package br.app.carteira

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.webkit.JavascriptInterface

/** Ponte da pagina para a digital ou o rosto do celular (window.AndroidBio). Usa a janela do proprio Android. */
class BioBridge(private val act: MainActivity) {

    private var cancel: CancellationSignal? = null

    /** true se o celular tem digital ou rosto cadastrado e liberado para apps. */
    @JavascriptInterface
    fun available(): Boolean {
        return try {
            val bm = act.getSystemService(BiometricManager::class.java)
            bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    /** Abre a janela de digital. O resultado volta para a pagina em window.__bioDone(true ou false). */
    @JavascriptInterface
    fun prompt() {
        act.runOnUiThread {
            try {
                cancel?.cancel()
                val cs = CancellationSignal()
                cancel = cs
                val ex = act.mainExecutor
                val bp = BiometricPrompt.Builder(act)
                    .setTitle("STOX3")
                    .setSubtitle("Confirme com a sua digital ou o seu rosto")
                    .setNegativeButton("Usar o PIN", ex) { _, _ -> done(false) }
                    .build()
                bp.authenticate(cs, ex, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        done(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        done(false)
                    }
                })
            } catch (e: Exception) {
                done(false)
            }
        }
    }

    private fun done(ok: Boolean) {
        act.runJs("window.__bioDone&&window.__bioDone(" + ok + ")")
    }
}
