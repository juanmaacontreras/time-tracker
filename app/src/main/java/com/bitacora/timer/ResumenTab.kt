package com.bitacora.timer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

// Pestaña Resumen: totales del período, gráficos, desglose por categoría y exportar (CSV / backup).
// Funciones de extensión de MainActivity: comparten su estado (campos internal).

private const val DAY_MS = 86400000L

internal fun MainActivity.renderResumen() {
    resumenView.removeAllViews()
    resumenView.addView(buildPeriodSelector())

    val (from, to) = Store.periodRange(statsPeriod, statsOffset)
    resumenView.addView(buildOffsetNav(periodLabel(statsPeriod, statsOffset, from, to)))

    // Incluye archivadas: su historial sigue contando en las estadísticas.
    val allActs = Store.activities(this, includeArchived = true)

    val allCats = allActs.map { Store.categoryForActivity(this, it) }
        .distinctBy { it.getString("id") }
        .sortedBy { it.optString("name", "") }
    if (allCats.size > 1) resumenView.addView(buildCategorySelector(allCats))
    if (statsCategory != "all" && allCats.none { it.getString("id") == statsCategory }) statsCategory = "all"

    val acts = if (statsCategory == "all") allActs
        else allActs.filter { Store.categoryForActivity(this, it).getString("id") == statsCategory }

    // Una sola pasada por el historial para todo el período; el resto sale de acá.
    val secs = Store.secsByActivity(this, from, to)
    val perAct = acts.map { it to (secs[it.getString("id")] ?: 0L) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val total = perAct.sumOf { it.second }

    val totalLabel = if (statsCategory == "all") "TOTAL"
        else "TOTAL · ${(allCats.find { it.getString("id") == statsCategory }?.optString("name", "") ?: "").uppercase()}"
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
    val catColorsAll = categoryColors(allActs)
    // Día → por actividad. Mes → por día. Semana → por día TAMBIÉN, pero cada barra
    // apilada por categoría (así se ve en qué día se le dedicó tiempo a qué). Si ya hay
    // un filtro de categoría activo, apilar una sola categoría no aporta nada: cae a
    // "por actividad" como día.
    if (statsPeriod == "week" && statsCategory == "all") {
        resumenView.addView(sectionLabel("POR DÍA · CATEGORÍA"))
        // Orden fijo de categorías (por total de la semana) para que el mismo color
        // quede siempre en la misma posición dentro de cada barra apilada.
        val catOrder = categorySums(allActs, secs).entries.sortedByDescending { it.value }.map { it.key }
        val stacks = weekCategoryStacks(allActs, from, catOrder)
        resumenView.addView(buildStackedBarChart(stacks, catColorsAll))
    } else if (statsPeriod == "day" || statsPeriod == "week") {
        resumenView.addView(sectionLabel("POR ACTIVIDAD"))
        val bars = perAct.take(8).map {
            Triple(it.first.getString("name"), it.second, Color.parseColor(it.first.optString("color", "#2F4B8F")))
        }
        resumenView.addView(buildBarChart(bars, true, 1))
    } else {
        resumenView.addView(sectionLabel("POR DÍA"))
        val bars = monthBars(actIds, from, if (statsOffset == 0) java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) else Store.daysInMonth(from))
        resumenView.addView(buildBarChart(bars, false, 5))
    }
    for ((a, sec) in perAct) {
        resumenView.addView(statRow(a.getString("name"), Color.parseColor(a.optString("color", "#2F4B8F")), sec, total))
    }

    if (statsCategory == "all") {
        val cats = categorySums(allActs, secs)
        if (cats.isNotEmpty()) {
            // Colapsable: por defecto expandida si hay pocas categorías.
            val expanded = catExpanded ?: (cats.size <= 3)
            val header = sectionLabel("POR CATEGORÍA  " + if (expanded) "▾" else "▸") as TextView
            header.isClickable = true
            header.setOnClickListener { catExpanded = !expanded; renderResumen() }
            resumenView.addView(header)
            if (expanded) {
                for ((c, sec) in cats.entries.sortedByDescending { it.value }) {
                    val label = Store.categoryById(this, c)?.optString("name", "") ?: "General"
                    // Mismo color que en el gráfico apilado: sirve de leyenda.
                    val row = statRow(label, catColorsAll[c] ?: col(R.color.indigo), sec, total)
                    row.setOnClickListener { statsCategory = c; renderResumen() }
                    resumenView.addView(row)
                }
            }
        }
    }

    resumenView.addView(buildCsvButton())
}

