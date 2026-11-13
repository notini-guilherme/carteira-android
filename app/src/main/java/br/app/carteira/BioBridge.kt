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

    /** Abre a janela de digital. O resultado volta para a pagina em window.__bioDone(ok, codigo, seq). */
    @JavascriptInterface
    fun prompt(seq: Int) {
        act.runOnUiThread {
            var finished = false
            fun fin(ok: Boolean, code: Int) {
                if (finished) return
                finished = true
                done(ok, code, seq)
            }
            try {
                cancel?.cancel()
                val cs = CancellationSignal()
                cancel = cs
                val ex = act.mainExecutor
                val bp = BiometricPrompt.Builder(act)
                    .setTitle("STOX3")
                    .setSubtitle("Confirme com a sua digital ou o seu rosto")
                    .setNegativeButton("Usar o PIN", ex) { _, _ -> fin(false, -1) }
                    .build()
                bp.authenticate(cs, ex, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        fin(true, 0)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        fin(false, errorCode)
                    }
                })
            } catch (e: Exception) {
                fin(false, 0)
            }
        }
    }

    private fun done(ok: Boolean, code: Int, seq: Int) {
        act.runJs("window.__bioDone&&window.__bioDone(" + ok + "," + code + "," + seq + ")")
    }
}
