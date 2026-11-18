package br.app.carteira

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** v4.0: le a noticia dentro do app (tela propria com Voltar), sem sair para o navegador. */
class ReaderActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")
        if (url.isNullOrEmpty()) { finish(); return }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        root.fitsSystemWindows = true
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(12, 8, 12, 8)
        bar.setBackgroundColor(Color.parseColor("#1E3A8A"))
        val back = Button(this)
        back.text = "← Voltar"
        back.setOnClickListener { finish() }
        val title = TextView(this)
        title.text = intent.getStringExtra("title") ?: ""
        title.setTextColor(Color.WHITE)
        title.maxLines = 2
        title.setPadding(12, 0, 12, 0)
        val ext = Button(this)
        ext.text = "Navegador"
        ext.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: url))) } catch (e: Exception) { }
        }
        bar.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ext, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        prog.max = 100
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                return !(u.scheme == "http" || u.scheme == "https")
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                prog.progress = newProgress
                prog.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10))
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        web = w
        w.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}

/** Ponte para o JavaScript: AndroidWeb.read(url, titulo). Devolve "" se abriu. */
class ReaderBridge(private val ctx: Context) {
    @JavascriptInterface
    fun read(url: String, title: String): String {
        return try {
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return "link invalido"
            val i = Intent(ctx, ReaderActivity::class.java)
            i.putExtra("url", url)
            i.putExtra("title", title)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            ""
        } catch (e: Exception) {
            e.message ?: "erro"
        }
    }
}