// Color de cada categoría (propiedad propia de la categoría ahora, no de la primera
// actividad). Se usa como "leyenda" consistente entre el selector, el gráfico apilado
// y la lista colapsable — el mismo color en todos lados identifica cada una.
internal fun MainActivity.categoryColors(acts: List<JSONObject>): LinkedHashMap<String, Int> {
    val map = LinkedHashMap<String, Int>()
    for (a in acts) {
        val cat = Store.categoryForActivity(this, a)
        val id = cat.getString("id")
        if (id !in map) map[id] = Color.parseColor(cat.optString("color", "#2F4B8F"))
    }
    return map
}

internal fun MainActivity.buildCategorySelector(cats: List<JSONObject>): View {
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.HORIZONTAL
    wrap.setPadding(0, dp(10), 0, 0)

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
    for (c in cats) {
        row.addView(chip(c.optString("name", "General"), c.getString("id"), Color.parseColor(c.optString("color", "#2F4B8F"))))
    }
    scroll.addView(row)
    wrap.addView(scroll)
    return wrap
}

internal fun MainActivity.buildPeriodSelector(): View {
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

internal fun MainActivity.periodLabel(period: String, offset: Int, from: Long, to: Long): String {
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

internal fun MainActivity.buildOffsetNav(label: String): View {
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

// Segundos por categoría, a partir de los segundos por actividad ya calculados.
internal fun MainActivity.categorySums(acts: List<JSONObject>, secs: Map<String, Long>): LinkedHashMap<String, Long> {
    val sums = LinkedHashMap<String, Long>()
    for (a in acts) {
        val t = secs[a.getString("id")] ?: 0L
        if (t > 0) {
            val c = Store.categoryForActivity(this, a).getString("id")
            sums[c] = (sums[c] ?: 0L) + t
        }
    }
    return sums
}

// dayCount: días a recorrer (parcial hasta hoy si es el mes actual, completo si es un mes pasado).
internal fun MainActivity.monthBars(actIds: Set<String>, monthStart: Long, dayCount: Int): List<Triple<String, Long, Int>> {
    val c = col(R.color.indigo)
    return (0 until dayCount).map { i ->
        val ds = monthStart + i * DAY_MS
        Triple("${i + 1}", Store.totalBetween(this, ds, ds + DAY_MS, actIds), c)
    }
}

// Para cada día de la semana, el desglose por categoría (en el orden fijo catOrder,
// así el mismo color queda siempre en la misma posición dentro de cada barra apilada).
internal fun MainActivity.weekCategoryStacks(
    acts: List<JSONObject>, weekStart: Long, catOrder: List<String>
): List<Pair<String, List<Pair<String, Long>>>> {
    val labels = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
    return (0..6).map { i ->
        val ds = weekStart + i * DAY_MS
        val perCat = categorySums(acts, Store.secsByActivity(this, ds, ds + DAY_MS))
        val ordered = catOrder.mapNotNull { c -> perCat[c]?.let { c to it } }
        labels[i] to ordered
    }
}

// Gráfico de barras apiladas: una columna por día, cada una dividida en segmentos de
// color según la categoría (mismo color que el selector y la lista de categorías).
internal fun MainActivity.buildStackedBarChart(
    days: List<Pair<String, List<Pair<String, Long>>>>, catColors: Map<String, Int>
): View {
    val chartH = dp(150)
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.HORIZONTAL
    wrap.gravity = Gravity.BOTTOM
    val wlp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
    wlp.topMargin = dp(6); wlp.bottomMargin = dp(4)
    wrap.layoutParams = wlp

    val totals = days.map { d -> d.second.sumOf { it.second } }
    val max = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L)

    for ((idx, day) in days.withIndex()) {
        val (label, segs) = day
        val total = totals[idx]

        val dayCol = LinearLayout(this)
        dayCol.orientation = LinearLayout.VERTICAL
        dayCol.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        dayCol.setPadding(dp(2), 0, dp(2), 0)
        dayCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val v = TextView(this)
        v.text = if (total > 0) Store.exact(total) else ""
        v.textSize = 9f
        v.setTextColor(col(R.color.inkSoft))
        v.gravity = Gravity.CENTER
        v.maxLines = 1
        dayCol.addView(v)

        val barH = Math.max(dp(3), (total.toDouble() / max * chartH).toInt())
        val stack = LinearLayout(this)
        stack.orientation = LinearLayout.VERTICAL
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barH)
        slp.topMargin = dp(3); slp.leftMargin = dp(1); slp.rightMargin = dp(1)
        stack.layoutParams = slp
        // Los segmentos son Views planos sin radio propio; el redondeo de las puntas
        // superiores lo da el fondo de este contenedor + clipToOutline (recorta los
        // hijos al contorno redondeado, como el overflow:hidden del espejo web).
        val stackBg = GradientDrawable()
        val r = dp(4).toFloat()
        stackBg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        stack.background = stackBg
        stack.clipToOutline = true
        for ((c, secs) in segs) {
            val segH = Math.max(dp(1), (secs.toDouble() / total * barH).toInt())
            val seg = View(this)
            seg.setBackgroundColor(catColors[c] ?: col(R.color.indigo))
            seg.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, segH)
            stack.addView(seg)
        }
        dayCol.addView(stack)

        val n = TextView(this)
        n.text = label
        n.textSize = 9f
        n.setTextColor(col(R.color.muted))
        n.gravity = Gravity.CENTER
        n.maxLines = 1
        n.setPadding(0, dp(4), 0, 0)
        dayCol.addView(n)

        wrap.addView(dayCol)
    }

    val outer = LinearLayout(this)
    outer.orientation = LinearLayout.VERTICAL
    val olp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    olp.bottomMargin = dp(14)
    outer.layoutParams = olp
    outer.addView(wrap)
    val baseline = View(this)
    baseline.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    baseline.setBackgroundColor(col(R.color.line))
    outer.addView(baseline)
    return outer
}

