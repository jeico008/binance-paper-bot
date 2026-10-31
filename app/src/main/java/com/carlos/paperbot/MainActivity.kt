package com.carlos.paperbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button

    private val tick = object : Runnable {
        override fun run() {
            val on = BotRuntime.running
            btnToggle.text = if (on) "DETENER BOT" else "INICIAR BOT"
            tvStatus.text = (if (on) "● ACTIVO — " else "● DETENIDO — ") + BotRuntime.status
            tvSummary.text = BotRuntime.summary.ifEmpty { "Capital simulado inicial: ${BotConfig().capitalInitial} USDT" }
            tvLog.text = BotRuntime.logText().ifEmpty { "Sin eventos todavía." }
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "Paper Bot Binance"; textSize = 24f; setTypeface(null, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Solo simulación · sin API keys · sin órdenes reales"; textSize = 12f })
        tvStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(4)) }
        tvSummary = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        root.addView(tvStatus); root.addView(tvSummary)

        btnToggle = Button(this).apply {
            setOnClickListener {
                if (BotRuntime.running) BotService.stop(this@MainActivity) else {
                    askNotifPermission()
                    BotService.start(this@MainActivity)
                }
            }
        }
        root.addView(btnToggle)
        root.addView(Button(this).apply {
            text = "Permitir uso de batería sin restricciones"
            setOnClickListener { requestBatteryExemption() }
        })
        root.addView(Button(this).apply {
            text = "Probar notificación"
            setOnClickListener {
                askNotifPermission()
                BotService.alert(this@MainActivity, "Prueba", "Si ves esto, las alertas funcionan.")
            }
        })
        root.addView(Button(this).apply {
            text = "Reiniciar cuenta simulada"
            setOnClickListener {
                if (BotRuntime.running) Toast.makeText(this@MainActivity, "Detén el bot primero.", Toast.LENGTH_SHORT).show()
                else { Storage.reset(this@MainActivity); Toast.makeText(this@MainActivity, "Cuenta reiniciada.", Toast.LENGTH_SHORT).show() }
            }
        })
        root.addView(TextView(this).apply {
            text = "CSV en: " + Storage.filesPath(this@MainActivity); textSize = 11f; setPadding(0, dp(8), 0, dp(8))
        })
        tvLog = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; gravity = Gravity.START }
        root.addView(tvLog)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Ya está sin restricciones.", Toast.LENGTH_SHORT).show(); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
}
