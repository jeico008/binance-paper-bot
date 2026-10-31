package com.carlos.paperbot

import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ===================== MODELOS Y CONFIGURACIÓN =====================
// Valores idénticos a config.json del bot original. Edítalos aquí si quieres cambiar la estrategia.

data class BotConfig(
    val symbols: List<String> = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT"),
    val capitalInitial: Double = 591.0,
    val riskPerTradePct: Double = 0.5,
    val maxTotalOpenRiskPct: Double = 1.5,
    val maxPositions: Int = 3,
    val feePct: Double = 0.10,
    val slAtr: Double = 1.5,
    val tpR: Double = 2.0,
    val partialTakePct: Double = 50.0,
    val trailAtr: Double = 2.0,
    val emaTrendFast: Int = 50,
    val emaTrendSlow: Int = 200,
    val emaPullback: Int = 20,
    val rsiPeriod: Int = 14,
    val rsiMin: Double = 50.0,
    val rsiMax: Double = 65.0,
    val adxPeriod: Int = 14,
    val adxMin: Double = 20.0,
    val atrPeriod: Int = 14,
    val volumePeriod: Int = 20,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val refreshSeconds: Int = 60,
    val priceRefreshSeconds: Int = 10,
    val klinesLimit: Int = 500,
    val maxNotionalPerPosition: Double = 197.0,
) {
    val feeRate get() = feePct / 100.0
    val riskPerTrade get() = riskPerTradePct / 100.0
    val maxTotalOpenRisk get() = maxTotalOpenRiskPct / 100.0
}

data class Candle(
    val openTime: Long, val closeTime: Long,
    val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double,
)

/** Vela 1H con los indicadores calculados. */
data class IndCandle(
    val openTime: Long, val closeTime: Long,
    val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double,
    val ema20: Double, val ema50: Double, val rsi: Double, val atr: Double, val adx: Double,
    val macdHist: Double, val volSma: Double,
)

fun isoOf(ms: Long): String = Instant.ofEpochMilli(ms).toString()

// ===================== INDICADORES (equivalentes a pandas ewm adjust=False) =====================

object Ind {
    /** pandas: series.ewm(alpha=alpha, adjust=False, min_periods=minPeriods).mean() */
    fun ewm(src: DoubleArray, alpha: Double, minPeriods: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        var state = Double.NaN
        var count = 0
        for (i in src.indices) {
            val x = src[i]
            if (x.isNaN()) {
                if (!state.isNaN() && count >= minPeriods) out[i] = state
                continue
            }
            state = if (state.isNaN()) x else alpha * x + (1 - alpha) * state
            count++
            if (count >= minPeriods) out[i] = state
        }
        return out
    }

    fun ema(src: DoubleArray, period: Int) = ewm(src, 2.0 / (period + 1), period)

    fun rsi(close: DoubleArray, period: Int): DoubleArray {
        val n = close.size
        val up = DoubleArray(n) { Double.NaN }
        val down = DoubleArray(n) { Double.NaN }
        for (i in 1 until n) {
            val d = close[i] - close[i - 1]
            up[i] = max(d, 0.0)
            down[i] = max(-d, 0.0)
        }
        val au = ewm(up, 1.0 / period, period)
        val ad = ewm(down, 1.0 / period, period)
        return DoubleArray(n) { i ->
            val u = au[i]
            val d = ad[i]
            when {
                u.isNaN() || d.isNaN() -> Double.NaN
                d == 0.0 && u > 0.0 -> 100.0
                d == 0.0 -> 50.0
                else -> 100.0 - 100.0 / (1.0 + u / d)
            }
        }
    }

    private fun trueRange(c: List<Candle>): DoubleArray = DoubleArray(c.size) { i ->
        if (i == 0) c[i].high - c[i].low
        else {
            val pc = c[i - 1].close
            max(c[i].high - c[i].low, max(abs(c[i].high - pc), abs(c[i].low - pc)))
        }
    }

