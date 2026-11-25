package br.app.carteira

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Abre a carteira (pagina em assets/index.html) dentro de um WebView.
 * A pagina e servida por https://appassets.androidplatform.net/, assim o armazenamento local
 * (os seus dados) e estavel entre aberturas. Links externos abrem no navegador do celular.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null

    private var kbOn = false

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uris = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        chooser?.onReceiveValue(uris)
        chooser = null
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        web.post { web.evaluateJavascript("window.__ntPerm&&window.__ntPerm(" + ok + ")", null) }
    }

    /** Roda um trecho de JavaScript na pagina (usado pelas pontes). */
    fun runJs(js: String) {
        web.post { web.evaluateJavascript(js, null) }
    }

    /** Pede a permissao de notificacoes (Android 13 ou mais novo). O resultado volta para a pagina em window.__ntPerm. */
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            web.post { web.evaluateJavascript("window.__ntPerm&&window.__ntPerm(true)", null) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.bg))
        web = WebView(this)
        web.setBackgroundColor(getColor(R.color.bg))
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        // botao Voltar: primeiro fecha a pagina da acao, a tela cheia ou o menu Mais; so depois sai
        // 4.4.1: o JS fecha paginas/menus, volta para a primeira aba e para o Total; so entao pede um
        // segundo toque para sair. O callback volta a ficar ligado depois de sair (antes ficava desligado
        // e o proximo Voltar fechava o app direto, sem passar pelo app).
        var lastBack = 0L
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__stxBack&&window.__stxBack())?'1':'0'") { r ->
                    if (r != null && r.contains("1")) return@evaluateJavascript
                    if (web.canGoBack()) {
                        web.goBack()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBack < 2500) {
                            lastBack = 0L
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        } else {
                            lastBack = now
                            Toast.makeText(this@MainActivity, "Toque em Voltar de novo para sair", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        val controller = WindowCompat.getInsetsController(window, root)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            // avisa a pagina quando o teclado abre ou fecha (o menu de baixo some enquanto digita)
            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val kb = imeBottom > sysBottom
            if (kb != kbOn) {
                kbOn = kb
                web.post { web.evaluateJavascript("window.__kb&&window.__kb(" + kb + ")", null) }
            }
            WindowInsetsCompat.CONSUMED
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.setSupportMultipleWindows(false)
        // 4.0: tela fixa (sem zoom e sem o tamanho de fonte do sistema deformar o layout)
        s.textZoom = 100
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return loader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                if (u.host == HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                    // sem aplicativo para abrir o link: ignora
                }
                return true
            }

            // 4.4: se o Android encerrar a parte que desenha a tela (pouca memoria), recarrega o app em vez de ficar tudo branco
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                try {
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (e: Exception) {
                }
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = filePathCallback
                return try {
                    pick.launch(fileChooserParams!!.createIntent())
                    true
                } catch (e: Exception) {
                    chooser = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }
        }

        web.addJavascriptInterface(HttpBridge(web), "AndroidHttp")
        web.addJavascriptInterface(FilesBridge(this), "AndroidFiles")
        web.addJavascriptInterface(NotifBridge(this), "AndroidNotif")
        web.addJavascriptInterface(BioBridge(this), "AndroidBio")
        web.addJavascriptInterface(ReaderBridge(this), "AndroidWeb")
        web.addJavascriptInterface(PrintBridge(this), "AndroidPrint")

        Notify.ensureChannel(this)
        Notify.schedule(this)

        web.loadUrl("https://$HOST/assets/index.html")
    }

    /** Quando a janela do app volta a ter o foco, avisa a pagina (ela pede a digital de novo se estiver bloqueada). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::web.isInitialized) {
            runJs("window.__bioFocus&&window.__bioFocus()")
        }
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("AndroidHttp")
        web.removeJavascriptInterface("AndroidFiles")
        web.removeJavascriptInterface("AndroidNotif")
        super.onDestroy()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
