package com.carlos.paperbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

// ===================== BINANCE (datos públicos, sin API keys) =====================

object Binance {
    private val BASES = listOf("https://api.binance.com/api/v3", "https://data-api.binance.vision/api/v3")

    private fun getText(path: String): String {
        val errors = mutableListOf<String>()
        for (base in BASES) {
            try {
                val c = URL(base + path).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "PaperBot/1.0")
                try {
                    if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                errors += "$base: ${e.message}"
            }
        }
        throw RuntimeException("No se pudo consultar Binance. " + errors.joinToString(" | "))
    }

    fun klines(symbol: String, interval: String, limit: Int): List<Candle> {
        val arr = JSONArray(getText("/klines?symbol=$symbol&interval=$interval&limit=${minOf(limit, 1000)}"))
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.getJSONArray(i)
            out += Candle(k.getLong(0), k.getLong(6), k.getString(1).toDouble(), k.getString(2).toDouble(),
                k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble())
        }
        if (out.size < 10) throw RuntimeException("Binance devolvió pocas velas para $symbol $interval.")
        return out
    }

    fun price(symbol: String): Double =
        JSONObject(getText("/ticker/price?symbol=$symbol")).getString("price").toDouble()
}

// ===================== ALMACENAMIENTO (estado + CSV) =====================

object Storage {
    private fun dir(ctx: Context): File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { it.mkdirs() }
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "paper_state.json")

    fun save(ctx: Context, a: PaperAccount) {
        val o = JSONObject()
        o.put("initial_capital", a.cfg.capitalInitial)
        o.put("cash", a.cash)
        o.put("realized_pnl", a.realizedPnl)
        o.put("total_trades", a.totalTrades)
        o.put("wins", a.wins)
        o.put("losses", a.losses)
        val pos = JSONObject()
        for ((k, p) in a.positions) {
            pos.put(k, JSONObject().apply {
                put("symbol", p.symbol); put("entryTime", p.entryTime); put("entryPrice", p.entryPrice)
                put("qty", p.qty); put("initialQty", p.initialQty); put("stopPrice", p.stopPrice)
                put("initialStop", p.initialStop); put("targetPrice", p.targetPrice); put("riskUsdt", p.riskUsdt)
                put("entryFee", p.entryFee); put("partialTaken", p.partialTaken)
                put("tradeRealizedPnl", p.tradeRealizedPnl); put("partialCount", p.partialCount)
            })
        }
        o.put("positions", pos)
        o.put("last_processed", JSONObject(a.lastProcessed as Map<*, *>))
        val pend = JSONObject()
        for ((k, v) in a.pendingSignals) pend.put(k, JSONObject().put("atr", v.atr).put("time", v.time))
        o.put("pending_signals", pend)
        val tmp = File(ctx.filesDir, "paper_state.json.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(stateFile(ctx))
    }

    fun load(ctx: Context, a: PaperAccount) {
        val f = stateFile(ctx)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            if (kotlin.math.abs(o.optDouble("initial_capital", -1.0) - a.cfg.capitalInitial) > 1e-8) return
            a.cash = o.getDouble("cash")
            a.realizedPnl = o.optDouble("realized_pnl", 0.0)
            a.totalTrades = o.optInt("total_trades"); a.wins = o.optInt("wins"); a.losses = o.optInt("losses")
            val pos = o.getJSONObject("positions")
            for (k in pos.keys()) {
                val p = pos.getJSONObject(k)
                a.positions[k] = Position(p.getString("symbol"), p.getString("entryTime"), p.getDouble("entryPrice"),
                    p.getDouble("qty"), p.getDouble("initialQty"), p.getDouble("stopPrice"), p.getDouble("initialStop"),
                    p.getDouble("targetPrice"), p.getDouble("riskUsdt"), p.getDouble("entryFee"),
                    p.getBoolean("partialTaken"), p.getDouble("tradeRealizedPnl"), p.getInt("partialCount"))
            }
            val lp = o.getJSONObject("last_processed")
            for (k in lp.keys()) a.lastProcessed[k] = lp.getString(k)
            val pd = o.getJSONObject("pending_signals")
            for (k in pd.keys()) a.pendingSignals[k] = Pending(pd.getJSONObject(k).getDouble("atr"), pd.getJSONObject(k).getString("time"))
        } catch (e: Exception) {
            // Estado corrupto: se conserva copia y se empieza limpio.
            f.renameTo(File(ctx.filesDir, "paper_state.corrupt.${System.currentTimeMillis()}.json"))
        }
    }

    fun reset(ctx: Context) { stateFile(ctx).delete() }

    @Synchronized
    fun appendCsv(ctx: Context, name: String, header: List<String>, row: List<Any?>) {
        val f = File(dir(ctx), name)
        val isNew = !f.exists() || f.length() == 0L
        f.appendText((if (isNew) header.joinToString(",") + "\n" else "") +
            row.joinToString(",") { "\"" + (it?.toString() ?: "").replace("\"", "\"\"") + "\"" } + "\n")
    }

    fun logEvent(ctx: Context, event: String, symbol: String, price: Double, detail: String) =
        appendCsv(ctx, "paper_events.csv", listOf("time", "event", "symbol", "price", "detail"),
            listOf(Instant.now().toString(), event, symbol, price, detail))

    fun logTrade(ctx: Context, t: ClosedTrade) =
        appendCsv(ctx, "paper_trades.csv",
            listOf("entry_time", "exit_time", "symbol", "entry_price", "exit_price", "initial_qty", "partial_exits", "final_reason", "net_pnl_usdt", "result"),
            listOf(t.entryTime, t.exitTime, t.symbol, t.entryPrice, t.exitPrice, t.initialQty, t.partialExits, t.finalReason, t.netPnl, t.result))

    fun filesPath(ctx: Context): String = dir(ctx).absolutePath
}