    fun atr(c: List<Candle>, period: Int): DoubleArray = ewm(trueRange(c), 1.0 / period, period)

    fun adx(c: List<Candle>, period: Int): DoubleArray {
        val n = c.size
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        for (i in 1 until n) {
            val up = c[i].high - c[i - 1].high
            val down = -(c[i].low - c[i - 1].low)
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
        }
        val a = 1.0 / period
        val smoothTr = ewm(ewm(trueRange(c), 1.0, 1), a, period)
        val pe = ewm(plusDm, a, period)
        val me = ewm(minusDm, a, period)
        val dx = DoubleArray(n) { i ->
            val s = smoothTr[i]
            if (s.isNaN() || s == 0.0 || pe[i].isNaN() || me[i].isNaN()) Double.NaN
            else {
                val pdi = 100 * pe[i] / s
                val mdi = 100 * me[i] / s
                val sum = pdi + mdi
                if (sum == 0.0) Double.NaN else 100 * abs(pdi - mdi) / sum
            }
        }
        return ewm(dx, a, period)
    }

    fun rollingMean(src: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        var sum = 0.0
        for (i in src.indices) {
            sum += src[i]
            if (i >= window) sum -= src[i - window]
            if (i >= window - 1) out[i] = sum / window
        }
        return out
    }
}

// ===================== SEÑAL =====================

class SignalResult(
    val price: Double, val rsi: Double, val adx: Double, val atr: Double,
    val ema20: Double, val ema50: Double, val ema200h4: Double, val macdHist: Double,
    val volume: Double, val volumeAvg: Double,
    val candleKey: String,
    val checks: LinkedHashMap<String, Boolean>,
    val signal: Boolean,
    val lastCandle: IndCandle,
) {
    val trendUp get() = ema20 > ema50 && price > ema200h4
}

object Strategy {
    /** Quita la vela en formación: nunca se opera con una vela sin cerrar. */
    fun closedCandles(c: List<Candle>, nowMs: Long): List<Candle> = c.filter { it.closeTime < nowMs }

    fun indicators1h(c: List<Candle>, cfg: BotConfig): List<IndCandle> {
        val close = DoubleArray(c.size) { c[it].close }
        val vol = DoubleArray(c.size) { c[it].volume }
        val e20 = Ind.ema(close, cfg.emaPullback)
        val e50 = Ind.ema(close, cfg.emaTrendFast)
        val rsi = Ind.rsi(close, cfg.rsiPeriod)
        val atr = Ind.atr(c, cfg.atrPeriod)
        val adx = Ind.adx(c, cfg.adxPeriod)
        val fast = Ind.ema(close, cfg.macdFast)
        val slow = Ind.ema(close, cfg.macdSlow)
        val macd = DoubleArray(c.size) { fast[it] - slow[it] }
        val sig = Ind.ema(macd, cfg.macdSignal)
        val vsma = Ind.rollingMean(vol, cfg.volumePeriod)
        return c.indices.map { i ->
            IndCandle(c[i].openTime, c[i].closeTime, c[i].open, c[i].high, c[i].low, c[i].close, c[i].volume,
                e20[i], e50[i], rsi[i], atr[i], adx[i], macd[i] - sig[i], vsma[i])
        }
    }

