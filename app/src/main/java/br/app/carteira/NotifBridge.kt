package br.app.carteira

import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface

/** Ponte da pagina para as notificacoes (window.AndroidNotif). */
class NotifBridge(private val act: MainActivity) {

    /** "granted" se o Android deixa o STOX3 mostrar notificacoes, senao "denied". */
    @JavascriptInterface
    fun status(): String = if (Notify.allowed(act)) "granted" else "denied"

    @JavascriptInterface
    fun ask() {
        act.runOnUiThread { act.askNotifications() }
    }

    @JavascriptInterface
    fun config(open: Boolean, close: Boolean, snap: String) {
        Notify.configure(act.applicationContext, open, close, snap)
    }

    @JavascriptInterface
    fun test() {
        Notify.runNow(act.applicationContext)
    }

    @JavascriptInterface
    fun openSettings() {
        act.runOnUiThread {
            try {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
                act.startActivity(i)
            } catch (e: Exception) {
                // sem tela de configuracoes: ignora
            }
        }
    }
}
