package com.carlos.paperbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado compartido con la pantalla (mismo proceso). */
object BotRuntime {
    @Volatile var running = false
    @Volatile var status = "Detenido"
    @Volatile var summary = ""
    val log = ArrayDeque<String>()

    @Synchronized fun addLog(line: String) {
        log.addFirst(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + line)
        while (log.size > 150) log.removeLast()
    }
    @Synchronized fun logText(): String = log.joinToString("\n")
}

class BotService : Service() {
    companion object {
        const val CH_BOT = "bot_status"
        const val CH_ALERTS = "bot_alerts"
        const val ACTION_STOP = "com.carlos.paperbot.STOP"
        private const val ID_FG = 1
        private var alertId = 100

        fun prefs(ctx: Context) = ctx.getSharedPreferences("bot", Context.MODE_PRIVATE)

        fun start(ctx: Context) {
            prefs(ctx).edit().putBoolean("enabled", true).apply()
            val i = Intent(ctx, BotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            prefs(ctx).edit().putBoolean("enabled", false).apply()
            ctx.stopService(Intent(ctx, BotService::class.java))
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_BOT, "Estado del bot", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alertas de trading", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            })
        }

        fun alert(ctx: Context, title: String, text: String) {
            ensureChannels(ctx)
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CH_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(alertId++, n)
        }
    }

    private val cfg = BotConfig()
    private lateinit var account: PaperAccount
    private var worker: Thread? = null
    @Volatile private var alive = false
    private var wake: PowerManager.WakeLock? = null
    private val lastPrices = HashMap<String, Double>()
    private val lastErrorAlert = HashMap<String, Long>()
    private var lastScan = 0L
    private var lastScanLabel = "—"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        ensureChannels(this)
        val n = foregroundNotification("Iniciando…")
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_FG, n)

        if (!alive) {
            alive = true
            BotRuntime.running = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paperbot:loop").apply { acquire() }
            account = PaperAccount(cfg)
            Storage.load(this, account)
            account.onEvent = { ev, sym, price, detail -> handleEvent(ev, sym, price, detail) }
            account.onTradeClosed = { t -> Storage.logTrade(this, t) }
            worker = Thread({ loop() }, "paperbot-loop").also { it.start() }
            BotRuntime.addLog("Servicio iniciado")
        }
        return START_STICKY
    }

    private fun handleEvent(ev: String, sym: String, price: Double, detail: String) {
        Storage.logEvent(this, ev, sym, price, detail)
        BotRuntime.addLog("$ev $sym @ ${"%.6g".format(price)}")
        if (ev in setOf("ENTRADA", "SEÑAL", "TAKE PROFIT PARCIAL", "STOP / TRAILING", "CIERRE_OPERACION"))
            alert(this, "$ev · $sym", "Precio: ${"%.6g".format(price)}\n$detail")
    }

    private fun loop() {
        while (alive) {
            val now = System.currentTimeMillis()
            try {
                if (now - lastScan >= cfg.refreshSeconds * 1000L) {
                    scan()
                    lastScan = System.currentTimeMillis()
                }
                refreshPrices()
            } catch (e: Exception) {
                BotRuntime.status = "Error: ${e.message}"
            }
            updateSummary()
            try { Thread.sleep(cfg.priceRefreshSeconds * 1000L) } catch (_: InterruptedException) { break }
        }
    }

    private fun scan() {
        val errors = mutableListOf<String>()
        val nowMs = System.currentTimeMillis()
        for (sym in cfg.symbols) {
            try {
                val d1 = Binance.klines(sym, "1h", cfg.klinesLimit)
                val d4 = Binance.klines(sym, "4h", cfg.klinesLimit)
                val res = Strategy.latestSignal(d1, d4, cfg, nowMs)
                lastPrices[sym] = res.price
                account.applyScan(sym, res, lastPrices)
            } catch (e: Exception) {
                errors += "$sym: ${e.message}"
                val last = lastErrorAlert[sym] ?: 0L
                if (nowMs - last > 15 * 60_000L) { // máx. un aviso de error cada 15 min por par
                    lastErrorAlert[sym] = nowMs
                    alert(this, "Error de conexión · $sym", (e.message ?: "").take(300))
                }
            }
        }
        Storage.save(this, account)
        lastScanLabel = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        BotRuntime.status = if (errors.isEmpty()) "Conectado · último análisis $lastScanLabel"
        else "Avisos $lastScanLabel: " + errors.joinToString(" | ").take(200)
    }

    private fun refreshPrices() {
        for (sym in cfg.symbols) {
            try { lastPrices[sym] = Binance.price(sym) } catch (_: Exception) { }
        }
    }

    private fun updateSummary() {
        val eq = account.equity(lastPrices)
        val s = "Equity %.2f USDT · P/L %+.2f · Pos %d/%d · Op %d (G%d/P%d)".format(
            eq, eq - cfg.capitalInitial, account.positions.size, cfg.maxPositions,
            account.totalTrades, account.wins, account.losses)
        BotRuntime.summary = s + "\n" + cfg.symbols.joinToString("  ") { "${it.removeSuffix("USDT")} ${"%.6g".format(lastPrices[it] ?: 0.0)}" }
        getSystemService(NotificationManager::class.java).notify(ID_FG, foregroundNotification(s))
    }

    private fun foregroundNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_BOT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Paper Bot activo").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Detener", stop).build())
            .build()
    }

    override fun onDestroy() {
        alive = false
        worker?.interrupt()
        BotRuntime.running = false
        BotRuntime.status = "Detenido"
        try { if (::account.isInitialized) Storage.save(this, account) } catch (_: Exception) { }
        wake?.let { if (it.isHeld) it.release() }
        BotRuntime.addLog("Servicio detenido")
        super.onDestroy()
    }
}