    fun latestSignal(d1raw: List<Candle>, d4raw: List<Candle>, cfg: BotConfig, nowMs: Long): SignalResult {
        val c1 = closedCandles(d1raw, nowMs)
        val c4 = closedCandles(d4raw, nowMs)
        val h1 = indicators1h(c1, cfg)
        val close4 = DoubleArray(c4.size) { c4[it].close }
        val e50_4 = Ind.ema(close4, cfg.emaTrendFast)
        val e200_4 = Ind.ema(close4, cfg.emaTrendSlow)
        val required = max(220, cfg.emaTrendSlow + 5)
        if (h1.size < required || c4.size < required)
            throw IllegalStateException("Se necesitan al menos $required velas cerradas en 1H y 4H.")

        val last = h1[h1.size - 1]
        val prev = h1[h1.size - 2]
        val k = c4.size - 1
        val trendClose = close4[k]

        val checks = linkedMapOf(
            "Tendencia 4H" to (trendClose > e200_4[k] && e50_4[k] > e200_4[k] && e50_4[k] > e50_4[k - 1]),
            "Recuperación EMA20" to (prev.close <= prev.ema20 && last.close > last.ema20),
            "RSI 50-65 subiendo" to (last.rsi >= cfg.rsiMin && last.rsi <= cfg.rsiMax && last.rsi > prev.rsi),
            "MACD histograma creciente" to (last.macdHist > prev.macdHist && last.macdHist > 0),
            "ADX > ${cfg.adxMin}" to (last.adx > cfg.adxMin),
            "Volumen >= promedio" to (last.volume >= last.volSma),
        )
        val signal = checks.values.all { it } && last.close > last.open
        return SignalResult(last.close, last.rsi, last.adx, last.atr, last.ema20, last.ema50, e200_4[k],
            last.macdHist, last.volume, last.volSma, isoOf(last.closeTime), LinkedHashMap(checks), signal, last)
    }
}

// ===================== CUENTA SIMULADA =====================

data class Position(
    val symbol: String, val entryTime: String, val entryPrice: Double,
    var qty: Double, val initialQty: Double, var stopPrice: Double, val initialStop: Double,
    val targetPrice: Double, val riskUsdt: Double, var entryFee: Double,
    var partialTaken: Boolean = false, var tradeRealizedPnl: Double = 0.0, var partialCount: Int = 0,
)

data class Pending(val atr: Double, val time: String)

data class ClosedTrade(
    val entryTime: String, val exitTime: String, val symbol: String, val entryPrice: Double,
    val exitPrice: Double, val initialQty: Double, val partialExits: Int, val finalReason: String,
    val netPnl: Double, val result: String,
)

class PaperAccount(val cfg: BotConfig) {
    var cash = cfg.capitalInitial
    val positions = linkedMapOf<String, Position>()
    var realizedPnl = 0.0
    var totalTrades = 0
    var wins = 0
    var losses = 0
    val lastProcessed = hashMapOf<String, String>()
    val pendingSignals = hashMapOf<String, Pending>()

    /** event, symbol, price, detail */
    var onEvent: (String, String, Double, String) -> Unit = { _, _, _, _ -> }
    var onTradeClosed: (ClosedTrade) -> Unit = {}

    fun equity(prices: Map<String, Double> = emptyMap()): Double =
        cash + positions.values.sumOf { it.qty * (prices[it.symbol] ?: it.entryPrice) }

    fun openRisk(): Double = positions.values.sumOf { max(0.0, it.entryPrice - it.stopPrice) * it.qty }

    fun canOpen(symbol: String) = symbol !in positions && positions.size < cfg.maxPositions

    fun openPosition(symbol: String, price: Double, atr: Double, timestamp: String, prices: Map<String, Double>): Boolean {
        if (!canOpen(symbol) || !atr.isFinite() || atr <= 0 || price <= 0) return false
        val equity = equity(prices)
        val stopDistance = cfg.slAtr * atr
        val stopPct = stopDistance / price
        if (stopPct <= 0 || stopPct >= 0.15) return false
        val riskBudget = min(equity * cfg.riskPerTrade, max(0.0, equity * cfg.maxTotalOpenRisk - openRisk()))
        val qtyByRisk = if (stopDistance != 0.0) riskBudget / stopDistance else 0.0
        val notionalCap = min(cfg.maxNotionalPerPosition, cash / (1 + cfg.feeRate))
        val qty = min(qtyByRisk, notionalCap / price)
        val notional = qty * price
        val fee = notional * cfg.feeRate
        if (qty <= 0 || notional + fee > cash || notional < 10) return false
        val stop = price - stopDistance
        cash -= notional + fee
        val target = price + cfg.tpR * stopDistance
        positions[symbol] = Position(symbol, timestamp, price, qty, qty, stop, stop, target, qty * stopDistance, fee)
        onEvent("ENTRADA", symbol, price, "qty=%.8f; stop=%.8f; target=%.8f".format(qty, stop, target))
        return true
    }

