package com.carlos.paperbot

/** Descripción de cada parámetro editable: clave, etiqueta, tipo ("text" | "int" | "dec") y valor actual. */
class FieldSpec(val key: String, val label: String, val kind: String, val get: (BotConfig) -> String)

object ConfigSpec {
    private fun d(v: Double) = v.toString().removeSuffix(".0")

    val fields = listOf(
        FieldSpec("symbols", "Pares (separados por coma)", "text") { it.symbols.joinToString(",") },
        FieldSpec("capitalInitial", "Capital inicial (USDT)", "dec") { d(it.capitalInitial) },
        FieldSpec("riskPerTradePct", "Riesgo por operación (%)", "dec") { d(it.riskPerTradePct) },
        FieldSpec("maxTotalOpenRiskPct", "Riesgo total abierto máx. (%)", "dec") { d(it.maxTotalOpenRiskPct) },
        FieldSpec("maxPositions", "Posiciones máximas", "int") { it.maxPositions.toString() },
        FieldSpec("maxNotionalPerPosition", "Monto máx. por posición (USDT)", "dec") { d(it.maxNotionalPerPosition) },
        FieldSpec("feePct", "Comisión por lado (%)", "dec") { d(it.feePct) },
        FieldSpec("slAtr", "Stop loss (x ATR)", "dec") { d(it.slAtr) },
        FieldSpec("tpR", "Objetivo (múltiplo de R)", "dec") { d(it.tpR) },
        FieldSpec("partialTakePct", "Toma parcial (%)", "dec") { d(it.partialTakePct) },
        FieldSpec("trailAtr", "Trailing stop (x ATR)", "dec") { d(it.trailAtr) },
        FieldSpec("rsiMin", "RSI mínimo", "dec") { d(it.rsiMin) },
        FieldSpec("rsiMax", "RSI máximo", "dec") { d(it.rsiMax) },
        FieldSpec("adxMin", "ADX mínimo", "dec") { d(it.adxMin) },
        FieldSpec("refreshSeconds", "Análisis cada (segundos)", "int") { it.refreshSeconds.toString() },
        FieldSpec("priceRefreshSeconds", "Precios cada (segundos)", "int") { it.priceRefreshSeconds.toString() },
        FieldSpec("emaTrendFast", "EMA tendencia rápida (4H)", "int") { it.emaTrendFast.toString() },
        FieldSpec("emaTrendSlow", "EMA tendencia lenta (4H)", "int") { it.emaTrendSlow.toString() },
        FieldSpec("emaPullback", "EMA recuperación (1H)", "int") { it.emaPullback.toString() },
        FieldSpec("rsiPeriod", "Periodo RSI", "int") { it.rsiPeriod.toString() },
        FieldSpec("adxPeriod", "Periodo ADX", "int") { it.adxPeriod.toString() },
        FieldSpec("atrPeriod", "Periodo ATR", "int") { it.atrPeriod.toString() },
        FieldSpec("volumePeriod", "Periodo volumen promedio", "int") { it.volumePeriod.toString() },
        FieldSpec("macdFast", "MACD rápida", "int") { it.macdFast.toString() },
        FieldSpec("macdSlow", "MACD lenta", "int") { it.macdSlow.toString() },
        FieldSpec("macdSignal", "MACD señal", "int") { it.macdSignal.toString() },
        FieldSpec("klinesLimit", "Velas a descargar (250-1000)", "int") { it.klinesLimit.toString() },
    )

    fun toMap(c: BotConfig): LinkedHashMap<String, String> =
        LinkedHashMap<String, String>().also { m -> fields.forEach { m[it.key] = it.get(c) } }

