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
import android.text.TextUtils
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
    private lateinit var timerView: LinearLayout
    private lateinit var resumenView: LinearLayout
    private lateinit var bannerLabel: TextView
    private lateinit var bannerName: TextView
    private lateinit var bannerChrono: Chronometer
    private lateinit var btnEdit: Button
    private lateinit var tabTimer: TextView
    private lateinit var tabResumen: TextView

    private var editMode = false
    private var tab = "timer"
    private var statsPeriod = "day"
    private var statsCategory = "all"

    private val todayViews = HashMap<String, TextView>()
    private val expandedIds = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateRunningRow()
            handler.postDelayed(this, 1000)
        }
    }
    private val syncLoop = object : Runnable {
        override fun run() {
            doSync()
            handler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        timerView = findViewById(R.id.timerView)
        resumenView = findViewById(R.id.resumenView)
        bannerLabel = findViewById(R.id.bannerLabel)
        bannerName = findViewById(R.id.bannerName)
        bannerChrono = findViewById(R.id.bannerChrono)
        btnEdit = findViewById(R.id.btnEdit)
        tabTimer = findViewById(R.id.tabTimer)
        tabResumen = findViewById(R.id.tabResumen)

        btnEdit.setOnClickListener {
            editMode = !editMode
            btnEdit.text = if (editMode) "✓ Listo" else "✎ Editar"
            render()
        }
        tabTimer.setOnClickListener { switchTab("timer") }
        tabResumen.setOnClickListener { switchTab("resumen") }

        requestNotifPermission()
        scheduleBackgroundSync()
        switchTab("timer")
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
        doSync()
        handler.postDelayed(syncLoop, 10000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(syncLoop)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun switchTab(t: String) {
        tab = t
        if (t == "resumen" && editMode) { editMode = false; btnEdit.text = "✎ Editar" }
        timerView.visibility = if (t == "timer") View.VISIBLE else View.GONE
        resumenView.visibility = if (t == "resumen") View.VISIBLE else View.GONE
        btnEdit.visibility = if (t == "timer") View.VISIBLE else View.GONE
        styleTabs()
        render()
    }

    private fun styleTabs() {
        fun paint(tv: TextView, active: Boolean) {
            if (active) {
                val d = GradientDrawable()
                d.cornerRadius = dp(8).toFloat()
                d.setColor(Color.parseColor("#182742"))
                tv.background = d
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = null
                tv.setTextColor(Color.parseColor("#4A5A78"))
            }
        }
        paint(tabTimer, tab == "timer")
        paint(tabResumen, tab == "resumen")
    }

    private fun render() {
        if (tab == "timer") renderTimer() else renderResumen()
        TimerWidget.refresh(this)
        ResumenWidget.refresh(this)
        Notifs.update(this)
    }

    // ---------------- TIMER TAB ----------------
    private fun renderTimer() {
        todayViews.clear()
        list.removeAllViews()
        val runId = Store.runningActId(this)
        val acts = Store.activities(this)
        for (act in acts) list.addView(buildRow(act, runId))
        if (acts.isEmpty()) {
            val hint = TextView(this)
            hint.text = "Todavía no tenés actividades.\nCreá la primera acá abajo 👇"
            hint.setTextColor(Color.parseColor("#8592AB"))
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, dp(10), 0, dp(14))
            list.addView(hint)
            list.addView(buildAddCard())
        } else if (editMode) {
            list.addView(buildAddCard())
        }
        renderBanner(runId)
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
        val expanded = id in expandedIds

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val clp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        clp.bottomMargin = dp(8)
        container.layoutParams = clp

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(12), dp(12))
        val bg = card()
        if (running && !editMode) bg.setStroke(dp(2), Color.parseColor("#E1591F"))
        if (editMode) bg.setStroke(dp(1), Color.parseColor("#2F4B8F"))
        row.background = bg

        if (!editMode) {
            val arrow = TextView(this)
            arrow.text = if (expanded) "▾" else "▸"
            arrow.setTextColor(Color.parseColor("#8592AB"))
            arrow.textSize = 16f
            arrow.setPadding(0, 0, dp(10), 0)
            arrow.setOnClickListener {
                if (expanded) expandedIds.remove(id) else expandedIds.add(id)
                renderTimer()
            }
            row.addView(arrow)
        }

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

        if (!editMode) {
            val today = TextView(this)
            today.text = Store.exact(Store.secsFor(this, id, Store.startOfToday()))
            today.setTextColor(if (running) Color.parseColor("#E1591F") else Color.parseColor("#182742"))
            today.textSize = 14f
            today.typeface = Typeface.MONOSPACE
            today.setPadding(0, 0, dp(12), 0)
            row.addView(today)
            todayViews[id] = today
        }

        val glyph = TextView(this)
        glyph.text = if (editMode) "✎" else if (running) "\u25A0" else "\u25B6"
        glyph.setTextColor(if (running && !editMode) Color.parseColor("#E1591F") else Color.parseColor("#2F4B8F"))
        glyph.textSize = 18f
        row.addView(glyph)

        row.setOnClickListener {
            if (editMode) {
                openActivitySheet(act)
            } else {
                Store.toggle(this, id)
                render()
                doSync()
            }
        }
        container.addView(row)
        if (expanded && !editMode) container.addView(buildSessionsPanel(act))
        return container
    }

    private fun buildSessionsPanel(act: JSONObject): View {
        val id = act.getString("id")
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(10), dp(14), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F3F6FB"))
        bg.cornerRadius = dp(10).toFloat()
        panel.background = bg
        val plp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        plp.topMargin = dp(4)
        panel.layoutParams = plp

        val sessions = Store.sessionsForActivityToday(this, id)
        val title = TextView(this)
        title.text = "ENTRADAS DE HOY"
        title.setTextColor(Color.parseColor("#8592AB"))
        title.textSize = 10f
        title.letterSpacing = 0.1f
        panel.addView(title)

        if (sessions.isEmpty()) {
            val e = TextView(this)
            e.text = "Sin entradas cerradas todavia."
            e.setTextColor(Color.parseColor("#8592AB"))
            e.textSize = 12f
            e.setPadding(0, dp(8), 0, 0)
            panel.addView(e)
            return panel
        }

        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        for (s in sessions) {
            val srow = LinearLayout(this)
            srow.orientation = LinearLayout.HORIZONTAL
            srow.gravity = Gravity.CENTER_VERTICAL
            srow.setPadding(0, dp(8), 0, dp(8))

            val label = TextView(this)
            val secs = (s.getLong("end") - s.getLong("start")) / 1000
            label.text = fmt.format(s.getLong("start")) + " - " + fmt.format(s.getLong("end")) + "   " + Store.exact(secs)
            label.textSize = 13f
            label.setTextColor(Color.parseColor("#182742"))
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            srow.addView(label)

            val del = TextView(this)
            del.text = "Borrar"
            del.setTextColor(Color.parseColor("#B5432E"))
            del.textSize = 12f
            del.setTypeface(del.typeface, Typeface.BOLD)
            del.setPadding(dp(10), 0, dp(4), 0)
            del.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Borrar entrada")
                    .setMessage("Se borra el registro de " + fmt.format(s.getLong("start")) + " a " + fmt.format(s.getLong("end")) + ". Seguro?")
                    .setPositiveButton("Borrar") { _, _ ->
                        Store.deleteSession(this, s.getString("id"))
                        renderTimer()
                        doSync()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            srow.addView(del)
            panel.addView(srow)
        }
        return panel
    }

    private fun buildAddCard(): View {
        val add = TextView(this)
        add.text = "+  Nueva actividad"
        add.gravity = Gravity.CENTER
        add.setTextColor(Color.parseColor("#2F4B8F"))
        add.textSize = 14f
        add.setTypeface(add.typeface, Typeface.BOLD)
        add.setPadding(dp(14), dp(18), dp(14), dp(18))
        val d = GradientDrawable()
        d.cornerRadius = dp(13).toFloat()
        d.setStroke(dp(2), Color.parseColor("#C7D2E3"))
        add.background = d
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4)
        add.layoutParams = lp
        add.setOnClickListener { openActivitySheet(null) }
        return add
    }

    private fun updateRunningRow() {
        if (tab != "timer" || editMode) return
        val runId = Store.runningActId(this)
        if (runId.isEmpty()) return
        todayViews[runId]?.text = Store.exact(Store.secsFor(this, runId, Store.startOfToday()))
    }

    // ---------------- RESUMEN TAB ----------------
    private fun labelFor(p: String) = when (p) {
        "day" -> "Hoy"
        "week" -> "Esta semana"
        else -> "Este mes"
    }

    private fun renderResumen() {
        resumenView.removeAllViews()
        resumenView.addView(buildPeriodSelector())

        val from = Store.periodStart(statsPeriod)
        val allActs = Store.activities(this)

        val allCats = allActs.map { it.optString("type", "General").ifEmpty { "General" } }
            .distinct().sorted()
        if (allCats.size > 1) resumenView.addView(buildCategorySelector(allCats))
        if (statsCategory != "all" && statsCategory !in allCats) statsCategory = "all"

        val acts = if (statsCategory == "all") allActs
            else allActs.filter { it.optString("type", "General").ifEmpty { "General" } == statsCategory }

        val perAct = acts.map { it to Store.secsFor(this, it.getString("id"), from) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val total = perAct.sumOf { it.second }

        val totalLabel = if (statsCategory == "all") labelFor(statsPeriod).uppercase()
            else "${labelFor(statsPeriod).uppercase()} · ${statsCategory.uppercase()}"
        resumenView.addView(sectionLabel("TOTAL · $totalLabel"))
        val big = TextView(this)
        big.text = Store.exact(total)
        big.textSize = 30f
        big.typeface = Typeface.MONOSPACE
        big.setTextColor(Color.parseColor("#182742"))
        resumenView.addView(big)

        if (perAct.isEmpty()) {
            val e = TextView(this)
            e.text = "Sin registros en este período."
            e.setTextColor(Color.parseColor("#8592AB"))
            e.setPadding(0, dp(24), 0, 0)
            resumenView.addView(e)
            return
        }

        val actIds = acts.map { it.getString("id") }.toSet()
        if (statsPeriod == "day") {
            resumenView.addView(sectionLabel("POR ACTIVIDAD"))
            val bars = perAct.take(8).map {
                Triple(it.first.getString("name"), it.second, Color.parseColor(it.first.optString("color", "#2F4B8F")))
            }
            resumenView.addView(buildBarChart(bars, true, 1))
        } else {
            resumenView.addView(sectionLabel("POR DÍA"))
            val bars = if (statsPeriod == "week") weekBars(actIds) else monthBars(actIds)
            resumenView.addView(buildBarChart(bars, statsPeriod == "week", if (statsPeriod == "week") 1 else 5))
        }
        for ((a, sec) in perAct) {
            resumenView.addView(statRow(a.getString("name"), Color.parseColor(a.optString("color", "#2F4B8F")), sec, total))
        }

        if (statsCategory == "all") {
            val cats = LinkedHashMap<String, Long>()
            for (a in allActs) {
                val t = Store.secsFor(this, a.getString("id"), from)
                if (t > 0) {
                    val c = a.optString("type", "General").ifEmpty { "General" }
                    cats[c] = (cats[c] ?: 0L) + t
                }
            }
            if (cats.isNotEmpty()) {
                resumenView.addView(sectionLabel("POR CATEGORÍA"))
                for ((c, sec) in cats.entries.sortedByDescending { it.value }) {
                    val row = statRow(c, Color.parseColor("#2F4B8F"), sec, total)
                    row.setOnClickListener { statsCategory = c; renderResumen() }
                    resumenView.addView(row)
                }
            }
        }

        resumenView.addView(buildCsvButton())
    }

    private fun buildCategorySelector(cats: List<String>): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.setPadding(0, dp(10), 0, 0)

        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun chip(label: String, value: String): View {
            val c = TextView(this)
            c.text = label
            c.textSize = 12f
            c.setPadding(dp(12), dp(7), dp(12), dp(7))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6)
            c.layoutParams = lp
            val d = GradientDrawable()
            d.cornerRadius = dp(16).toFloat()
            if (value == statsCategory) {
                d.setColor(Color.parseColor("#182742"))
                c.setTextColor(Color.WHITE)
            } else {
                d.setColor(Color.parseColor("#FDFEFF"))
                d.setStroke(dp(1), Color.parseColor("#D3DBE8"))
                c.setTextColor(Color.parseColor("#4A5A78"))
            }
            c.background = d
            c.setOnClickListener { statsCategory = value; renderResumen() }
            return c
        }

        row.addView(chip("Todo", "all"))
        for (c in cats) row.addView(chip(c, c))
        scroll.addView(row)
        wrap.addView(scroll)
        return wrap
    }

    private fun sectionLabel(text: String): View {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor("#8592AB"))
        t.textSize = 10f
        t.letterSpacing = 0.14f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(22)
        lp.bottomMargin = dp(8)
        t.layoutParams = lp
        return t
    }

    private fun buildPeriodSelector(): View {
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.setPadding(dp(3), dp(3), dp(3), dp(3))
        val segBg = GradientDrawable()
        segBg.cornerRadius = dp(10).toFloat()
        segBg.setColor(Color.parseColor("#FDFEFF"))
        segBg.setStroke(dp(1), Color.parseColor("#D3DBE8"))
        seg.background = segBg
        val periods = listOf("day" to "Día", "week" to "Semana", "month" to "Mes")
        for ((p, lbl) in periods) {
            val b = TextView(this)
            b.text = lbl
            b.gravity = Gravity.CENTER
            b.textSize = 13f
            b.setPadding(dp(6), dp(9), dp(6), dp(9))
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (p == statsPeriod) {
                val d = GradientDrawable()
                d.cornerRadius = dp(8).toFloat()
                d.setColor(Color.parseColor("#182742"))
                b.background = d
                b.setTextColor(Color.WHITE)
            } else {
                b.setTextColor(Color.parseColor("#4A5A78"))
            }
            b.setOnClickListener { statsPeriod = p; renderResumen() }
            seg.addView(b)
        }
        return seg
    }

    private val DAY_MS = 86400000L

    private fun weekBars(actIds: Set<String>): List<Triple<String, Long, Int>> {
        val ws = Store.startOfWeek()
        val labels = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
        val c = Color.parseColor("#2F4B8F")
        return (0..6).map { i ->
            val ds = ws + i * DAY_MS
            Triple(labels[i], Store.totalBetween(this, ds, ds + DAY_MS, actIds), c)
        }
    }

    private fun monthBars(actIds: Set<String>): List<Triple<String, Long, Int>> {
        val ms = Store.startOfMonth()
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        val c = Color.parseColor("#2F4B8F")
        return (0 until today).map { i ->
            val ds = ms + i * DAY_MS
            Triple("${i + 1}", Store.totalBetween(this, ds, ds + DAY_MS, actIds), c)
        }
    }

    private fun buildBarChart(bars: List<Triple<String, Long, Int>>, showValues: Boolean, labelStep: Int): View {
        val chartH = dp(150)
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.gravity = Gravity.BOTTOM
        val wlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wlp.topMargin = dp(6); wlp.bottomMargin = dp(4)
        wrap.layoutParams = wlp

        val max = (bars.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
        for ((idx, b) in bars.withIndex()) {
            val label = b.first
            val secs = b.second
            val colorInt = b.third

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            col.setPadding(dp(2), 0, dp(2), 0)
            col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            if (showValues) {
                val v = TextView(this)
                v.text = if (secs > 0) Store.exact(secs) else ""
                v.textSize = 9f
                v.setTextColor(Color.parseColor("#4A5A78"))
                v.gravity = Gravity.CENTER
                v.maxLines = 1
                col.addView(v)
            }

            val barH = Math.max(dp(3), (secs.toDouble() / max * chartH).toInt())
            val bar = View(this)
            val barBg = GradientDrawable()
            barBg.cornerRadius = dp(4).toFloat()
            barBg.setColor(colorInt)
            bar.background = barBg
            val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barH)
            blp.topMargin = dp(3)
            blp.leftMargin = dp(1); blp.rightMargin = dp(1)
            bar.layoutParams = blp
            col.addView(bar)

            val n = TextView(this)
            n.text = if (idx % labelStep == 0) label else ""
            n.textSize = 9f
            n.setTextColor(Color.parseColor("#8592AB"))
            n.gravity = Gravity.CENTER
            n.maxLines = 1
            n.ellipsize = TextUtils.TruncateAt.END
            n.setPadding(0, dp(4), 0, 0)
            col.addView(n)

            wrap.addView(col)
        }
        return wrap
    }

    private fun statRow(name: String, colorInt: Int, sec: Long, total: Long): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        box.background = card()
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        box.layoutParams = lp

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val dot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(colorInt)
        dot.background = dotBg
        val dlp = LinearLayout.LayoutParams(dp(10), dp(10)); dlp.rightMargin = dp(10)
        dot.layoutParams = dlp
        top.addView(dot)

        val nm = TextView(this)
        nm.text = name
        nm.textSize = 14f
        nm.setTextColor(Color.parseColor("#182742"))
        nm.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(nm)

        val vl = TextView(this)
        val pct = if (total > 0) Math.round(sec * 100.0 / total).toInt() else 0
        vl.text = "${Store.exact(sec)}  ·  $pct%"
        vl.textSize = 13f
        vl.typeface = Typeface.MONOSPACE
        vl.setTextColor(Color.parseColor("#4A5A78"))
        top.addView(vl)
        box.addView(top)

        val track = LinearLayout(this)
        track.orientation = LinearLayout.HORIZONTAL
        val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        tlp.topMargin = dp(9)
        track.layoutParams = tlp
        val trackBg = GradientDrawable()
        trackBg.cornerRadius = dp(5).toFloat()
        trackBg.setColor(Color.parseColor("#E1E7F0"))
        track.background = trackBg

        val fill = View(this)
        val fillBg = GradientDrawable()
        fillBg.cornerRadius = dp(5).toFloat()
        fillBg.setColor(colorInt)
        fill.background = fillBg
        val rest = View(this)

        val safeTotal = total.coerceAtLeast(1L)
        fill.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, sec.toFloat())
        rest.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (safeTotal - sec).coerceAtLeast(0L).toFloat())
        track.weightSum = safeTotal.toFloat()
        track.addView(fill)
        track.addView(rest)
        box.addView(track)

        return box
    }

    private fun buildCsvButton(): View {
        val b = Button(this)
        b.text = "Exportar CSV"
        b.textSize = 13f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(20)
        b.layoutParams = lp
        b.setOnClickListener { exportCsv() }
        return b
    }

    // ---------------- edit / create ----------------
    private fun openActivitySheet(existing: JSONObject?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val nameIn = EditText(this)
        nameIn.hint = "Nombre (ej. Análisis de circuitos)"
        nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        nameIn.setText(existing?.optString("name") ?: "")

        val typeIn = EditText(this)
        typeIn.hint = "Categoría (Materia, Libro, Proyecto…)"
        typeIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        typeIn.setText(existing?.optString("type") ?: "")

        box.addView(nameIn)
        box.addView(typeIn)

        val colorLabel = TextView(this)
        colorLabel.text = "Color"
        colorLabel.setTextColor(Color.parseColor("#8592AB"))
        colorLabel.textSize = 12f
        colorLabel.setPadding(0, dp(14), 0, dp(6))
        box.addView(colorLabel)

        var picked = existing?.optString("color")
            ?: Store.COLORS[Store.activities(this).size % Store.COLORS.size]
        val colorRow = LinearLayout(this)
        colorRow.orientation = LinearLayout.HORIZONTAL
        val swatches = ArrayList<View>()
        fun style(v: View, c: String) {
            val d = GradientDrawable()
            d.cornerRadius = dp(8).toFloat()
            d.setColor(Color.parseColor(c))
            if (c == picked) d.setStroke(dp(3), Color.parseColor("#182742"))
            v.background = d
        }
        for (c in Store.COLORS) {
            val sw = View(this)
            val slp = LinearLayout.LayoutParams(dp(34), dp(34)); slp.rightMargin = dp(8)
            sw.layoutParams = slp
            style(sw, c)
            sw.setOnClickListener {
                picked = c
                for ((i, s) in swatches.withIndex()) style(s, Store.COLORS[i])
            }
            swatches.add(sw)
            colorRow.addView(sw)
        }
        box.addView(colorRow)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nueva actividad" else "Editar actividad")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameIn.text.toString().trim()
                if (name.isNotEmpty()) {
                    val type = typeIn.text.toString().trim().ifEmpty { "General" }
                    if (existing == null) Store.addActivity(this, name, type, picked)
                    else Store.updateActivity(this, existing.getString("id"), name, type, picked)
                    render()
                    doSync()
                }
            }
            .setNegativeButton("Cancelar", null)
        if (existing != null) {
            builder.setNeutralButton("Borrar") { _, _ -> confirmDelete(existing) }
        }
        builder.show()
    }

    private fun confirmDelete(act: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("Borrar \"${act.getString("name")}\"")
            .setMessage("También se borran sus sesiones. ¿Seguro?")
            .setPositiveButton("Borrar") { _, _ ->
                Store.deleteActivity(this, act.getString("id"))
                render()
                doSync()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doSync() {
        if (!Config.syncEnabled()) return
        Thread {
            val ok = Sync.syncNow(this)
            runOnUiThread { if (ok) render() }
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
