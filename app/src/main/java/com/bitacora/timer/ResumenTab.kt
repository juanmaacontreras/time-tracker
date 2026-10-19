package com.bitacora.timer

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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

    // Tarjeta del total + gráfico.
    val card = cardBox(radiusDp = 22)
    card.setPadding(dp(18), dp(18), dp(18), dp(12))
    card.layoutParams = lp(top = 14)
    val catName = allCats.find { it.getString("id") == statsCategory }?.optString("name", "") ?: ""
    card.addView(label(if (statsCategory == "all") "Total" else "Total · $catName", 13f, col(R.color.muted), bold = true))
    val big = label(Store.exact(total), 44f, col(R.color.ink), tnum = true)
    big.letterSpacing = -0.03f
    big.includeFontPadding = false
    big.setPadding(0, dp(4), 0, 0)
    card.addView(big)
    resumenView.addView(card)

    if (perAct.isEmpty()) {
        val e = label("Sin registros en este período.", 14f, col(R.color.muted))
        e.gravity = Gravity.CENTER
        e.setPadding(0, dp(22), 0, dp(10))
        card.addView(e)
        return
    }
    val sub = label(if (perAct.size == 1) "en 1 actividad" else "en ${perAct.size} actividades", 13f, col(R.color.inkSoft))
    sub.setPadding(0, dp(4), 0, 0)
    card.addView(sub)

    val actIds = acts.map { it.getString("id") }.toSet()
    val catColorsAll = categoryColors(allActs)
    // Día → por actividad. Mes → por día. Semana → por día TAMBIÉN, pero cada barra
    // apilada por categoría (así se ve en qué día se le dedicó tiempo a qué). Si ya hay
    // un filtro de categoría activo, apilar una sola categoría no aporta nada: cae a
    // "por actividad" como día.
    if (statsPeriod == "week" && statsCategory == "all") {
        // Orden fijo de categorías (por total de la semana) para que el mismo color
        // quede siempre en la misma posición dentro de cada barra apilada.
        val catOrder = categorySums(allActs, secs).entries.sortedByDescending { it.value }.map { it.key }
        val stacks = weekCategoryStacks(allActs, from, catOrder)
        card.addView(buildChart(stacks.map { (lbl, segs) -> Bar(lbl, segs.map { (c, t) -> t to (catColorsAll[c] ?: col(R.color.indigo)) }) },
            showValues = true, labelStep = 1, todayIdx = todayIndex(from, 7)))
    } else if (statsPeriod == "day" || statsPeriod == "week") {
        val bars = perAct.take(8).map { Bar(it.first.getString("name"), listOf(it.second to parseColor(it.first.optString("color")))) }
        card.addView(buildChart(bars, showValues = true, labelStep = 1, todayIdx = -1))
    } else {
        val days = if (statsOffset == 0) java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) else Store.daysInMonth(from)
        card.addView(buildChart(monthBars(actIds, from, days), showValues = false, labelStep = 5, todayIdx = todayIndex(from, days)))
    }

    resumenView.addView(sectionHeader("Por actividad"))
    val list = cardBox(radiusDp = 18)
    list.setPadding(dp(16), dp(4), dp(16), dp(4))
    for ((i, pair) in perAct.withIndex()) {
        list.addView(statRow(pair.first.getString("name"), parseColor(pair.first.optString("color")), pair.second, total, first = i == 0))
    }
    resumenView.addView(list)

    if (statsCategory == "all") {
        val cats = categorySums(allActs, secs)
        if (cats.isNotEmpty()) {
            // Colapsable: por defecto expandida si hay pocas categorías.
            val expanded = catExpanded ?: (cats.size <= 3)
            val header = sectionHeader("Por categoría")
            val chev = iconView(R.drawable.ic_chevron_down, col(R.color.inkSoft), 16)
            chev.rotation = if (expanded) 0f else -90f
            header.addView(chev, 1)
            (header.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = WRAP; weight = 0f; rightMargin = dp(4) }
            header.setOnClickListener { catExpanded = !expanded; renderResumen() }
            resumenView.addView(header)
            if (expanded) {
                val cl = cardBox(radiusDp = 18)
                cl.setPadding(dp(16), dp(4), dp(16), dp(4))
                for ((i, entry) in cats.entries.sortedByDescending { it.value }.withIndex()) {
                    val c = entry.key
                    val name = Store.categoryById(this, c)?.optString("name", "") ?: "General"
                    // Mismo color que en el gráfico apilado: sirve de leyenda.
                    val row = statRow(name, catColorsAll[c] ?: col(R.color.indigo), entry.value, total, first = i == 0)
                    row.setOnClickListener { statsCategory = c; renderResumen() }
                    cl.addView(row)
                }
                resumenView.addView(cl)
            }
        }
    }

    resumenView.addView(buildExportButton())
}

