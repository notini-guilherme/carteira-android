package br.app.carteira

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
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
                val ch = NotificationChannel(CHANNEL, "Mercado e alertas", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "Resumo na abertura e no fechamento, alertas de alta e queda e aviso de proventos"
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
            .putBoolean("rules", rulesOn(snap))
            .apply()
        schedule(ctx)
    }

    fun schedule(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wm = WorkManager.getInstance(ctx)
        if (sp.getBoolean("open", false) || sp.getBoolean("close", false) || sp.getBoolean("rules", false)) {
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
        try {
            alerts(ctx, sp, snap, now, mins, day)
        } catch (e: Exception) {
            // um erro nos avisos de movimento nao pode derrubar o resumo de abertura e fechamento
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Avisos de movimento (carteira, ativos, favoritos, cripto) e de proventos, com limite de frequencia.
    // As regras vem da pagina dentro do resumo: {"r":{"pd":2,"pdir":"b","ad":3,"adir":"b","fv":3,"cr":5,"ev":1,"fq":"d","cap":5}}
    // (0 = desligado). Nenhum valor em reais aparece nas notificacoes.
    // ---------------------------------------------------------------------------------------------
    private const val AL_FROM = 10 * 60 + 25
    private const val AL_TO = 18 * 60 + 5

    private fun rulesOn(snapJson: String): Boolean {
        val snap = try { JSONObject(snapJson) } catch (e: Exception) { return false }
        val r = snap.optJSONObject("r") ?: return false
        return r.optDouble("pd", 0.0) > 0 || r.optDouble("ad", 0.0) > 0 || r.optDouble("fv", 0.0) > 0 ||
            r.optDouble("cr", 0.0) > 0 || r.optInt("ev", 0) > 0
    }

    private fun dirOk(d: Double, dir: String): Boolean = when (dir) {
        "d" -> d < 0
        "u" -> d > 0
        else -> true
    }

    private fun lastBand(sp: SharedPreferences, key: String, day: String): Int {
        val s = sp.getString(key, "") ?: ""
        return if (s.startsWith(day + ":")) (s.substring(day.length + 1).toIntOrNull() ?: 0) else 0
    }

    /** Faixa atingida (1 = passou do limite, 2 = passou do dobro...). 0 = nao passou ou ja avisou (conforme a frequencia). */
    private fun newBand(sp: SharedPreferences, key: String, day: String, absPct: Double, thr: Double, fq: String): Int {
        if (!(thr > 0)) return 0
        val band = (absPct / thr).toInt()
        if (band < 1) return 0
        val last = lastBand(sp, key, day)
        if (last >= 1 && (fq != "b" || band <= last)) return 0
        return band
    }

    private fun capLeft(sp: SharedPreferences, day: String, cap: Int): Boolean {
        if (cap <= 0) return true
        return lastBand(sp, "cnt", day) < cap
    }

    private fun capUse(sp: SharedPreferences, day: String) {
        sp.edit().putString("cnt", day + ":" + (lastBand(sp, "cnt", day) + 1)).apply()
    }

    private class Hit(val name: String, val pct: Double, val key: String, val band: Int)

    private fun listText(hits: List<Hit>): String {
        val sorted = hits.sortedByDescending { Math.abs(it.pct) }
        val sb = StringBuilder()
        var n = 0
        for (h in sorted) {
            if (n >= 5) break
            if (n > 0) sb.append(" · ")
            sb.append(h.name).append(' ').append(pct(h.pct))
            n++
        }
        if (sorted.size > n) sb.append(" · +").append(sorted.size - n)
        return sb.toString()
    }

    private fun alerts(ctx: Context, sp: SharedPreferences, snap: String, now: Calendar, mins: Int, day: String) {
        val root = try { JSONObject(snap) } catch (e: Exception) { return }
        val r = root.optJSONObject("r") ?: return
        val key = root.optString("k", "")
        val fq = r.optString("fq", "d")
        val cap = r.optInt("cap", 0)
        val pd = r.optDouble("pd", 0.0)
        val ad = r.optDouble("ad", 0.0)
        val fv = r.optDouble("fv", 0.0)
        val cr = r.optDouble("cr", 0.0)
        val pdir = r.optString("pdir", "b")
        val adir = r.optString("adir", "b")
        val iso = String.format(Locale.US, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))

        // proventos de hoje (data com, data ex, pagamento): uma vez por dia
        if (r.optInt("ev", 0) > 0 && mins >= 9 * 60 && mins <= 20 * 60 && sp.getString("done_ev", "") != day) {
            val ev = root.optJSONArray("e")
            if (ev != null) {
                val com = ArrayList<String>()
                val ex = ArrayList<String>()
                val pay = ArrayList<String>()
                for (i in 0 until ev.length()) {
                    val row = ev.optJSONArray(i) ?: continue
                    if (row.optString(1, "") != iso) continue
                    val tk = row.optString(0, "")
                    when (row.optString(2, "")) {
                        "com" -> com.add(tk)
                        "ex" -> ex.add(tk)
                        "pag" -> pay.add(tk)
                    }
                }
                if (com.isNotEmpty() || ex.isNotEmpty() || pay.isNotEmpty()) {
                    val parts = ArrayList<String>()
                    if (pay.isNotEmpty()) parts.add("Pagamento previsto hoje: " + pay.joinToString(", "))
                    if (com.isNotEmpty()) parts.add("Data com hoje (último dia para ter direito): " + com.joinToString(", "))
                    if (ex.isNotEmpty()) parts.add("Data ex hoje: " + ex.joinToString(", "))
                    post(ctx, 8, "Proventos de hoje", parts.joinToString(". ") + ". Confira no extrato da corretora.")
                    sp.edit().putString("done_ev", day).apply()
                }
            }
        }

        val dow = now.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return
        if (mins < AL_FROM || mins > AL_TO) return
        if (!(pd > 0 || ad > 0 || fv > 0 || cr > 0)) return

        val cache = HashMap<String, Quote?>()
        fun quote(tk: String): Quote? {
            if (cache.containsKey(tk)) return cache[tk]
            val q = try { fetch(tk, key) } catch (e: Exception) { null }
            cache[tk] = q
            try { Thread.sleep(150) } catch (e: Exception) { }
            return q
        }

        val rows = root.optJSONArray("p")

        // carteira de acoes (no total) e ativos da carteira
        if ((pd > 0 || ad > 0) && rows != null && rows.length() > 0) {
            var dayChg = 0.0
            var dayBase = 0.0
            var todayN = 0
            var bestT = ""
            var bestP = Double.NEGATIVE_INFINITY
            var worstT = ""
            var worstP = Double.POSITIVE_INFINITY
            val hits = ArrayList<Hit>()
            val n = Math.min(rows.length(), 40)
            for (i in 0 until n) {
                val row = rows.optJSONArray(i) ?: continue
                val tk = row.optString(0, "")
                val qty = row.optDouble(1, 0.0)
                if (tk.isEmpty() || !(qty > 0)) continue
                val q = quote(tk) ?: continue
                if (!sameDay(q.t, now)) continue
                todayN++
                dayChg += qty * (q.p - q.prev)
                dayBase += qty * q.prev
                val d = (q.p / q.prev - 1) * 100
                if (d > bestP) { bestP = d; bestT = tk }
                if (d < worstP) { worstP = d; worstT = tk }
                if (ad > 0 && dirOk(d, adir)) {
                    val b = newBand(sp, "ad_" + tk, day, Math.abs(d), ad, fq)
                    if (b > 0) hits.add(Hit(tk, d, "ad_" + tk, b))
                }
            }
            if (pd > 0 && todayN > 0 && dayBase > 0) {
                val dp = dayChg / dayBase * 100
                if (dirOk(dp, pdir) && capLeft(sp, day, cap)) {
                    val b = newBand(sp, "pd", day, Math.abs(dp), pd, fq)
                    if (b > 0) {
                        val sb = StringBuilder("Passou de ")
                        sb.append(String.format(BR, "%.0f", pd)).append("% no dia. ")
                        if (todayN >= 2 && bestT != worstT) {
                            sb.append("Maior alta: ").append(bestT).append(' ').append(pct(bestP)).append(". ")
                            sb.append("Maior queda: ").append(worstT).append(' ').append(pct(worstP)).append(". ")
                        }
                        sb.append("As cotações chegam com cerca de 30 min de atraso.")
                        post(ctx, 4, "Carteira " + (if (dp > 0) "em alta: " else "em queda: ") + pct(dp) + " hoje", sb.toString().trim())
                        sp.edit().putString("pd", day + ":" + b).apply()
                        capUse(sp, day)
                    }
                }
            }
            if (hits.isNotEmpty() && capLeft(sp, day, cap)) {
                val title = if (hits.size == 1) hits[0].name + " " + pct(hits[0].pct) + " hoje" else hits.size.toString() + " ativos da carteira mexendo muito"
                post(ctx, 5, title, "Passaram de " + String.format(BR, "%.0f", ad) + "% no dia: " + listText(hits))
                for (h in hits) sp.edit().putString(h.key, day + ":" + h.band).apply()
                capUse(sp, day)
            }
        }

        // favoritos (que nao estao na carteira)
        val fav = root.optJSONArray("f")
        if (fv > 0 && fav != null && fav.length() > 0) {
            val hits = ArrayList<Hit>()
            val n = Math.min(fav.length(), 25)
            for (i in 0 until n) {
                val tk = fav.optString(i, "")
                if (tk.isEmpty()) continue
                val q = quote(tk) ?: continue
                if (!sameDay(q.t, now)) continue
                val d = (q.p / q.prev - 1) * 100
                val b = newBand(sp, "fv_" + tk, day, Math.abs(d), fv, fq)
                if (b > 0) hits.add(Hit(tk, d, "fv_" + tk, b))
            }
            if (hits.isNotEmpty() && capLeft(sp, day, cap)) {
                val title = if (hits.size == 1) "Favorito " + hits[0].name + " " + pct(hits[0].pct) + " hoje" else hits.size.toString() + " favoritos mexendo muito"
                post(ctx, 6, title, "Passaram de " + String.format(BR, "%.0f", fv) + "% no dia: " + listText(hits))
                for (h in hits) sp.edit().putString(h.key, day + ":" + h.band).apply()
                capUse(sp, day)
            }
        }

        // cripto: variacao de 24 h (Binance)
        val coins = root.optJSONArray("c")
        if (cr > 0 && coins != null && coins.length() > 0) {
            val hits = ArrayList<Hit>()
            val n = Math.min(coins.length(), 15)
            for (i in 0 until n) {
                val c = coins.optString(i, "")
                if (c.isEmpty()) continue
                val d = fetchCripto(c) ?: continue
                try { Thread.sleep(120) } catch (e: Exception) { }
                val b = newBand(sp, "cr_" + c, day, Math.abs(d), cr, fq)
                if (b > 0) hits.add(Hit(c, d, "cr_" + c, b))
            }
            if (hits.isNotEmpty() && capLeft(sp, day, cap)) {
                val title = if (hits.size == 1) hits[0].name + " " + pct(hits[0].pct) + " em 24 h" else hits.size.toString() + " moedas mexendo muito"
                post(ctx, 7, title, "Passaram de " + String.format(BR, "%.0f", cr) + "% em 24 horas: " + listText(hits))
                for (h in hits) sp.edit().putString(h.key, day + ":" + h.band).apply()
                capUse(sp, day)
            }
        }
    }

    private fun fetchCripto(coin: String): Double? {
        for (suf in arrayOf("BRL", "USDT")) {
            for (host in arrayOf("https://data-api.binance.vision", "https://api.binance.com")) {
                val v = try { fetchBinance(host, coin + suf) } catch (e: Exception) { null }
                if (v != null) return v
            }
        }
        return null
    }

    private fun fetchBinance(host: String, sym: String): Double? {
        val u = URL(host + "/api/v3/ticker/24hr?symbol=" + URLEncoder.encode(sym, "UTF-8"))
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "CarteiraApp/1.0")
            if (c.responseCode !in 200..299) return null
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val v = JSONObject(body).optDouble("priceChangePercent", Double.NaN)
            return if (v.isNaN()) null else v
        } finally {
            c.disconnect()
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
        if (kind == "open") sb.append("Cotações com atraso de cerca de 30 min.")
        return Out.Ok(title, sb.toString().trim())
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