internal fun MainActivity.buildBarChart(bars: List<Triple<String, Long, Int>>, showValues: Boolean, labelStep: Int): View {
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

internal fun MainActivity.statRow(name: String, colorInt: Int, sec: Long, total: Long): View {
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

internal fun MainActivity.buildCsvButton(): View {
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
        val catName = Store.categoryById(this, statsCategory)?.optString("name", "") ?: statsCategory
        val filterLabel = if (statsCategory == "all") periodTxt else "$periodTxt · $catName"
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Exportar")
            .setItems(
                arrayOf(
                    "Historial completo (CSV)",
                    "Solo lo filtrado (CSV) · $filterLabel",
                    "Backup completo (JSON)"
                )
            ) { _, which ->
                when (which) {
                    0 -> exportCsv(filtered = false)
                    1 -> exportCsv(filtered = true)
                    else -> exportBackup()
                }
            }
            .show()
    }
    return b
}

internal fun MainActivity.exportCsv(filtered: Boolean) {
    try {
        val csv = if (filtered) {
            val (from, to) = Store.periodRange(statsPeriod, statsOffset)
            val ids: Set<String>? = if (statsCategory == "all") null
                else Store.activities(this, includeArchived = true)
                    .filter { Store.categoryForActivity(this, it).getString("id") == statsCategory }
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

// Backup crudo y completo, para poder volver atrás si algo corrompe los datos.
// El CSV no sirve para eso: exporta sesiones de un período, no el estado entero.
internal fun MainActivity.exportBackup() {
    try {
        val json = Store.fullBackup(this).toString(2)
        val fecha = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(Store.now()))
        // Nombre con fecha para que dos backups no se pisen entre sí.
        val file = File(cacheDir, "bitacora-backup-$fecha.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
        send.type = "application/json"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Guardar backup"))
    } catch (e: Exception) {
        Toast.makeText(this, "No se pudo generar el backup", Toast.LENGTH_SHORT).show()
    }
}
