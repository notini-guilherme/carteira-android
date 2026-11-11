package br.app.carteira

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Avisos da abertura e do fechamento do mercado, com o resultado da carteira de acoes em %.
 * A pagina entrega um resumo das posicoes (codigo, quantidade, custo) em config(); um trabalho periodico do
 * WorkManager (a cada 15 min) confere o horario e, na janela certa e uma vez por dia, consulta as cotacoes e avisa.
 */
object Notify {

    const val CHANNEL = "mercado"
    private const val PREFS = "stox3_nt"
    private const val WORK = "stox3-nt"
    private const val OPEN_FROM = 10 * 60 + 35
    private const val OPEN_TO = 12 * 60 + 30
    private const val CLOSE_FROM = 18 * 60 + 35
    private const val CLOSE_TO = 21 * 60
    private val SP: TimeZone = TimeZone.getTimeZone("America/Sao_Paulo")
    private val BR = Locale("pt", "BR")

    private class Quote(val p: Double, val prev: Double, val t: Long)

    private sealed class Out {
        class Ok(val title: String, val body: String) : Out()
        object Closed : Out()
        object NoData : Out()
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Mercado (abertura e fechamento)", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "Resultado da carteira de ações na abertura e no fechamento do mercado"
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun allowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** Chamado pela pagina: liga/desliga os avisos e guarda o resumo das posicoes. */
    fun configure(ctx: Context, open: Boolean, close: Boolean, snap: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("open", open)
            .putBoolean("close", close)
            .putString("snap", snap)
            .apply()
        schedule(ctx)
    }

