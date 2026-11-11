package br.app.carteira

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Consulta de cotacoes feita pelo aplicativo (sem as regras de CORS do navegador).
 * So aceita enderecos https das fontes gratuitas usadas pela carteira.
 */
class HttpBridge(private val web: WebView) {

    private val pool = Executors.newFixedThreadPool(2)

    private val allowed = setOf(
        "brapi.dev",
        "data-api.binance.vision",
        "api.binance.com",
        "api.mercadobitcoin.net",
        "olinda.bcb.gov.br"
    )

    @JavascriptInterface
    fun get(id: String, url: String) {
        pool.execute {
            var status = 0
            var body = ""
            try {
                val u = URL(url)
                if (u.protocol == "https" && u.host in allowed) {
                    val c = u.openConnection() as HttpURLConnection
                    c.requestMethod = "GET"
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "CarteiraApp/1.0")
                    status = c.responseCode
                    val stream = if (status in 200..299) c.inputStream else c.errorStream
                    body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    c.disconnect()
                }
            } catch (e: Exception) {
                status = 0
                body = ""
            }
            val js = "window.__qxDone(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ")"
            web.post { web.evaluateJavascript(js, null) }
        }
    }
}

/** Salva o arquivo de backup na pasta Downloads do celular. Devolve "" se deu certo, ou o motivo do erro. */
class FilesBridge(private val ctx: Context) {

    @JavascriptInterface
    fun saveText(name: String, text: String): String {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "nao foi possivel criar o arquivo"
            val out = ctx.contentResolver.openOutputStream(uri) ?: return "nao foi possivel gravar"
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ""
        } catch (e: Exception) {
            e.message ?: "erro"
        }
    }
}
