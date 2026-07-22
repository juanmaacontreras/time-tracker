package com.bitacora.timer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var stats: TextView
    private lateinit var bannerLabel: TextView
    private lateinit var bannerName: TextView
    private lateinit var bannerChrono: Chronometer
    private lateinit var syncStatus: TextView

    private val todayViews = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateRunningRow()
            handler.postDelayed(this, 1000)
        }
    }

    // Sincroniza sola cada 10s mientras la app esta abierta (sin tocar boton).
    private val syncLoop = object : Runnable {
        override fun run() {
            doSync(false)
            handler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        stats = findViewById(R.id.stats)
        bannerLabel = findViewById(R.id.bannerLabel)
        bannerName = findViewById(R.id.bannerName)
        bannerChrono = findViewById(R.id.bannerChrono)
        syncStatus = findViewById(R.id.syncStatus)
        findViewById<Button>(R.id.btnNew).setOnClickListener { newActivityDialog() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }

        requestNotifPermission()
        scheduleBackgroundSync()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
    }

    private fun scheduleBackgroundSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("bitacora-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(ticker)
        doSync(false)
        handler.postDelayed(syncLoop, 10000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(syncLoop)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        todayViews.clear()
        list.removeAllViews()
        val runId = Store.runningActId(this)
        for (act in Store.activities(this)) list.addView(buildRow(act, runId))
        renderBanner(runId)
        renderStats()
        TimerWidget.refresh(this)
        Notifs.update(this)
    }

    private fun renderBanner(runId: String) {
        if (runId.isNotEmpty()) {
            val a = Store.activityById(this, runId)
            bannerLabel.text = "GRABANDO"
            bannerName.text = a?.optString("name") ?: "—"
            val elapsed = Store.now() - Store.runningStart(this)
            bannerChrono.base = SystemClock.elapsedRealtime() - elapsed
            bannerChrono.start()
        } else {
            bannerLabel.text = "EN REPOSO"
            bannerName.text = "Nada corriendo"
            bannerChrono.stop()
            bannerChrono.base = SystemClock.elapsedRealtime()
        }
    }

    private fun card(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(Color.parseColor("#FDFEFF"))
        d.cornerRadius = dp(13).toFloat()
        d.setStroke(dp(1), Color.parseColor("#D3DBE8"))
        return d
    }

    private fun buildRow(act: JSONObject, runId: String): View {
        val id = act.getString("id")
        val running = id == runId

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(12), dp(12))
        val bg = card()
        if (running) bg.setStroke(dp(2), Color.parseColor("#E1591F"))
        row.background = bg
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        row.layoutParams = lp

        val dot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(Color.parseColor(act.optString("color", "#2F4B8F")))
        dot.background = dotBg
        val dlp = LinearLayout.LayoutParams(dp(12), dp(12))
        dlp.rightMargin = dp(12)
        dot.layoutParams = dlp
        row.addView(dot)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val type = TextView(this)
        type.text = act.optString("type", "").uppercase()
        type.setTextColor(Color.parseColor("#8592AB"))
        type.textSize = 10f
        val name = TextView(this)
        name.text = act.getString("name")
        name.setTextColor(Color.parseColor("#182742"))
        name.textSize = 16f
        name.setTypeface(name.typeface, Typeface.BOLD)
        mid.addView(type)
        mid.addView(name)
        row.addView(mid)

        val today = TextView(this)
        today.text = Store.human(Store.secsFor(this, id, Store.startOfToday()))
        today.setTextColor(if (running) Color.parseColor("#E1591F") else Color.parseColor("#182742"))
        today.textSize = 14f
        today.typeface = Typeface.MONOSPACE
        today.setPadding(0, 0, dp(12), 0)
        row.addView(today)
        todayViews[id] = today

        val glyph = TextView(this)
        glyph.text = if (running) "\u25A0" else "\u25B6"
        glyph.setTextColor(if (running) Color.parseColor("#E1591F") else Color.parseColor("#2F4B8F"))
        glyph.textSize = 18f
        row.addView(glyph)

        row.setOnClickListener {
            Store.toggle(this, id)
            render()
            doSync(false)
        }
        row.setOnLongClickListener {
            confirmDelete(act)
            true
        }
        return row
    }

    private fun updateRunningRow() {
        val runId = Store.runningActId(this)
        if (runId.isEmpty()) return
        todayViews[runId]?.text = Store.human(Store.secsFor(this, runId, Store.startOfToday()))
    }

    private fun renderStats() {
        val sb = StringBuilder()
        val today = Store.startOfToday()
        val week = Store.startOfWeek()
        val acts = Store.activities(this)
        var todayTotal = 0L
        for (a in acts) todayTotal += Store.secsFor(this, a.getString("id"), today)
        sb.append("HOY   ").append(Store.human(todayTotal)).append("\n\n")
        sb.append("ESTA SEMANA\n")
        val rows = acts.map { Pair(it, Store.secsFor(this, it.getString("id"), week)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        if (rows.isEmpty()) sb.append("  (sin registros)")
        for (r in rows) {
            val nm = r.first.getString("name")
            sb.append("  ").append(nm.padEnd(16).take(16)).append("  ")
                .append(Store.human(r.second)).append("\n")
        }
        stats.text = sb.toString()
    }

    private fun newActivityDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val nameIn = EditText(this)
        nameIn.hint = "Nombre (ej. Análisis de circuitos)"
        nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val typeIn = EditText(this)
        typeIn.hint = "Tipo (Materia, Libro, Proyecto…)"
        typeIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        box.addView(nameIn)
        box.addView(typeIn)
        AlertDialog.Builder(this)
            .setTitle("Nueva actividad")
            .setView(box)
            .setPositiveButton("Crear") { _, _ ->
                val name = nameIn.text.toString().trim()
                if (name.isNotEmpty()) {
                    val type = typeIn.text.toString().trim().ifEmpty { "General" }
                    val color = Store.COLORS[Store.activities(this).size % Store.COLORS.size]
                    Store.addActivity(this, name, type, color)
                    render()
                    doSync(false)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(act: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("Borrar \"${act.getString("name")}\"")
            .setMessage("También se borran sus sesiones. ¿Seguro?")
            .setPositiveButton("Borrar") { _, _ ->
                Store.deleteActivity(this, act.getString("id"))
                render()
                doSync(false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doSync(showToast: Boolean) {
        if (!Config.syncEnabled()) {
            syncStatus.text = "Sync desactivada · completá Config.kt para sincronizar entre dispositivos."
            return
        }
        if (!showToast) syncStatus.text = "Sincronizando…"
        Thread {
            val ok = Sync.syncNow(this)
            runOnUiThread {
                syncStatus.text = if (ok) "Sincronizado \u2713" else "Sin conexión con el servidor."
                if (ok) render()
                if (showToast) {
                    Toast.makeText(this, if (ok) "Listo" else "Falló la sync", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun exportCsv() {
        try {
            val csv = Store.exportCsv(this)
            val file = File(cacheDir, "bitacora.csv")
            file.writeText("\uFEFF$csv")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/csv"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Exportar CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo exportar", Toast.LENGTH_SHORT).show()
        }
    }
}