// Posición de "hoy" dentro de un período que arranca en from (para resaltar su barra),
// o -1 si hoy no cae ahí.
private fun todayIndex(from: Long, count: Int): Int {
    val i = Math.round((Store.startOfToday() - from).toDouble() / DAY_MS).toInt()
    return if (i in 0 until count) i else -1
}

// Color de cada categoría (propiedad propia de la categoría ahora, no de la primera
// actividad). Se usa como "leyenda" consistente entre el selector, el gráfico apilado
// y la lista colapsable — el mismo color en todos lados identifica cada una.
internal fun MainActivity.categoryColors(acts: List<JSONObject>): LinkedHashMap<String, Int> {
    val map = LinkedHashMap<String, Int>()
    for (a in acts) {
        val cat = Store.categoryForActivity(this, a)
        val id = cat.getString("id")
        if (id !in map) map[id] = parseColor(cat.optString("color"))
    }
    return map
}

internal fun MainActivity.buildCategorySelector(cats: List<JSONObject>): View {
    val scroll = android.widget.HorizontalScrollView(this)
    scroll.isHorizontalScrollBarEnabled = false
    scroll.layoutParams = lp(top = 12)
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL

    fun chip(text: String, value: String, dotColor: Int?): View {
        val active = value == statsCategory
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(13), dp(8), dp(13), dp(8))
        c.layoutParams = lp(w = WRAP, right = 8)
        c.background = rounded(if (active) col(R.color.ink) else col(R.color.card), 18)
        if (dotColor != null) {
            val dot = View(this)
            dot.background = oval(dotColor)
            dot.layoutParams = lp(w = dp(8), h = dp(8), right = 7)
            c.addView(dot)
        }
        c.addView(label(text, 13f, if (active) col(R.color.paper) else col(R.color.inkSoft), bold = true))
        c.setOnClickListener { statsCategory = value; renderResumen() }
        return c
    }

    row.addView(chip("Todo", "all", null))
    for (c in cats) row.addView(chip(c.optString("name", "General"), c.getString("id"), parseColor(c.optString("color"))))
    scroll.addView(row)
    return scroll
}