    /** Convierte texto en BotConfig validado. Lanza IllegalArgumentException con un mensaje legible. */
    fun parse(m: Map<String, String>): BotConfig {
        val def = BotConfig()
        fun label(k: String) = fields.first { it.key == k }.label
        fun dec(k: String, dv: Double): Double {
            val raw = m[k]?.trim()
            if (raw.isNullOrEmpty()) return dv
            val v = raw.replace(',', '.').toDoubleOrNull()
            if (v == null || !v.isFinite()) throw IllegalArgumentException("Valor inválido en: ${label(k)}")
            return v
        }
        fun int(k: String, dv: Int): Int {
            val raw = m[k]?.trim()
            if (raw.isNullOrEmpty()) return dv
            return raw.toIntOrNull() ?: throw IllegalArgumentException("Valor inválido en: ${label(k)}")
        }
        val symbols = (m["symbols"] ?: def.symbols.joinToString(","))
            .split(",", " ", ";").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (symbols.isEmpty()) throw IllegalArgumentException("Indica al menos un par (ej. BTCUSDT)")
        symbols.firstOrNull { !Regex("[A-Z0-9]{5,15}").matches(it) }
            ?.let { throw IllegalArgumentException("Par inválido: $it") }

        val c = BotConfig(
            symbols = symbols,
            capitalInitial = dec("capitalInitial", def.capitalInitial),
            riskPerTradePct = dec("riskPerTradePct", def.riskPerTradePct),
            maxTotalOpenRiskPct = dec("maxTotalOpenRiskPct", def.maxTotalOpenRiskPct),
            maxPositions = int("maxPositions", def.maxPositions),
            feePct = dec("feePct", def.feePct),
            slAtr = dec("slAtr", def.slAtr),
            tpR = dec("tpR", def.tpR),
            partialTakePct = dec("partialTakePct", def.partialTakePct),
            trailAtr = dec("trailAtr", def.trailAtr),
            emaTrendFast = int("emaTrendFast", def.emaTrendFast),
            emaTrendSlow = int("emaTrendSlow", def.emaTrendSlow),
            emaPullback = int("emaPullback", def.emaPullback),
            rsiPeriod = int("rsiPeriod", def.rsiPeriod),
            rsiMin = dec("rsiMin", def.rsiMin),
            rsiMax = dec("rsiMax", def.rsiMax),
            adxPeriod = int("adxPeriod", def.adxPeriod),
            adxMin = dec("adxMin", def.adxMin),
            atrPeriod = int("atrPeriod", def.atrPeriod),
            volumePeriod = int("volumePeriod", def.volumePeriod),
            macdFast = int("macdFast", def.macdFast),
            macdSlow = int("macdSlow", def.macdSlow),
            macdSignal = int("macdSignal", def.macdSignal),
            refreshSeconds = int("refreshSeconds", def.refreshSeconds),
            priceRefreshSeconds = int("priceRefreshSeconds", def.priceRefreshSeconds),
            klinesLimit = int("klinesLimit", def.klinesLimit),
            maxNotionalPerPosition = dec("maxNotionalPerPosition", def.maxNotionalPerPosition),
        )
        fun need(ok: Boolean, msg: String) { if (!ok) throw IllegalArgumentException(msg) }
        need(c.capitalInitial >= 50, "El capital inicial debe ser al menos 50 USDT")
        need(c.riskPerTradePct > 0 && c.riskPerTradePct <= 10, "Riesgo por operación: entre 0 y 10 %")
        need(c.maxTotalOpenRiskPct >= c.riskPerTradePct && c.maxTotalOpenRiskPct <= 30, "Riesgo total: mayor o igual al de una operación y máx. 30 %")
        need(c.maxPositions in 1..symbols.size, "Posiciones máximas: entre 1 y el número de pares (${symbols.size})")
        need(c.maxNotionalPerPosition >= 10, "Monto máx. por posición: mínimo 10 USDT")
        need(c.feePct in 0.0..2.0, "Comisión: entre 0 y 2 %")
        need(c.slAtr > 0 && c.tpR > 0 && c.trailAtr > 0, "Stop, objetivo y trailing deben ser mayores que 0")
        need(c.partialTakePct in 1.0..100.0, "Toma parcial: entre 1 y 100 %")
        need(c.rsiMin < c.rsiMax, "RSI mínimo debe ser menor que el máximo")
        need(c.refreshSeconds >= 10 && c.priceRefreshSeconds >= 5, "Análisis ≥ 10 s y precios ≥ 5 s (evita bloqueos de Binance)")
        need(listOf(c.emaTrendFast, c.emaTrendSlow, c.emaPullback, c.rsiPeriod, c.adxPeriod, c.atrPeriod,
            c.volumePeriod, c.macdFast, c.macdSlow, c.macdSignal).all { it >= 2 }, "Los periodos deben ser 2 o más")
        need(c.macdFast < c.macdSlow, "MACD rápida debe ser menor que la lenta")
        need(c.emaTrendFast < c.emaTrendSlow, "EMA rápida (4H) debe ser menor que la lenta")
        need(c.klinesLimit in 250..1000, "Velas a descargar: entre 250 y 1000")
        need(c.emaTrendSlow + 25 <= c.klinesLimit, "Con esa EMA lenta necesitas más velas a descargar")
        return c
    }
}