    fun schedule(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wm = WorkManager.getInstance(ctx)
        if (sp.getBoolean("open", false) || sp.getBoolean("close", false)) {
            val req = PeriodicWorkRequest.Builder(NotifWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    /** Botao "Enviar notificacao de teste": roda agora, ignorando horario e feriado. */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequest.Builder(NotifWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putBoolean("test", true).build())
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    private fun dayKey(c: Calendar): String =
        String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    private fun sameDay(ms: Long, ref: Calendar): Boolean {
        if (ms <= 0L) return false
        val c = Calendar.getInstance(SP)
        c.timeInMillis = ms
        return dayKey(c) == dayKey(ref)
    }

    /** Executado pelo WorkManager a cada 15 minutos (ou pelo teste). */
    fun tick(ctx: Context, test: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snap = sp.getString("snap", "") ?: ""
        val now = Calendar.getInstance(SP)
        if (test) {
            val out = compose(snap, "close", now, true)
            when (out) {
                is Out.Ok -> post(ctx, 3, "Teste: " + out.title, out.body)
                is Out.Closed -> post(ctx, 3, "STOX3", "Não encontrei cotações para testar.")
                is Out.NoData -> post(ctx, 3, "STOX3", "Não consegui buscar as cotações agora. Confira a internet e a chave de acesso em Configurações, em Avançado.")
            }
            return
        }
        val dow = now.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val day = dayKey(now)
        for (kind in arrayOf("open", "close")) {
            if (!sp.getBoolean(kind, false)) continue
            val from = if (kind == "open") OPEN_FROM else CLOSE_FROM
            val to = if (kind == "open") OPEN_TO else CLOSE_TO
            if (mins < from || mins > to) continue
            if (sp.getString("done_" + kind, "") == day) continue
            val tr = sp.getString("tries_" + kind, "") ?: ""
            val tries = if (tr.startsWith(day + ":")) (tr.substring(day.length + 1).toIntOrNull() ?: 0) else 0
            if (tries >= 4) continue
            sp.edit().putString("tries_" + kind, day + ":" + (tries + 1)).apply()
            when (val out = compose(snap, kind, now, false)) {
                is Out.Ok -> {
                    post(ctx, if (kind == "open") 1 else 2, out.title, out.body)
                    sp.edit().putString("done_" + kind, day).apply()
                }
                is Out.Closed -> sp.edit().putString("done_" + kind, day).apply()
                is Out.NoData -> { /* tenta de novo no proximo ciclo */ }
            }
        }
    }

    private fun pct(v: Double): String = String.format(BR, "%+.2f%%", v)

    private fun compose(snapJson: String, kind: String, now: Calendar, test: Boolean): Out {
        val snap = try { JSONObject(snapJson) } catch (e: Exception) { return Out.Closed }
        val key = snap.optString("k", "")
        val arr = snap.optJSONArray("p") ?: return Out.Closed
        if (arr.length() == 0) return Out.Closed
        var fetched = 0
        var todayN = 0
        var dayChg = 0.0
        var dayBase = 0.0
        var value = 0.0
        var cost = 0.0
        var bestT = ""
        var bestP = Double.NEGATIVE_INFINITY
        var worstT = ""
        var worstP = Double.POSITIVE_INFINITY
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val tk = row.optString(0, "")
            val qty = row.optDouble(1, 0.0)
            val cst = row.optDouble(2, 0.0)
            if (tk.isEmpty() || !(qty > 0)) continue
            val q = try { fetch(tk, key) } catch (e: Exception) { null }
            if (i < arr.length() - 1) { try { Thread.sleep(150) } catch (e: Exception) { } }
            if (q == null) continue
            fetched++
            value += qty * q.p
            cost += cst
            if (test || sameDay(q.t, now)) {
                todayN++
                dayChg += qty * (q.p - q.prev)
                dayBase += qty * q.prev
                val d = (q.p / q.prev - 1) * 100
                if (d > bestP) { bestP = d; bestT = tk }
                if (d < worstP) { worstP = d; worstT = tk }
            }
        }
        if (fetched == 0) return Out.NoData
        if (todayN == 0 || !(dayBase > 0)) return Out.Closed
        val dayPct = dayChg / dayBase * 100
        val title = if (kind == "open") "Mercado aberto: carteira " + pct(dayPct) + " até agora" else "Mercado fechou: carteira " + pct(dayPct) + " hoje"
        val sb = StringBuilder()
        if (cost > 0) sb.append("Resultado total das posições: ").append(pct((value / cost - 1) * 100)).append(". ")
        if (todayN >= 2 && bestT.isNotEmpty() && worstT.isNotEmpty() && bestT != worstT) {
            sb.append("Maior alta: ").append(bestT).append(' ').append(pct(bestP)).append(". ")
            sb.append("Maior queda: ").append(worstT).append(' ').append(pct(worstP)).append(". ")
        }
        if (todayN < arr.length()) sb.append("Contei ").append(todayN).append(" de ").append(arr.length()).append(" ativos. ")
        sb.append("Cotações com atraso de cerca de 30 min.")
        return Out.Ok(title, sb.toString())
    }

    private fun parseTime(o: JSONObject): Long {
        val v = o.opt("regularMarketTime") ?: return 0L
        return try {
            when (v) {
                is Number -> { val l = v.toLong(); if (l < 100000000000L) l * 1000 else l }
                is String -> try { java.time.Instant.parse(v).toEpochMilli() } catch (e: Exception) { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun fetch(tk: String, key: String): Quote? {
        val q = if (key.isNotEmpty()) "?token=" + URLEncoder.encode(key, "UTF-8") else ""
        val u = URL("https://brapi.dev/api/quote/" + URLEncoder.encode(tk, "UTF-8") + q)
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "CarteiraApp/1.0")
            if (c.responseCode !in 200..299) return null
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val r = JSONObject(body).optJSONArray("results")?.optJSONObject(0) ?: return null
            val p = r.optDouble("regularMarketPrice", 0.0)
            var prev = r.optDouble("regularMarketPreviousClose", 0.0)
            if (!(prev > 0)) {
                val chg = r.optDouble("regularMarketChangePercent", Double.NaN)
                if (!chg.isNaN() && p > 0) prev = p / (1 + chg / 100)
            }
            if (!(p > 0) || !(prev > 0)) return null
            return Quote(p, prev, parseTime(r))
        } finally {
            c.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, title: String, body: String) {
        if (!allowed(ctx)) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stox)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // sem permissao de notificacoes: nada a fazer
        }
    }
}

/** Trabalho periodico (15 min) que chama Notify.tick; o teste chama uma vez com test=true. */
class NotifWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            Notify.tick(applicationContext, inputData.getBoolean("test", false))
        } catch (e: Exception) {
            // nao deixa o trabalho falhar por um erro de rede ou de formato
        }
        return Result.success()
    }
}
