package com.bitacora.timer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.Calendar

// Pestaña Resumen: todo lo que importa del período a la vista y arriba (el total, cómo
// viene contra el período anterior y las stats clave); debajo, la forma del período
// (línea de tiempo del día, barras de la semana, calendario del mes), los desgloses y
// exportar (CSV / backup). Espejo de renderResumen en web/index.html.
// Funciones de extensión de MainActivity: comparten su estado (campos internal).

private const val DAY_MS = 86400000L
private const val HOUR_MS = 3600000L
private val WEEKDAYS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
private val PREV_LABEL = mapOf(
    "day" to ("vs. ayer a esta hora" to "vs. el día anterior"),
    "week" to ("vs. la semana pasada a esta altura" to "vs. la semana anterior"),
    "month" to ("vs. el mes pasado a esta altura" to "vs. el mes anterior")
)
private const val CAT_ROW_TAG = "catrow"

internal fun MainActivity.renderResumen() {
    // El filtro de categorías se desplaza en horizontal: se conserva dónde estaba.
    val catScroll = resumenView.findViewWithTag<View>(CAT_ROW_TAG)?.scrollX ?: 0
    resumenView.removeAllViews()
    resumenView.addView(buildPeriodSelector())

    val (from, to) = Store.periodRange(statsPeriod, statsOffset)
    resumenView.addView(buildOffsetNav(periodLabel(statsPeriod, statsOffset, from, to)))

    // Incluye archivadas: su historial sigue contando en las estadísticas.
    val allActs = Store.activities(this, includeArchived = true)
    val catMap = LinkedHashMap<String, JSONObject>()
    for (a in allActs) Store.categoryForActivity(this, a).let { catMap[it.getString("id")] = it }
    val allCats = catMap.values.sortedBy { it.optString("name", "") }
    if (statsCategory != "all" && statsCategory !in catMap) statsCategory = "all"
    if (allCats.size > 1) {
        val row = buildCategorySelector(allCats)
        resumenView.addView(row)
        row.post { row.scrollTo(catScroll, 0) }
    }

    val acts = if (statsCategory == "all") allActs
        else allActs.filter { Store.categoryForActivity(this, it).getString("id") == statsCategory }
    val actIds = acts.map { it.getString("id") }.toSet()

    // Una sola pasada por el historial para todo el período; el resto sale de acá.
    val secs = Store.secsByActivity(this, from, to)
    val perAct = acts.map { it to (secs[it.getString("id")] ?: 0L) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val total = perAct.sumOf { it.second }

    // Días del período que ya pasaron (o todos, si es un período pasado): base del
    // promedio y de qué días se dibujan como "todavía no".
    val dayCount = Math.round((to - from).toDouble() / DAY_MS).toInt()
    val elapsedDays = if (statsOffset == 0) Math.round((Store.startOfToday() - from).toDouble() / DAY_MS).toInt() + 1 else dayCount
    val days = if (statsPeriod == "day") null else dailyTotals(from, dayCount, actIds)

    val card = cardBox(radiusDp = 22)
    card.setPadding(dp(18), dp(18), dp(18), dp(16))
    card.layoutParams = lp(top = 14)
    val catName = catMap[statsCategory]?.optString("name", "") ?: ""
    card.addView(label(if (statsCategory == "all") "Total" else "Total · $catName", 13f, col(R.color.muted), bold = true))
    card.addView(bigTime(total))
    comparisonLine(total, from, actIds)?.let { card.addView(it) }
    resumenView.addView(card)

    if (statsPeriod == "day") {
        val sessions = Store.sessionsBetween(this, from, to).filter { it.first in actIds }
        if (sessions.isNotEmpty()) {
            card.addView(buildKpis(dayKpis(sessions), listOf(0.85f, 1f, 1.3f)))
            val colors = allActs.associate { it.getString("id") to parseColor(it.optString("color")) }
            val tl = TimelineView(this, sessions.map { Triple(it.second, it.third, colors[it.first] ?: col(R.color.indigo)) })
            tl.layoutParams = lp(h = dp(52), top = 18)
            card.addView(tl)
        } else {
            val e = label("Sin registros en este día.", 14f, col(R.color.muted))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(18), 0, dp(4))
            card.addView(e)
        }
    } else {
        card.addView(buildKpis(rangeKpis(days!!, elapsedDays, from)))
        if (statsPeriod == "week") card.addView(buildWeekChart(from, acts, elapsedDays, days, total.toDouble() / elapsedDays))
        else card.addView(buildMonthCalendar(from, days, elapsedDays))
    }

    if (perAct.isNotEmpty()) {
        resumenView.addView(sectionHeader("Por actividad"))
        val list = listCard()
        for ((i, pair) in perAct.withIndex()) {
            val a = pair.first
            val color = parseColor(a.optString("color"))
            val sw = swatch(color, Store.categoryForActivity(this, a).optString("icon", ""), a.getString("name"), 34, false)
            list.addView(statRow(sw, a.getString("name"), color, pair.second, total, first = i == 0))
        }
        resumenView.addView(list)
    }

    // Por categoría siempre abierta: es también la leyenda de los colores del gráfico.
    if (statsCategory == "all") {
        val entries = categorySums(allActs, secs).entries.sortedByDescending { it.value }
        if (entries.size > 1 || (entries.size == 1 && statsPeriod == "week")) {
            resumenView.addView(sectionHeader("Por categoría"))
            val cl = listCard()
            for ((i, entry) in entries.withIndex()) {
                val c = entry.key
                val cat = catMap[c] ?: continue
                val name = cat.optString("name", "General")
                val color = parseColor(cat.optString("color"))
                val row = statRow(swatch(color, cat.optString("icon", ""), name, 34, false), name, color, entry.value, total, first = i == 0)
                row.setOnClickListener { statsCategory = c; renderResumen() }
                cl.addView(row)
            }
            resumenView.addView(cl)
        }
    }

    // Lo que no se tocó en el período, con cuándo fue la última vez.
    val idle = acts.filter { !it.optBoolean("archived", false) && (secs[it.getString("id")] ?: 0L) <= 0L }
    if (idle.isNotEmpty()) {
        val last = Store.lastEndByActivity(this)
        val sorted = idle.sortedWith(compareByDescending<JSONObject> { last[it.getString("id")] ?: 0L }
            .thenBy { it.optString("name", "") })
        resumenView.addView(sectionHeader("Sin tiempo este período"))
        val il = listCard()
        for ((i, a) in sorted.withIndex()) {
            val box = rowBox(first = i == 0)
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val sw = swatch(parseColor(a.optString("color")), Store.categoryForActivity(this, a).optString("icon", ""), a.getString("name"), 34, false)
            sw.alpha = 0.6f
            (sw.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
            top.addView(sw)
            val nm = label(a.getString("name"), 14.5f, col(R.color.ink), bold = true, single = true)
            nm.alpha = 0.6f
            nm.layoutParams = lp(w = 0, weight = 1f, right = 8)
            top.addView(nm)
            top.addView(label(lastSeen(last[a.getString("id")]), 12.5f, col(R.color.muted)))
            box.addView(top)
            il.addView(box)
        }
        resumenView.addView(il)
    }

    resumenView.addView(buildExportButton())
}

// "56 h 05 m" grande, con las unidades en chico y apagadas.
private fun MainActivity.bigTime(sec: Long): TextView {
    val sb = SpannableStringBuilder()
    fun unit(u: String) {
        val s = sb.length
        sb.append(u)
        sb.setSpan(RelativeSizeSpan(0.5f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(col(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val h = sec / 3600
    val m = (sec % 3600) / 60
    when {
        h > 0 -> { sb.append("$h"); unit("h"); sb.append(" " + m.toString().padStart(2, '0')); unit("m") }
        m > 0 || sec == 0L -> { sb.append("$m"); unit("m") }
        else -> { sb.append("$sec"); unit("s") }
    }
    val t = label(sb, 44f, col(R.color.ink), bold = true, tnum = true)
    t.letterSpacing = -0.03f
    t.includeFontPadding = false
    t.setPadding(0, dp(6), 0, 0)
    return t
}

// Duración legible para las líneas de texto: "1 h 10 m", "25 m", "40 s".
// "56 h 05 m": el formato del total del Resumen (app y widget).
internal fun spaced(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> if (m > 0) "$h h ${m.toString().padStart(2, '0')} m" else "$h h"
        m > 0 -> "$m m"
        else -> "$sec s"
    }
}

// Contra el período anterior. Si el actual está en curso, contra el MISMO tramo del
// anterior (lunes a hoy a esta hora vs. lunes a ese día a esta hora): si no, la
// comparación siempre da "menos" hasta que termine. Subir es verde; bajar es apagado,
// nunca rojo: no es un error. Devuelve el texto y el signo (1 sube, -1 baja, 0 igual),
// o null si no hay nada que comparar. La usan el Resumen y el widget Resumen.
internal fun comparison(ctx: Context, period: String, offset: Int, from: Long, total: Long, actIds: Set<String>?): Pair<String, Int>? {
    val (pf, pt) = Store.periodRange(period, offset - 1)
    val live = offset == 0
    val prevTo = if (live) minOf(pt, pf + (Store.now() - from)) else pt
    val prev = Store.totalBetween(ctx, pf, prevTo, actIds)
    if (total == 0L && prev == 0L) return null
    val labels = PREV_LABEL[period] ?: return null
    val text = if (live) labels.first else labels.second
    val diff = total - prev
    return when {
        Math.abs(diff) < 60 -> ("= Igual que " + text.removePrefix("vs. ")) to 0
        diff > 0 -> "▲ ${spaced(diff)} $text" to 1
        else -> "▼ ${spaced(-diff)} $text" to -1
    }
}

private fun MainActivity.comparisonLine(total: Long, from: Long, actIds: Set<String>): View? {
    val (text, sign) = comparison(this, statsPeriod, statsOffset, from, total, actIds) ?: return null
    val t = label(text, 13.5f, col(if (sign > 0) R.color.up else R.color.muted), bold = true)
    t.setPadding(0, dp(6), 0, 0)
    return t
}

// weights: ancho relativo de cada una (el Día le da más a "de · hasta", que es la más larga).
private fun MainActivity.buildKpis(items: List<Pair<String, String>>, weights: List<Float> = items.map { 1f }): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.layoutParams = lp(top = 14)
    for ((i, item) in items.withIndex()) {
        val k = LinearLayout(this)
        k.orientation = LinearLayout.VERTICAL
        k.background = rounded(col(R.color.surface2), 14)
        k.setPadding(dp(11), dp(10), dp(11), dp(10))
        k.layoutParams = lp(w = 0, weight = weights[i], left = if (i > 0) 8 else 0)
        k.addView(label(item.first, 16f, col(R.color.ink), bold = true, tnum = true, single = true))
        k.addView(label(item.second, 12f, col(R.color.muted)))
        row.addView(k)
    }
    return row
}

private fun hhmm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

private fun dayKpis(sessions: List<Triple<String, Long, Long>>): List<Pair<String, String>> {
    val longest = sessions.maxOf { it.third - it.second }
    val first = sessions.first().second
    val last = sessions.maxOf { it.third }
    return listOf(
        "${sessions.size}" to (if (sessions.size == 1) "sesión" else "sesiones"),
        Store.human(longest / 1000) to "la más larga",
        "${hhmm(first)}–${hhmm(last)}" to "de · hasta"
    )
}

private fun MainActivity.rangeKpis(days: List<Long>, elapsedDays: Int, from: Long): List<Pair<String, String>> {
    val passed = days.take(elapsedDays)
    val total = passed.sum()
    val active = passed.count { it > 0 }
    var best = 0
    for (i in passed.indices) if (passed[i] > passed[best]) best = i
    val bestLabel = if (statsPeriod == "week") WEEKDAYS[best].lowercase() else {
        val c = Calendar.getInstance()
        c.timeInMillis = dayStart(from, best)
        "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}"
    }
    return listOf(
        (if (total > 0) Store.human(total / elapsedDays) else "0m") to "promedio por día",
        "$active de $elapsedDays" to "días con actividad",
        if (passed[best] > 0) Store.human(passed[best]) to "mejor día · $bestLabel" else "—" to "mejor día"
    )
}

// Medianoche del día i contando desde from (respeta los cambios de horario).
private fun dayStart(from: Long, i: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = from
    c.add(Calendar.DAY_OF_MONTH, i)
    return startOfDay(c.timeInMillis)
}

private fun startOfDay(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

// Total de cada día del período (filtrado por actividades).
private fun MainActivity.dailyTotals(from: Long, count: Int, actIds: Set<String>): List<Long> =
    (0 until count).map { Store.totalBetween(this, dayStart(from, it), dayStart(from, it + 1), actIds) }

private fun lastSeen(ms: Long?): String {
    if (ms == null || ms <= 0L) return "Nunca"
    val d = Math.round((Store.startOfToday() - startOfDay(ms)).toDouble() / DAY_MS).toInt()
    return when {
        d <= 0 -> "Hoy"
        d == 1 -> "Ayer"
        else -> "hace $d días"
    }
}

// Offset (≤ 0) del período que contiene el instante ms.
internal fun offsetFor(period: String, ms: Long): Int {
    val t = Calendar.getInstance()
    val d = Calendar.getInstance().also { it.timeInMillis = ms }
    if (period == "month") return minOf(0, (d.get(Calendar.YEAR) - t.get(Calendar.YEAR)) * 12 + d.get(Calendar.MONTH) - t.get(Calendar.MONTH))
    val cur = Store.periodRange(period, 0).first
    val target = startOfDay(ms)
    if (period == "day") return minOf(0, Math.round((target - cur).toDouble() / DAY_MS).toInt())
    return minOf(0, Math.floor((target - cur).toDouble() / (7 * DAY_MS) + 0.02).toInt())
}

// Semana: una barra por día, apilada por categoría (o, con un filtro de categoría, por
// actividad de esa categoría), techo en horas enteras y la línea del promedio. Cada
// barra crece desde abajo sobre una pista de altura completa; los días que todavía no
// llegaron quedan solo contorneados.
private fun MainActivity.buildWeekChart(from: Long, acts: List<JSONObject>, elapsedDays: Int, days: List<Long>, avg: Double): View {
    val byCat = statsCategory == "all"
    fun keyOf(a: JSONObject) = if (byCat) Store.categoryForActivity(this, a).getString("id") else a.getString("id")
    val colorOf = HashMap<String, Int>()
    for (a in acts) {
        val k = keyOf(a)
        if (k !in colorOf) colorOf[k] = parseColor(if (byCat) Store.categoryForActivity(this, a).optString("color") else a.optString("color"))
    }
    val weekSum = HashMap<String, Long>()
    val perDay = (0..6).map { i ->
        val s = Store.secsByActivity(this, dayStart(from, i), dayStart(from, i + 1))
        val m = HashMap<String, Long>()
        for (a in acts) {
            val t = s[a.getString("id")] ?: 0L
            if (t > 0) { val k = keyOf(a); m[k] = (m[k] ?: 0L) + t; weekSum[k] = (weekSum[k] ?: 0L) + t }
        }
        m
    }
    // Orden fijo (por total de la semana): el mismo color queda en la misma posición.
    val order = weekSum.entries.sortedByDescending { it.value }.map { it.key }
    val ceil = maxOf(3600L, Math.ceil((days.maxOrNull() ?: 0L) / 3600.0).toLong() * 3600L)
    val todayIdx = if (statsOffset == 0) elapsedDays - 1 else -1
    val chartH = dp(140)
    val radius = 8

    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.VERTICAL
    wrap.layoutParams = lp(top = 18)
    fun row() = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL }
    val vals = row()
    val bars = row()
    val labs = row()
    for ((i, t) in days.withIndex()) {
        val v = label(if (t > 0) Store.human(t) else "", 10f, col(R.color.inkSoft), bold = true, tnum = true, single = true)
        v.gravity = Gravity.CENTER
        v.layoutParams = lp(w = 0, weight = 1f, left = 3, right = 3, bottom = 4)
        vals.addView(v)

        // Pista con la barra apoyada abajo; clipToOutline recorta los segmentos al
        // contorno redondeado, como el overflow:hidden del espejo web.
        val track = LinearLayout(this)
        track.orientation = LinearLayout.VERTICAL
        track.gravity = Gravity.BOTTOM
        track.background = if (i >= elapsedDays) GradientDrawable().also {
            it.cornerRadius = dp(radius).toFloat(); it.setStroke(Math.max(1, dp(1) * 3 / 2), col(R.color.grid))
        } else rounded(col(R.color.grid), radius)
        track.clipToOutline = true
        track.layoutParams = lp(w = 0, h = chartH, weight = 1f, left = 3, right = 3)
        if (t > 0) {
            val barH = maxOf(dp(4), (t.toDouble() / ceil * chartH).toInt())
            val stack = LinearLayout(this)
            stack.orientation = LinearLayout.VERTICAL
            // Fondo del color de la tarjeta: los 2dp entre segmentos se ven como separador.
            stack.background = rounded(col(R.color.card), radius)
            stack.clipToOutline = true
            stack.layoutParams = lp(h = barH)
            var n = 0
            for (k in order) {
                val sec = perDay[i][k] ?: continue
                val seg = View(this)
                seg.setBackgroundColor(colorOf[k] ?: col(R.color.indigo))
                seg.layoutParams = lp(h = 0, weight = sec.toFloat(), top = if (n++ > 0) 2 else 0)
                stack.addView(seg)
            }
            track.addView(stack)
        }
        bars.addView(track)

        val isToday = i == todayIdx
        val n = label(WEEKDAYS[i], 11f, if (isToday) col(R.color.ink) else col(R.color.muted), bold = isToday, single = true)
        n.gravity = Gravity.CENTER
        n.layoutParams = lp(w = 0, weight = 1f, top = 6)
        labs.addView(n)
    }
    val plot = FrameLayout(this)
    plot.layoutParams = lp(h = chartH)
    plot.addView(bars, FrameLayout.LayoutParams(MATCH, chartH))
    if (avg >= 60) {
        val g = GuideView(this, (avg / ceil).toFloat(), "prom. " + Store.human(avg.toLong()))
        plot.addView(g, FrameLayout.LayoutParams(MATCH, chartH))
    }
    wrap.addView(vals)
    wrap.addView(plot)
    wrap.addView(labs)
    return wrap
}

// Mes: calendario L–D; la intensidad del acento dice cuánto se hizo ese día (0, <1 h,
// 1–2 h, 2–4 h, 4 h+). Tocar un día lleva a su vista de Día.
private fun MainActivity.buildMonthCalendar(from: Long, days: List<Long>, elapsedDays: Int): View {
    val indigo = col(R.color.indigo)
    val card = col(R.color.card)
    fun levelColor(t: Long): Int? = when {
        t <= 0 -> null
        t < HOUR_MS / 1000 -> blend(indigo, card, 0.28f)
        t < 2 * HOUR_MS / 1000 -> blend(indigo, card, 0.50f)
        t < 4 * HOUR_MS / 1000 -> blend(indigo, card, 0.75f)
        else -> indigo
    }
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.VERTICAL
    wrap.layoutParams = lp(top = 16)

    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    for (d in WEEKDAYS) {
        val t = label(d.take(1), 11f, col(R.color.muted), bold = true)
        t.gravity = Gravity.CENTER
        t.layoutParams = lp(w = 0, weight = 1f, left = 3, right = 3)
        head.addView(t)
    }
    wrap.addView(head)

    val c = Calendar.getInstance().also { it.timeInMillis = from }
    val lead = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val todayIdx = if (statsOffset == 0) elapsedDays - 1 else -1
    var week: LinearLayout? = null
    for (cell in 0 until lead + days.size) {
        if (cell % 7 == 0) {
            week = LinearLayout(this)
            week.orientation = LinearLayout.HORIZONTAL
            week.layoutParams = lp(top = 6)
            wrap.addView(week)
        }
        val i = cell - lead
        val box = SquareText(this)
        box.layoutParams = lp(w = 0, weight = 1f, left = 3, right = 3)
        if (i >= 0) {
            val t = days[i]
            val future = i >= elapsedDays
            val fill = if (future) null else levelColor(t)
            box.text = String.format(java.util.Locale.ROOT, "%d", i + 1)
            box.textSize = 12.5f
            box.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            box.fontFeatureSettings = "tnum"
            box.gravity = Gravity.CENTER
            box.setTextColor(when {
                future -> col(R.color.muted)
                t >= 2 * HOUR_MS / 1000 -> col(R.color.onAccent)
                fill != null -> col(R.color.ink)
                else -> col(R.color.inkSoft)
            })
            val bg = GradientDrawable()
            bg.cornerRadius = dp(10).toFloat()
            when {
                future -> bg.setStroke(Math.max(1, dp(1) * 3 / 2), col(R.color.grid))
                else -> bg.setColor(fill ?: col(R.color.grid))
            }
            if (i == todayIdx) bg.setStroke(dp(2), col(R.color.ink))
            box.background = bg
            if (!future) {
                val ds = dayStart(from, i)
                box.setOnClickListener { statsPeriod = "day"; statsOffset = offsetFor("day", ds); renderResumen() }
            }
        }
        week!!.addView(box)
    }
    // Las filas incompletas se completan con celdas vacías para que no se estiren.
    val rest = (7 - (lead + days.size) % 7) % 7
    repeat(rest) { week!!.addView(View(this), lp(w = 0, h = 1, weight = 1f, left = 3, right = 3)) }

    val legend = LinearLayout(this)
    legend.orientation = LinearLayout.HORIZONTAL
    legend.gravity = Gravity.CENTER_VERTICAL or Gravity.END
    legend.layoutParams = lp(top = 12)
    legend.addView(label("0", 11f, col(R.color.muted)))
    for (lv in listOf(col(R.color.grid), blend(indigo, card, 0.28f), blend(indigo, card, 0.5f), blend(indigo, card, 0.75f), indigo)) {
        val sq = View(this)
        sq.background = rounded(lv, 3)
        sq.layoutParams = lp(w = dp(12), h = dp(12), left = 5)
        legend.addView(sq)
    }
    legend.addView(label("4 h+", 11f, col(R.color.muted)).also { it.setPadding(dp(5), 0, 0, 0) })
    wrap.addView(legend)
    return wrap
}

internal fun MainActivity.buildCategorySelector(cats: List<JSONObject>): View {
    val scroll = HorizontalScrollView(this)
    scroll.tag = CAT_ROW_TAG
    scroll.isHorizontalScrollBarEnabled = false
    scroll.layoutParams = lp(top = 12)
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL

    fun chip(text: String, value: String, cat: JSONObject?): View {
        val active = value == statsCategory
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(13), dp(8), dp(13), dp(8))
        c.layoutParams = lp(w = WRAP, right = 8)
        c.background = rounded(if (active) col(R.color.ink) else col(R.color.card), 18)
        if (cat != null) {
            val color = parseColor(cat.optString("color"))
            val res = CategoryIcons.resOf(cat.optString("icon", ""))
            if (res != null) {
                val ic = iconView(res, color, 15)
                (ic.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(7)
                c.addView(ic)
            } else {
                val dot = View(this)
                dot.background = oval(color)
                dot.layoutParams = lp(w = dp(8), h = dp(8), right = 7)
                c.addView(dot)
            }
        }
        c.addView(label(text, 13f, if (active) col(R.color.paper) else col(R.color.inkSoft), bold = true))
        c.setOnClickListener { statsCategory = value; renderResumen() }
        return c
    }

    row.addView(chip("Todo", "all", null))
    for (c in cats) row.addView(chip(c.optString("name", "General"), c.getString("id"), c))
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
        b.setOnClickListener {
            // Se mantiene la fecha que se estaba mirando: de "Semana del 6" a "Mes" va a ese mes.
            val anchor = if (statsOffset == 0) Store.now() else Store.periodRange(statsPeriod, statsOffset).first
            statsPeriod = p; statsOffset = offsetFor(p, anchor); renderResumen()
        }
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
    val away = statsOffset != 0

    // Las dos flechas iguales (44dp); la que no se puede usar queda tenue.
    fun arrow(res: Int, enabled: Boolean, onClick: () -> Unit): View {
        val b = FrameLayout(this)
        b.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        b.background = oval(col(R.color.card))
        b.alpha = if (enabled) 1f else 0.35f
        val ic = iconView(res, col(R.color.ink), 20)
        ic.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        b.addView(ic)
        if (enabled) b.setOnClickListener { onClick() }
        return b
    }

    row.addView(arrow(R.drawable.ic_chevron_left, true) { statsOffset -= 1; renderResumen() })
    val mid = LinearLayout(this)
    mid.orientation = LinearLayout.VERTICAL
    mid.gravity = Gravity.CENTER
    mid.layoutParams = lp(w = 0, weight = 1f)
    mid.addView(label(text, 15f, col(R.color.ink), bold = true).also { it.gravity = Gravity.CENTER })
    if (away) {
        // Tocar la fecha vuelve al período actual.
        mid.addView(label("Volver a hoy", 12f, col(R.color.indigo), bold = true).also { it.gravity = Gravity.CENTER })
        mid.setOnClickListener { statsOffset = 0; renderResumen() }
    }
    row.addView(mid)
    // No se puede navegar a "el futuro": el offset máximo es 0 (el período actual).
    row.addView(arrow(R.drawable.ic_chevron_right, away) { statsOffset += 1; renderResumen() })
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

private fun MainActivity.listCard(): LinearLayout {
    val c = cardBox(radiusDp = 18)
    c.setPadding(dp(16), dp(2), dp(16), dp(2))
    return c
}

// Fila de una tarjeta-lista, con la línea divisoria arriba salvo en la primera.
private fun MainActivity.rowBox(first: Boolean): LinearLayout {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(0, dp(10), 0, dp(10))
    if (!first) {
        val sep = GradientDrawable()
        sep.setColor(col(R.color.card))
        box.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(col(R.color.line)), sep
        )).also { it.setLayerInset(1, 0, dp(1), 0, 0) }
    }
    return box
}

// Fila de un desglose: swatch de identidad, nombre, tiempo, porcentaje y una barra fina
// de proporción (alineada con el nombre, no con el swatch).
internal fun MainActivity.statRow(swatch: View, name: String, colorInt: Int, sec: Long, total: Long, first: Boolean): View {
    val box = rowBox(first)
    val top = LinearLayout(this)
    top.orientation = LinearLayout.HORIZONTAL
    top.gravity = Gravity.CENTER_VERTICAL
    (swatch.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
    top.addView(swatch)
    val nm = label(name, 14.5f, col(R.color.ink), bold = true, single = true)
    nm.layoutParams = lp(w = 0, weight = 1f, right = 8)
    top.addView(nm)
    top.addView(label(Store.human(sec), 14f, col(R.color.ink), bold = true, tnum = true))
    val pct = if (total > 0) Math.round(sec * 100.0 / total).toInt() else 0
    val pv = label("$pct%", 12.5f, col(R.color.muted), tnum = true)
    pv.gravity = Gravity.END
    pv.layoutParams = lp(w = dp(42))
    top.addView(pv)
    box.addView(top)

    val track = LinearLayout(this)
    track.orientation = LinearLayout.HORIZONTAL
    track.layoutParams = lp(h = dp(6), top = 6, left = 46)
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

// Celda cuadrada del calendario (alto = ancho).
@SuppressLint("ViewConstructor", "AppCompatCustomView")
private class SquareText(ctx: Context) : TextView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

// Línea punteada del promedio sobre el gráfico de la semana, con su etiqueta a la derecha.
@SuppressLint("ViewConstructor")
private class GuideView(private val act: MainActivity, private val fraction: Float, private val text: String) : View(act) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeWidth = act.dp(1) * 1.5f
        it.color = act.col(R.color.muted)
        it.pathEffect = DashPathEffect(floatArrayOf(act.dp(4).toFloat(), act.dp(3).toFloat()), 0f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = act.col(R.color.inkSoft)
        it.textSize = 10.5f * act.resources.displayMetrics.scaledDensity
        it.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        it.fontFeatureSettings = "tnum"
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = act.col(R.color.card) }
    private val path = Path()
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val y = height * (1 - fraction.coerceIn(0f, 1f))
        path.rewind()
        path.moveTo(0f, y); path.lineTo(width.toFloat(), y)
        canvas.drawPath(path, line)
        val pad = act.dp(4).toFloat()
        val w = txt.measureText(text)
        val base = maxOf(y - pad, -txt.ascent())
        box.set(width - w - 2 * pad, base + txt.ascent() - pad / 2, width.toFloat(), base + txt.descent() + pad / 2)
        canvas.drawRoundRect(box, pad, pad, bg)
        canvas.drawText(text, width - w - pad, base, txt)
    }
}

// Día: un bloque por sesión sobre la franja horaria con actividad (en horas enteras,
// mínimo 4, múltiplo del paso de las marcas para que la última caiga en el borde).
@SuppressLint("ViewConstructor")
private class TimelineView(private val act: MainActivity, private val blocks: List<Triple<Long, Long, Int>>) : View(act) {
    private val start: Long
    private val hours: Int
    private val step: Int
    private val ticks: List<Pair<Int, String>>
    init {
        val c = Calendar.getInstance()
        c.timeInMillis = blocks.first().first
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        start = c.timeInMillis
        val lastEnd = blocks.maxOf { it.second }
        var h = maxOf(4, Math.ceil((lastEnd - start).toDouble() / HOUR_MS).toInt())
        step = when { h <= 6 -> 1; h <= 12 -> 2; h <= 18 -> 3; else -> 4 }
        h = ((h + step - 1) / step) * step
        hours = h
        ticks = (0..hours step step).map { i ->
            c.timeInMillis = start + i * HOUR_MS
            i to c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0') + ":00"
        }
    }
    private val clip = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = act.col(R.color.muted)
        it.textSize = 11f * act.resources.displayMetrics.scaledDensity
        it.fontFeatureSettings = "tnum"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val trackH = act.dp(30).toFloat()
        val span = hours * HOUR_MS.toFloat()
        rect.set(0f, 0f, w, trackH)
        clip.rewind()
        clip.addRoundRect(rect, act.dp(9).toFloat(), act.dp(9).toFloat(), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.color = act.col(R.color.grid)
        canvas.drawRect(0f, 0f, w, trackH, fill)
        val r = act.dp(5).toFloat()
        for ((s, e, color) in blocks) {
            val x0 = (s - start) / span * w
            val x1 = maxOf(x0 + act.dp(3), (e - start) / span * w)
            fill.color = color
            rect.set(x0, 0f, x1, trackH)
            canvas.drawRoundRect(rect, r, r, fill)
        }
        canvas.restore()
        val base = trackH + act.dp(6) - txt.ascent()
        for ((i, label) in ticks) {
            val tw = txt.measureText(label)
            val x = i.toFloat() / hours * w
            val left = when (i) { 0 -> 0f; hours -> x - tw; else -> x - tw / 2 }
            canvas.drawText(label, left, base, txt)
        }
    }
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
