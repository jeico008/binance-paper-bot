package com.carlos.paperbot

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Guarda/lee la configuración en el móvil. */
object ConfigStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load(ctx: Context): BotConfig = try {
        val raw = prefs(ctx).getString("json", null)
        if (raw == null) BotConfig() else {
            val o = JSONObject(raw)
            ConfigSpec.parse(o.keys().asSequence().associateWith { o.getString(it) })
        }
    } catch (e: Exception) { BotConfig() }

    fun save(ctx: Context, c: BotConfig) {
        val o = JSONObject()
        ConfigSpec.toMap(c).forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString("json", o.toString()).apply()
    }

    fun reset(ctx: Context) { prefs(ctx).edit().remove("json").apply() }
}

class SettingsActivity : Activity() {
    private val inputs = LinkedHashMap<String, EditText>()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        root.addView(TextView(this).apply { text = "Configuración"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = if (BotRuntime.running) "El bot está activo: los cambios se aplican al detenerlo e iniciarlo de nuevo."
            else "Los cambios se aplican la próxima vez que inicies el bot."
            textSize = 12f; setPadding(0, 0, 0, dp(8))
        })
        fillForm(root, ConfigStore.load(this))
        root.addView(Button(this).apply { text = "GUARDAR"; setOnClickListener { save() } })
        root.addView(Button(this).apply {
            text = "Restaurar valores originales"
            setOnClickListener {
                ConfigStore.reset(this@SettingsActivity)
                ConfigSpec.toMap(BotConfig()).forEach { (k, v) -> inputs[k]?.setText(v) }
                Toast.makeText(this@SettingsActivity, "Valores originales restaurados y guardados.", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun fillForm(root: LinearLayout, c: BotConfig) {
        for (f in ConfigSpec.fields) {
            root.addView(TextView(this).apply { text = f.label; textSize = 13f; setPadding(0, dp(10), 0, 0) })
            val et = EditText(this).apply {
                setText(f.get(c)); setSingleLine(true)
                inputType = when (f.kind) {
                    "int" -> InputType.TYPE_CLASS_NUMBER
                    "dec" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
            }
            inputs[f.key] = et
            root.addView(et)
        }
    }

    private fun save() {
        try {
            val cfg = ConfigSpec.parse(inputs.mapValues { it.value.text.toString() })
            val old = ConfigStore.load(this)
            ConfigStore.save(this, cfg)
            val msg = if (cfg.capitalInitial != old.capitalInitial)
                "Guardado. Cambiaste el capital: al iniciar, la cuenta simulada empezará de cero."
            else "Guardado."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }
}