internal fun MainActivity.buildPeriodSelector(): View {
    val seg = LinearLayout(this)
    seg.orientation = LinearLayout.HORIZONTAL
    seg.setPadding(dp(4), dp(4), dp(4), dp(4))
    seg.background = rounded(col(R.color.surface2), 14)
    for ((p, text) in listOf("day" to "Día", "week" to "Semana", "month" to "Mes")) {
        val b = label(text, 14f, col(R.color.inkSoft), bold = true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(6), dp(9), dp(6), dp(9))
        b.layoutParams = lp(w = 0, weight = 1f)
        paintSegment(b, p == statsPeriod)
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

internal fun MainActivity.buildOffsetNav(text: String): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.layoutParams = lp(top = 12)

    fun arrow(res: Int, enabled: Boolean, onClick: () -> Unit): View {
        val b = android.widget.FrameLayout(this)
        b.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        if (enabled) b.background = oval(col(R.color.card))
        val ic = iconView(res, if (enabled) col(R.color.ink) else col(R.color.line), 20)
        ic.layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        b.addView(ic)
        if (enabled) b.setOnClickListener { onClick() }
        return b
    }

    row.addView(arrow(R.drawable.ic_chevron_left, true) { statsOffset -= 1; renderResumen() })
    val t = label(text, 15f, col(R.color.ink), bold = true)
    t.gravity = Gravity.CENTER
    t.layoutParams = lp(w = 0, weight = 1f)
    row.addView(t)
    // No se puede navegar a "el futuro": el offset máximo es 0 (el período actual).
    row.addView(arrow(R.drawable.ic_chevron_right, statsOffset < 0) { statsOffset += 1; renderResumen() })
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

// Una barra del gráfico: etiqueta + segmentos (segundos, color). Una barra simple tiene
// un solo segmento; la vista Semana apila uno por categoría.
internal class Bar(val label: String, val segs: List<Pair<Long, Int>>) {
    val total: Long get() = segs.sumOf { it.first }
}

// dayCount: días a recorrer (parcial hasta hoy si es el mes actual, completo si es un mes pasado).
internal fun MainActivity.monthBars(actIds: Set<String>, monthStart: Long, dayCount: Int): List<Bar> {
    val c = col(R.color.indigo)
    return (0 until dayCount).map { i ->
        val ds = monthStart + i * DAY_MS
        Bar("${i + 1}", listOf(Store.totalBetween(this, ds, ds + DAY_MS, actIds) to c))
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

// Gráfico de barras: cada barra crece desde abajo sobre una "pista" tenue de altura
// completa (así se ve la forma del período aunque haya días vacíos), con las puntas
// redondeadas. La barra de hoy lleva la etiqueta resaltada.
internal fun MainActivity.buildChart(bars: List<Bar>, showValues: Boolean, labelStep: Int, todayIdx: Int): View {
    val chartH = dp(130)
    val dense = bars.size > 12
    val radius = if (dense) 4 else 8
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.HORIZONTAL
    wrap.gravity = Gravity.BOTTOM
    wrap.layoutParams = lp(top = 18)

    val max = (bars.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L)
    for ((idx, b) in bars.withIndex()) {
        val colView = LinearLayout(this)
        colView.orientation = LinearLayout.VERTICAL
        colView.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        val gap = if (dense) 1 else 3
        colView.layoutParams = lp(w = 0, weight = 1f, left = gap, right = gap)

        if (showValues) {
            val v = label(if (b.total > 0) Store.human(b.total) else "", 10f, col(R.color.inkSoft), bold = true, tnum = true, single = true)
            v.gravity = Gravity.CENTER
            v.setPadding(0, 0, 0, dp(4))
            colView.addView(v)
        }

        // Pista (altura completa) con la barra apoyada abajo; clipToOutline recorta los
        // segmentos al contorno redondeado, como el overflow:hidden del espejo web.
        val track = LinearLayout(this)
        track.orientation = LinearLayout.VERTICAL
        track.gravity = Gravity.BOTTOM
        track.background = rounded(col(R.color.grid), radius)
        track.clipToOutline = true
        track.layoutParams = lp(h = chartH)
        if (b.total > 0) {
            val barH = Math.max(dp(4), (b.total.toDouble() / max * chartH).toInt())
            val stack = LinearLayout(this)
            stack.orientation = LinearLayout.VERTICAL
            // Fondo del color de la tarjeta: los 2dp entre segmentos se ven como separador.
            stack.background = rounded(col(R.color.card), radius)
            stack.clipToOutline = true
            stack.layoutParams = lp(h = barH)
            for ((i, seg) in b.segs.withIndex()) {
                val segView = View(this)
                segView.setBackgroundColor(seg.second)
                val h = Math.max(dp(1), (seg.first.toDouble() / b.total * barH).toInt())
                segView.layoutParams = lp(h = h, top = if (i > 0) 2 else 0)
                stack.addView(segView)
            }
            track.addView(stack)
        }
        colView.addView(track)

        val isToday = idx == todayIdx
        val n = label(if (idx % labelStep == 0 || isToday) b.label else "", 11f,
            if (isToday) col(R.color.ink) else col(R.color.muted), bold = isToday, single = true)
        n.gravity = Gravity.CENTER
        n.setPadding(0, dp(6), 0, 0)
        colView.addView(n)
        wrap.addView(colView)
    }
    return wrap
}

// Fila de un desglose (dentro de una tarjeta-lista): punto, nombre, tiempo, porcentaje y
// una barra fina de proporción.
internal fun MainActivity.statRow(name: String, colorInt: Int, sec: Long, total: Long, first: Boolean): View {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(0, dp(11), 0, dp(11))
    if (!first) {
        val sep = GradientDrawable()
        sep.setColor(col(R.color.card))
        box.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(col(R.color.line)), sep
        )).also { it.setLayerInset(1, 0, dp(1), 0, 0) }
    }

    val top = LinearLayout(this)
    top.orientation = LinearLayout.HORIZONTAL
    top.gravity = Gravity.CENTER_VERTICAL
    val dot = View(this)
    dot.background = oval(colorInt)
    dot.layoutParams = lp(w = dp(10), h = dp(10), right = 10)
    top.addView(dot)
    val nm = label(name, 14.5f, col(R.color.ink), bold = true, single = true)
    nm.layoutParams = lp(w = 0, weight = 1f, right = 8)
    top.addView(nm)
    top.addView(label(Store.exact(sec), 14f, col(R.color.ink), bold = true, tnum = true))
    val pct = if (total > 0) Math.round(sec * 100.0 / total).toInt() else 0
    val pv = label("$pct%", 12.5f, col(R.color.muted), tnum = true)
    pv.gravity = Gravity.END
    pv.layoutParams = lp(w = dp(42))
    top.addView(pv)
    box.addView(top)

    val track = LinearLayout(this)
    track.orientation = LinearLayout.HORIZONTAL
    track.layoutParams = lp(h = dp(6), top = 8)
    track.background = rounded(col(R.color.grid), 3)
    val fill = View(this)
    fill.background = rounded(colorInt, 3)
    val rest = View(this)
    val safeTotal = total.coerceAtLeast(1L)
    fill.layoutParams = lp(w = 0, h = MATCH, weight = sec.toFloat())
    rest.layoutParams = lp(w = 0, h = MATCH, weight = (safeTotal - sec).coerceAtLeast(0L).toFloat())
    track.weightSum = safeTotal.toFloat()
    track.addView(fill)
    track.addView(rest)
    box.addView(track)
    return box
}

internal fun MainActivity.buildExportButton(): View {
    val b = LinearLayout(this)
    b.orientation = LinearLayout.HORIZONTAL
    b.gravity = Gravity.CENTER
    b.setPadding(dp(14), dp(14), dp(14), dp(14))
    b.background = withRipple(rounded(col(R.color.card), 16), dp(16).toFloat())
    b.layoutParams = lp(top = 18)
    b.addView(iconView(R.drawable.ic_download, col(R.color.indigo), 18))
    val t = label("Exportar", 14f, col(R.color.indigo), bold = true)
    t.setPadding(dp(8), 0, 0, 0)
    b.addView(t)
    b.setOnClickListener { openExportDialog() }
    return b
}

private fun MainActivity.openExportDialog() {
    val (rFrom, rTo) = Store.periodRange(statsPeriod, statsOffset)
    val periodTxt = periodLabel(statsPeriod, statsOffset, rFrom, rTo)
    val catName = Store.categoryById(this, statsCategory)?.optString("name", "") ?: statsCategory
    val filterLabel = if (statsCategory == "all") periodTxt else "$periodTxt · $catName"
    val box = dialogBox()
    var dlg: AlertDialog? = null
    fun choice(iconRes: Int, title: String, sub: String, first: Boolean, action: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(14), dp(4), dp(14))
        if (!first) {
            val div = View(this)
            div.setBackgroundColor(col(R.color.line))
            div.layoutParams = lp(h = dp(1))
            box.addView(div)
        }
        row.addView(iconView(iconRes, col(R.color.muted), 22))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.layoutParams = lp(w = 0, weight = 1f, left = 14)
        texts.addView(label(title, 15f, col(R.color.ink)))
        texts.addView(label(sub, 12.5f, col(R.color.muted)))
        row.addView(texts)
        row.setOnClickListener { dlg?.dismiss(); action() }
        box.addView(row)
    }
    choice(R.drawable.ic_table, "Historial completo", "Todas las sesiones, en CSV", true) { exportCsv(filtered = false) }
    choice(R.drawable.ic_filter, "Solo lo filtrado", "$filterLabel, en CSV", false) { exportCsv(filtered = true) }
    choice(R.drawable.ic_save, "Backup completo", "Todos los datos, en JSON", false) { exportBackup() }
    dlg = showStyled(
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Exportar")
            .setView(box)
            .setNegativeButton("Cancelar", null)
    )
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