    private fun sell(symbol: String, price: Double, qtyReq: Double, reason: String) {
        val p = positions[symbol] ?: return
        val qty = min(qtyReq, p.qty)
        if (qty <= 0) return
        val gross = qty * price
        val fee = gross * cfg.feeRate
        cash += gross - fee
        val entryFeeAlloc = p.entryFee * (qty / p.qty)
        val pnl = (price - p.entryPrice) * qty - fee - entryFeeAlloc
        p.entryFee -= entryFeeAlloc
        p.qty -= qty
        p.tradeRealizedPnl += pnl
        realizedPnl += pnl
        if ("TAKE PROFIT" in reason) p.partialCount++
        onEvent(reason, symbol, price, "qty=%.8f; pnl_neto_tramo=%.4f; pnl_operacion_acum=%.4f".format(qty, pnl, p.tradeRealizedPnl))
        if (p.qty <= p.initialQty * 1e-8) finalizeTrade(symbol, p, reason, price)
    }

    private fun finalizeTrade(symbol: String, p: Position, reason: String, exitPrice: Double) {
        val total = p.tradeRealizedPnl
        totalTrades++
        if (total > 1e-9) wins++ else if (total < -1e-9) losses++
        onTradeClosed(ClosedTrade(p.entryTime, Instant.now().toString(), symbol, p.entryPrice, exitPrice,
            p.initialQty, p.partialCount, reason, total,
            if (total > 1e-9) "GANADORA" else if (total < -1e-9) "PERDEDORA" else "NEUTRA"))
        onEvent("CIERRE_OPERACION", symbol, exitPrice, "resultado_neto=%.4f; parciales=%d".format(total, p.partialCount))
        positions.remove(symbol)
    }

    fun updatePosition(symbol: String, c: IndCandle) {
        var p = positions[symbol] ?: return
        // Prioridad conservadora: stop antes que objetivo si ambos se tocan en la misma vela.
        if (c.low <= p.stopPrice) {
            sell(symbol, p.stopPrice, p.qty, "STOP / TRAILING")
            return
        }
        if (!p.partialTaken && c.high >= p.targetPrice) {
            sell(symbol, p.targetPrice, p.initialQty * (cfg.partialTakePct / 100.0), "TAKE PROFIT PARCIAL")
            p = positions[symbol] ?: return
            p.partialTaken = true
            p.stopPrice = max(p.stopPrice, p.entryPrice)
        }
        val p2 = positions[symbol] ?: return
        if (c.atr.isFinite() && c.atr > 0) p2.stopPrice = max(p2.stopPrice, c.close - cfg.trailAtr * c.atr)
    }

    /**
     * Equivale a apply_scan() del bot de escritorio: una señal al cierre de la vela N
     * se ejecuta en la apertura de la vela N+1.
     */
    fun applyScan(symbol: String, res: SignalResult, prices: Map<String, Double>) {
        if (lastProcessed[symbol] == res.candleKey) return
        val candle = res.lastCandle
        val pending = pendingSignals[symbol]
        if (pending != null && canOpen(symbol)) {
            openPosition(symbol, candle.open, pending.atr, isoOf(candle.openTime), prices)
        }
        updatePosition(symbol, candle)
        pendingSignals.remove(symbol)
        if (res.signal) {
            pendingSignals[symbol] = Pending(res.atr, res.candleKey)
            onEvent("SEÑAL", symbol, res.price, "Señal de compra al cierre 1H; entrada en la apertura de la siguiente vela. ATR=%.6f".format(res.atr))
        }
        lastProcessed[symbol] = res.candleKey
    }
}
