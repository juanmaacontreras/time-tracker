package com.bitacora.timer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Chronometer
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var bannerPause: TextView
    private lateinit var bannerStop: TextView
    private lateinit var tabTimer: TextView
    private lateinit var tabResumen: TextView

    private var tab = "timer"
    private var statsPeriod = "day"
    private var statsCategory = "all"
    private var catExpanded: Boolean? = null  // null = usar default según cantidad
    private var statsOffset = 0  // 0 = período actual, -1 = el anterior, etc.
    private var showArchived = false

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
        bannerPause = findViewById(R.id.bannerPause)
        bannerStop = findViewById(R.id.bannerStop)
        tabTimer = findViewById(R.id.tabTimer)
        tabResumen = findViewById(R.id.tabResumen)

        bannerPause.setOnClickListener {
            if (Store.runningPaused(this)) Store.resume(this) else Store.pause(this)
            render()
            doSync()
        }
        bannerStop.setOnClickListener {
            Store.stop(this)
            render()
            doSync()
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
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun switchTab(t: String) {
        tab = t
        timerView.visibility = if (t == "timer") View.VISIBLE else View.GONE
        resumenView.visibility = if (t == "resumen") View.VISIBLE else View.GONE
        styleTabs()
        render()
    }

    private fun styleTabs() {
        fun paint(tv: TextView, active: Boolean) {
            if (active) {
                val d = GradientDrawable()
                d.cornerRadius = dp(8).toFloat()
                d.setColor(col(R.color.ink))
                tv.background = d
                tv.setTextColor(col(R.color.paper))
            } else {
                tv.background = null
                tv.setTextColor(col(R.color.inkSoft))
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
        val allWithArchived = Store.activities(this, includeArchived = true)
        val hasArchived = allWithArchived.any { it.optBoolean("archived", false) }

        // Orden por tiempo dedicado hoy (descendente), igual criterio que los widgets.
        val acts = allWithArchived
            .filter { !it.optBoolean("archived", false) }
            .sortedByDescending { Store.secsFor(this, it.getString("id"), Store.startOfToday()) }

        if (hasArchived) list.addView(buildArchivedToggle())

        for (act in acts) list.addView(buildRow(act, runId))
        if (acts.isEmpty() && !hasArchived) {
            val hint = TextView(this)
            hint.text = "Todavía no tenés actividades. Creá la primera acá abajo."
            hint.setTextColor(col(R.color.muted))
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, dp(10), 0, dp(14))
            list.addView(hint)
        }
        list.addView(buildAddCard())

        if (showArchived) {
            val archivedActs = allWithArchived
                .filter { it.optBoolean("archived", false) }
                .sortedByDescending { Store.secsFor(this, it.getString("id"), Store.startOfToday()) }
            if (archivedActs.isNotEmpty()) {
                list.addView(sectionLabel("ARCHIVADAS"))
                for (act in archivedActs) list.addView(buildRow(act, runId))
            }
        }
        renderBanner(runId)
    }

    private fun buildArchivedToggle(): View {
        val chip = TextView(this)
        chip.text = if (showArchived) "▾ Ocultar archivadas" else "▸ Ver archivadas"
        chip.textSize = 12f
        chip.setTextColor(col(R.color.inkSoft))
        chip.setPadding(dp(12), dp(7), dp(12), dp(7))
        val d = GradientDrawable()
        d.cornerRadius = dp(16).toFloat()
        d.setColor(col(R.color.card))
        d.setStroke(dp(1), col(R.color.line))
        chip.background = d
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        chip.layoutParams = lp
        chip.setOnClickListener { showArchived = !showArchived; renderTimer() }
        return chip
    }

    private fun renderBanner(runId: String) {
        if (runId.isNotEmpty()) {
            val a = Store.activityById(this, runId)
            val paused = Store.runningPaused(this)
            bannerName.text = a?.optString("name") ?: "—"
            // El chrono siempre se posiciona con el tiempo real corrido (sin pausas).
            val elapsed = Store.runningElapsedMs(this)
            bannerChrono.base = SystemClock.elapsedRealtime() - elapsed
            if (paused) {
                bannerLabel.text = "PAUSADO"
                bannerChrono.stop()
                bannerPause.text = "Resumir"
            } else {
                bannerLabel.text = "GRABANDO"
                bannerChrono.start()
                bannerPause.text = "Pausar"
            }
            bannerPause.visibility = View.VISIBLE
            bannerStop.visibility = View.VISIBLE
        } else {
            bannerLabel.text = "EN REPOSO"
            bannerName.text = "Nada corriendo"
            bannerChrono.stop()
            bannerChrono.base = SystemClock.elapsedRealtime()
            bannerPause.visibility = View.GONE
            bannerStop.visibility = View.GONE
        }
    }

    private fun card(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(col(R.color.card))
        d.cornerRadius = dp(12).toFloat()
        d.setStroke(dp(1), col(R.color.line))
        return d
    }

    // Envuelve un fondo con un ripple recortado a la misma forma, para dar feedback
    // táctil inmediato (tap corto o mantener presionado) sin animaciones vistosas.
    private fun withRipple(content: Drawable, cornerRadius: Float): Drawable {
        val mask = GradientDrawable()
        mask.cornerRadius = cornerRadius
        mask.setColor(Color.WHITE)
        val rippleColor = ColorStateList.valueOf(col(R.color.indigo))
        return RippleDrawable(rippleColor, content, mask)
    }

    private fun buildRow(act: JSONObject, runId: String): View {
        val id = act.getString("id")
        val running = id == runId
        val paused = running && Store.runningPaused(this)
        val expanded = id in expandedIds
        val archived = act.optBoolean("archived", false)

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
        // Borde consistente para los tres estados: solo cambia el color.
        if (running) bg.setStroke(dp(2), if (paused) col(R.color.pausedColor) else col(R.color.live))
        // Ripple al tocar/mantener presionado: feedback inmediato de que el toque se registró.
        row.background = withRipple(bg, dp(12).toFloat())

        val arrow = TextView(this)
        arrow.text = if (expanded) "▾" else "▸"
        arrow.setTextColor(col(R.color.muted))
        arrow.textSize = 20f
        arrow.gravity = Gravity.CENTER
        arrow.minWidth = dp(44)
        arrow.minHeight = dp(44)
        arrow.setPadding(dp(4), dp(4), dp(4), dp(4))
        arrow.isClickable = true
        arrow.setOnClickListener {
            if (expanded) expandedIds.remove(id) else expandedIds.add(id)
            renderTimer()
        }
        row.addView(arrow)

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
        val typeText = act.optString("type", "").uppercase()
        type.text = if (archived) (if (typeText.isEmpty()) "ARCHIVADA" else "$typeText · ARCHIVADA") else typeText
        type.setTextColor(col(R.color.muted))
        type.textSize = 10f
        if (archived) { row.alpha = 0.55f }
        val name = TextView(this)
        name.text = act.getString("name")
        name.setTextColor(col(R.color.ink))
        name.textSize = 16f
        name.setTypeface(name.typeface, Typeface.BOLD)
        mid.addView(type)
        mid.addView(name)
        row.addView(mid)

        val today = TextView(this)
        today.text = Store.exact(Store.secsFor(this, id, Store.startOfToday()))
        today.setTextColor(
            when {
                paused -> col(R.color.pausedColor)
                running -> col(R.color.live)
                else -> col(R.color.ink)
            }
        )
        today.textSize = 14f
        today.typeface = Typeface.MONOSPACE
        today.setPadding(0, 0, dp(12), 0)
        row.addView(today)
        todayViews[id] = today

        // Ícono real: forma = si tocar para o arranca (■ para / ▶ arranca); color = estado.
        // Tocar la activa (corriendo o pausada) siempre para — pausar es solo con el botón dedicado.
        val glyph = ImageView(this)
        glyph.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        glyph.setColorFilter(
            when {
                paused -> col(R.color.pausedColor)
                running -> col(R.color.live)
                else -> col(R.color.indigo)
            }
        )
        val glp = LinearLayout.LayoutParams(dp(18), dp(18))
        glyph.layoutParams = glp
        row.addView(glyph)

        row.setOnClickListener {
            if (archived) {
                openActivitySheet(act)
            } else {
                Store.toggle(this, id)
                render()
                doSync()
            }
        }
        // Mantener presionado abre la edición directa, sin entrar al modo global.
        row.setOnLongClickListener {
            openActivitySheet(act)
            true
        }
        container.addView(row)
        if (expanded) container.addView(buildSessionsPanel(act))
        return container
    }

    private fun buildSessionsPanel(act: JSONObject): View {
        val id = act.getString("id")
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(10), dp(14), dp(10))
        val bg = GradientDrawable()
        bg.setColor(col(R.color.panel))
        bg.cornerRadius = dp(12).toFloat()
        panel.background = bg
        val plp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        plp.topMargin = dp(4)
        panel.layoutParams = plp

        val sessions = Store.sessionsForActivityToday(this, id)
        val title = TextView(this)
        title.text = "ENTRADAS DE HOY"
        title.setTextColor(col(R.color.muted))
        title.textSize = 10f
        title.letterSpacing = 0.1f
        panel.addView(title)

        if (sessions.isEmpty()) {
            val e = TextView(this)
            e.text = "Sin entradas cerradas todavia."
            e.setTextColor(col(R.color.muted))
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
            label.setTextColor(col(R.color.ink))
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            srow.addView(label)

            val confirmDelete = {
                val d = AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("Borrar entrada")
                    .setMessage("Se borra el registro de " + fmt.format(s.getLong("start")) + " a " + fmt.format(s.getLong("end")) + ". Seguro?")
                    .setPositiveButton("Borrar") { _, _ ->
                        Store.deleteSession(this, s.getString("id"))
                        renderTimer()
                        doSync()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(col(R.color.live))
                Unit
            }

            val del = TextView(this)
            del.text = "✕"
            del.setTextColor(col(R.color.live))
            del.textSize = 16f
            del.setTypeface(del.typeface, Typeface.BOLD)
            del.gravity = Gravity.CENTER
            del.minWidth = dp(36)
            del.minHeight = dp(36)
            del.setPadding(dp(8), dp(4), dp(4), dp(4))
            del.setOnClickListener { confirmDelete() }
            srow.addView(del)
            panel.addView(srow)
        }
        return panel
    }

    private fun buildAddCard(): View {
        val add = TextView(this)
        add.text = "+  Nueva actividad"
        add.gravity = Gravity.CENTER
        add.setTextColor(col(R.color.indigo))
        add.textSize = 14f
        add.setTypeface(add.typeface, Typeface.BOLD)
        add.setPadding(dp(14), dp(18), dp(14), dp(18))
        val d = GradientDrawable()
        d.cornerRadius = dp(12).toFloat()
        d.setStroke(dp(2), col(R.color.line))
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
        if (tab != "timer") return
        val runId = Store.runningActId(this)
        if (runId.isEmpty() || Store.runningPaused(this)) return
        todayViews[runId]?.text = Store.exact(Store.secsFor(this, runId, Store.startOfToday()))
    }

    // ---------------- RESUMEN TAB ----------------
    private fun renderResumen() {
        resumenView.removeAllViews()
        resumenView.addView(buildPeriodSelector())

        val (from, to) = Store.periodRange(statsPeriod, statsOffset)
        resumenView.addView(buildOffsetNav(periodLabel(statsPeriod, statsOffset, from, to)))

        // Incluye archivadas: su historial sigue contando en las estadísticas.
        val allActs = Store.activities(this, includeArchived = true)

        val allCats = allActs.map { it.optString("type", "General").ifEmpty { "General" } }
            .distinct().sorted()
        if (allCats.size > 1) resumenView.addView(buildCategorySelector(allCats))
        if (statsCategory != "all" && statsCategory !in allCats) statsCategory = "all"

        val acts = if (statsCategory == "all") allActs
            else allActs.filter { it.optString("type", "General").ifEmpty { "General" } == statsCategory }

        val perAct = acts.map { it to Store.secsFor(this, it.getString("id"), from, to) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val total = perAct.sumOf { it.second }

        val totalLabel = if (statsCategory == "all") "TOTAL" else "TOTAL · ${statsCategory.uppercase()}"
        resumenView.addView(sectionLabel(totalLabel))
        val big = TextView(this)
        big.text = Store.exact(total)
        big.textSize = 30f
        big.typeface = Typeface.MONOSPACE
        big.setTextColor(col(R.color.ink))
        resumenView.addView(big)

        if (perAct.isEmpty()) {
            val e = TextView(this)
            e.text = "Sin registros en este período."
            e.setTextColor(col(R.color.muted))
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
            val bars = if (statsPeriod == "week") weekBars(actIds, from)
                else monthBars(actIds, from, if (statsOffset == 0) java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) else Store.daysInMonth(from))
            resumenView.addView(buildBarChart(bars, statsPeriod == "week", if (statsPeriod == "week") 1 else 5))
        }
        for ((a, sec) in perAct) {
            resumenView.addView(statRow(a.getString("name"), Color.parseColor(a.optString("color", "#2F4B8F")), sec, total))
        }

        if (statsCategory == "all") {
            val cats = LinkedHashMap<String, Long>()
            for (a in allActs) {
                val t = Store.secsFor(this, a.getString("id"), from, to)
                if (t > 0) {
                    val c = a.optString("type", "General").ifEmpty { "General" }
                    cats[c] = (cats[c] ?: 0L) + t
                }
            }
            if (cats.isNotEmpty()) {
                // Colapsable: por defecto expandida si hay pocas categorías.
                val expanded = catExpanded ?: (cats.size <= 3)
                val header = sectionLabel("POR CATEGORÍA  " + if (expanded) "▾" else "▸") as TextView
                header.isClickable = true
                header.setOnClickListener { catExpanded = !expanded; renderResumen() }
                resumenView.addView(header)
                if (expanded) {
                    for ((c, sec) in cats.entries.sortedByDescending { it.value }) {
                        val row = statRow(c, col(R.color.indigo), sec, total)
                        row.setOnClickListener { statsCategory = c; renderResumen() }
                        resumenView.addView(row)
                    }
                }
            }
        }

        resumenView.addView(buildCsvButton())
    }

    private fun buildCategorySelector(cats: List<String>): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.setPadding(0, dp(10), 0, 0)

        // Color representativo de cada categoría (el de su primera actividad).
        val catColor = HashMap<String, Int>()
        for (a in Store.activities(this, includeArchived = true)) {
            val c = a.optString("type", "General").ifEmpty { "General" }
            if (c !in catColor) catColor[c] = Color.parseColor(a.optString("color", "#2F4B8F"))
        }

        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun chip(label: String, value: String, dotColor: Int?): View {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.HORIZONTAL
            c.gravity = Gravity.CENTER_VERTICAL
            c.setPadding(dp(12), dp(7), dp(12), dp(7))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6)
            c.layoutParams = lp
            val active = value == statsCategory
            val d = GradientDrawable()
            d.cornerRadius = dp(16).toFloat()
            if (active) {
                d.setColor(col(R.color.ink))
            } else {
                d.setColor(col(R.color.card))
                d.setStroke(dp(1), col(R.color.line))
            }
            c.background = d

            if (dotColor != null) {
                val dot = View(this)
                val dotBg = GradientDrawable()
                dotBg.shape = GradientDrawable.OVAL
                dotBg.setColor(dotColor)
                dot.background = dotBg
                val dlp = LinearLayout.LayoutParams(dp(8), dp(8)); dlp.rightMargin = dp(6)
                dot.layoutParams = dlp
                c.addView(dot)
            }

            val t = TextView(this)
            t.text = label
            t.textSize = 12f
            t.setTextColor(if (active) col(R.color.paper) else col(R.color.inkSoft))
            c.addView(t)

            c.setOnClickListener { statsCategory = value; renderResumen() }
            return c
        }

        row.addView(chip("Todo", "all", null))
        for (c in cats) row.addView(chip(c, c, catColor[c]))
        scroll.addView(row)
        wrap.addView(scroll)
        return wrap
    }

    private fun sectionLabel(text: String): View {
        val t = TextView(this)
        t.text = text
        t.setTextColor(col(R.color.muted))
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
        segBg.cornerRadius = dp(12).toFloat()
        segBg.setColor(col(R.color.card))
        segBg.setStroke(dp(1), col(R.color.line))
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
                d.setColor(col(R.color.ink))
                b.background = d
                b.setTextColor(col(R.color.paper))
            } else {
                b.setTextColor(col(R.color.inkSoft))
            }
            b.setOnClickListener { statsPeriod = p; statsOffset = 0; renderResumen() }
            seg.addView(b)
        }
        return seg
    }

    // ---- navegación de período (◀ actual/pasado ▶) ----
    private fun periodLabel(period: String, offset: Int, from: Long, to: Long): String {
        val locale = java.util.Locale("es")
        return when (period) {
            "day" -> when (offset) {
                0 -> "Hoy"
                -1 -> "Ayer"
                else -> java.text.SimpleDateFormat("d 'de' MMMM", locale).format(java.util.Date(from))
            }
            "week" -> if (offset == 0) "Esta semana" else {
                val fmt = java.text.SimpleDateFormat("d MMM", locale)
                val endInclusive = java.util.Date(to - 1)
                "${fmt.format(java.util.Date(from))} – ${fmt.format(endInclusive)}"
            }
            "month" -> if (offset == 0) "Este mes" else {
                java.text.SimpleDateFormat("MMMM yyyy", locale).format(java.util.Date(from))
                    .replaceFirstChar { it.uppercase() }
            }
            else -> ""
        }
    }

    private fun buildOffsetNav(label: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        row.layoutParams = lp

        fun arrow(symbol: String, enabled: Boolean, onClick: () -> Unit): View {
            val t = TextView(this)
            t.text = symbol
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            t.minWidth = dp(36)
            t.minHeight = dp(36)
            t.setTextColor(if (enabled) col(R.color.indigo) else col(R.color.line))
            if (enabled) t.setOnClickListener { onClick() }
            return t
        }

        row.addView(arrow("◀", true) { statsOffset -= 1; renderResumen() })

        val t = TextView(this)
        t.text = label
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(col(R.color.ink))
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(t)

        // No se puede navegar a "el futuro": el offset máximo es 0 (el período actual).
        row.addView(arrow("▶", statsOffset < 0) { statsOffset += 1; renderResumen() })
        return row
    }

    private val DAY_MS = 86400000L

    private fun weekBars(actIds: Set<String>, weekStart: Long): List<Triple<String, Long, Int>> {
        val labels = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
        val c = col(R.color.indigo)
        return (0..6).map { i ->
            val ds = weekStart + i * DAY_MS
            Triple(labels[i], Store.totalBetween(this, ds, ds + DAY_MS, actIds), c)
        }
    }

    // dayCount: días a recorrer (parcial hasta hoy si es el mes actual, completo si es un mes pasado).
    private fun monthBars(actIds: Set<String>, monthStart: Long, dayCount: Int): List<Triple<String, Long, Int>> {
        val c = col(R.color.indigo)
        return (0 until dayCount).map { i ->
            val ds = monthStart + i * DAY_MS
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
                v.setTextColor(col(R.color.inkSoft))
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
            n.setTextColor(col(R.color.muted))
            n.gravity = Gravity.CENTER
            n.maxLines = 1
            n.ellipsize = TextUtils.TruncateAt.END
            n.setPadding(0, dp(4), 0, 0)
            col.addView(n)

            wrap.addView(col)
        }

        // Contenedor vertical: barras + línea de base sutil para anclarlas.
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        val olp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        olp.bottomMargin = dp(14)
        outer.layoutParams = olp
        outer.addView(wrap)
        val baseline = View(this)
        val bllp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        baseline.layoutParams = bllp
        baseline.setBackgroundColor(col(R.color.line))
        outer.addView(baseline)
        return outer
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
        nm.setTextColor(col(R.color.ink))
        nm.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(nm)

        val vl = TextView(this)
        val pct = if (total > 0) Math.round(sec * 100.0 / total).toInt() else 0
        vl.text = "${Store.exact(sec)}  ·  $pct%"
        vl.textSize = 13f
        vl.typeface = Typeface.MONOSPACE
        vl.setTextColor(col(R.color.inkSoft))
        top.addView(vl)
        box.addView(top)

        val track = LinearLayout(this)
        track.orientation = LinearLayout.HORIZONTAL
        val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        tlp.topMargin = dp(9)
        track.layoutParams = tlp
        val trackBg = GradientDrawable()
        trackBg.cornerRadius = dp(5).toFloat()
        trackBg.setColor(col(R.color.grid))
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
        val b = TextView(this)
        b.text = "Exportar CSV"
        b.textSize = 13f
        b.gravity = Gravity.CENTER
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setTextColor(col(R.color.indigo))
        b.setPadding(dp(14), dp(14), dp(14), dp(14))
        val d = GradientDrawable()
        d.cornerRadius = dp(12).toFloat()
        d.setColor(col(R.color.card))
        d.setStroke(dp(1), col(R.color.line))
        b.background = d
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(20)
        b.layoutParams = lp
        b.setOnClickListener {
            val (rFrom, rTo) = Store.periodRange(statsPeriod, statsOffset)
            val periodTxt = periodLabel(statsPeriod, statsOffset, rFrom, rTo)
            val filterLabel = if (statsCategory == "all") periodTxt
                else "$periodTxt · $statsCategory"
            AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("Exportar CSV")
                .setItems(arrayOf("Todo el historial", "Solo lo filtrado ($filterLabel)")) { _, which ->
                    exportCsv(filtered = which == 1)
                }
                .show()
        }
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
        colorLabel.setTextColor(col(R.color.muted))
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
            if (c == picked) d.setStroke(dp(3), col(R.color.ink))
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
        // Con 14 colores conviene poder desplazar horizontalmente.
        val colorScroll = android.widget.HorizontalScrollView(this)
        colorScroll.isHorizontalScrollBarEnabled = false
        colorScroll.addView(colorRow)
        box.addView(colorScroll)

        var dlg: AlertDialog? = null

        // Archivar / Desarchivar (solo para actividades existentes).
        if (existing != null) {
            val archived = existing.optBoolean("archived", false)
            val arch = TextView(this)
            arch.text = if (archived) "Desarchivar actividad" else "Archivar actividad"
            arch.setTextColor(col(R.color.indigo))
            arch.setTypeface(arch.typeface, Typeface.BOLD)
            arch.textSize = 14f
            arch.setPadding(0, dp(18), 0, dp(4))
            arch.setOnClickListener {
                if (archived) Store.unarchiveActivity(this, existing.getString("id"))
                else Store.archiveActivity(this, existing.getString("id"))
                render()
                doSync()
                dlg?.dismiss()
            }
            box.addView(arch)
        }

        val builder = AlertDialog.Builder(this, R.style.AppDialog)
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
        val dialog = builder.create()
        dlg = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(col(R.color.live))
    }

    private fun confirmDelete(act: JSONObject) {
        val d = AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Borrar \"${act.getString("name")}\"")
            .setMessage("También se borran sus sesiones. ¿Seguro?")
            .setPositiveButton("Borrar") { _, _ ->
                Store.deleteActivity(this, act.getString("id"))
                render()
                doSync()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(col(R.color.live))
    }

    private fun doSync() {
        if (!Config.syncEnabled()) return
        Thread {
            val ok = Sync.syncNow(this)
            runOnUiThread { if (ok) render() }
        }.start()
    }

    private fun exportCsv(filtered: Boolean) {
        try {
            val csv = if (filtered) {
                val (from, to) = Store.periodRange(statsPeriod, statsOffset)
                val ids: Set<String>? = if (statsCategory == "all") null
                    else Store.activities(this, includeArchived = true)
                        .filter { it.optString("type", "General").ifEmpty { "General" } == statsCategory }
                        .map { it.getString("id") }.toSet()
                Store.exportCsv(this, from, to, ids)
            } else {
                Store.exportCsv(this)
            }
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
