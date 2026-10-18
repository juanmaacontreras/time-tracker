package com.bitacora.timer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

// Pestaña Cronómetro: lista de actividades, banner de la sesión en curso y panel de sesiones de hoy.
// Funciones de extensión de MainActivity: comparten su estado (campos internal).

internal fun MainActivity.renderTimer() {
    todayViews.clear()
    list.removeAllViews()
    val runId = Store.runningActId(this)
    val allWithArchived = Store.activities(this, includeArchived = true)
    val hasArchived = allWithArchived.any { it.optBoolean("archived", false) }

    // Orden por tiempo dedicado hoy (descendente), igual criterio que los widgets.
    val today = Store.secsByActivity(this, Store.startOfToday())
    val byToday = allWithArchived.sortedByDescending { today[it.getString("id")] ?: 0L }
    val acts = byToday.filter { !it.optBoolean("archived", false) }

    if (hasArchived) list.addView(buildArchivedToggle())

    for (act in acts) list.addView(buildRow(act, runId, today[act.getString("id")] ?: 0L))
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
        val archivedActs = byToday.filter { it.optBoolean("archived", false) }
        if (archivedActs.isNotEmpty()) {
            list.addView(sectionLabel("ARCHIVADAS"))
            for (act in archivedActs) list.addView(buildRow(act, runId, today[act.getString("id")] ?: 0L))
        }
    }
    renderBanner(runId)
}

internal fun MainActivity.buildArchivedToggle(): View {
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

internal fun MainActivity.renderBanner(runId: String) {
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
        bannerStop.setTextColor(col(R.color.live))  // sigue el tema del perfil
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

internal fun MainActivity.buildRow(act: JSONObject, runId: String, todaySecs: Long): View {
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
    row.background = bg

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
    val typeText = Store.categoryForActivity(this, act).optString("name", "").uppercase()
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
    today.text = Store.exact(todaySecs)
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
    // El ripple solo aparece si el toque se sostiene un instante — así un tap corto
    // (arrancar/parar) queda limpio, y mantener presionado (editar) sí se ve reaccionar.
    val rippleBg = withRipple(bg, dp(12).toFloat())
    var downX = 0f
    var downY = 0f
    val holdRunnable = Runnable {
        row.background = rippleBg
        rippleBg.setHotspot(downX, downY)
        rippleBg.state = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
    }
    row.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                handler.postDelayed(holdRunnable, 150)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
            }
        }
        false
    }
    container.addView(row)
    if (expanded) container.addView(buildSessionsPanel(act))
    return container
}

internal fun MainActivity.buildSessionsPanel(act: JSONObject): View {
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

internal fun MainActivity.buildAddCard(): View {
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

internal fun MainActivity.updateRunningRow() {
    if (tab != "timer") return
    val runId = Store.runningActId(this)
    if (runId.isEmpty() || Store.runningPaused(this)) return
    todayViews[runId]?.text = Store.exact(Store.secsFor(this, runId, Store.startOfToday()))
}
