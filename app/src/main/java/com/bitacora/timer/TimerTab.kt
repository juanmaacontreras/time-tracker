package com.bitacora.timer

import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

// Pestaña Cronómetro: tarjeta de lo que corre, lista de actividades y entradas de hoy.
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

    list.addView(
        if (hasArchived) sectionHeader("Actividades", if (showArchived) "Ocultar archivadas" else "Ver archivadas") {
            showArchived = !showArchived; renderTimer()
        } else sectionHeader("Actividades")
    )
    for (act in acts) list.addView(buildRow(act, runId, today[act.getString("id")] ?: 0L))
    if (acts.isEmpty() && !hasArchived) {
        val hint = label("Todavía no tenés actividades. Creá la primera acá abajo.", 14f, col(R.color.muted))
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(12), 0, dp(6))
        list.addView(hint)
    }
    list.addView(wideButton("Nueva actividad", R.drawable.ic_plus) { openActivitySheet(null) })

    if (showArchived) {
        val archivedActs = byToday.filter { it.optBoolean("archived", false) }
        if (archivedActs.isNotEmpty()) {
            list.addView(sectionHeader("Archivadas"))
            for (act in archivedActs) list.addView(buildRow(act, runId, today[act.getString("id")] ?: 0L))
        }
    }
    renderBanner(runId)
}

// Tarjeta de lo que corre (lenguaje de la notificación). Sin nada corriendo muestra el
// total de hoy en el mismo lugar.
internal fun MainActivity.renderBanner(runId: String) {
    heroActions.removeAllViews()
    heroSwatch.removeAllViews()
    val idle = runId.isEmpty()
    heroIdle.visibility = if (idle) View.VISIBLE else View.GONE
    heroTop.visibility = if (idle) View.GONE else View.VISIBLE
    heroHint.visibility = if (idle) View.VISIBLE else View.GONE
    heroActions.visibility = if (idle) View.GONE else View.VISIBLE
    (bannerChrono.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (idle) 6 else 14)
    if (idle) {
        hero.background = rounded(col(R.color.card), 24)
        val total = Store.totalBetween(this, Store.startOfToday(), Long.MAX_VALUE)
        bannerChrono.stop()
        bannerChrono.base = SystemClock.elapsedRealtime() - total * 1000
        bannerChrono.setTextColor(col(R.color.ink))
        return
    }
    val a = Store.activityById(this, runId)
    val paused = Store.runningPaused(this)
    val color = parseColor(a?.optString("color"))
    val cat = a?.let { Store.categoryForActivity(this, it) }

    val heroBg = blend(color, col(R.color.card), 0.11f)
    hero.background = rounded(heroBg, 24)
    heroSwatch.addView(swatch(color, cat?.optString("icon", "") ?: "", a?.optString("name") ?: "", 48, solid = true))

    val stateColor = if (paused) col(R.color.pausedColor) else col(R.color.live)
    heroDot.background = oval(stateColor)
    bannerLabel.text = if (paused) "PAUSADO" else "GRABANDO"
    bannerLabel.setTextColor(stateColor)
    bannerName.text = a?.optString("name") ?: "—"
    heroCat.text = cat?.optString("name", "") ?: ""

    // El chrono siempre se posiciona con el tiempo real corrido (sin pausas).
    bannerChrono.base = SystemClock.elapsedRealtime() - Store.runningElapsedMs(this)
    if (paused) bannerChrono.stop() else bannerChrono.start()
    bannerChrono.setTextColor(if (paused) col(R.color.inkSoft) else col(R.color.ink))

    val pause = pillButton(if (paused) "Seguir" else "Pausar", if (paused) R.drawable.ic_play else R.drawable.ic_pause, primary = true, surface = heroBg) {
        if (Store.runningPaused(this)) Store.resume(this) else Store.pause(this)
        render(); doSync()
    }
    pause.layoutParams = lp(w = 0, weight = 1f, right = 5)
    val stop = pillButton("Parar", R.drawable.ic_stop, primary = false, surface = heroBg) {
        Store.stop(this)
        render(); doSync()
    }
    stop.layoutParams = lp(w = 0, weight = 1f, left = 5)
    heroActions.addView(pause)
    heroActions.addView(stop)
}

internal fun MainActivity.buildRow(act: JSONObject, runId: String, todaySecs: Long): View {
    val id = act.getString("id")
    val running = id == runId
    val paused = running && Store.runningPaused(this)
    val expanded = id in expandedIds
    val archived = act.optBoolean("archived", false)
    val color = parseColor(act.optString("color"))
    val cat = Store.categoryForActivity(this, act)
    val stateColor = if (paused) col(R.color.pausedColor) else col(R.color.live)

    // Tarjeta: la fila y, desplegadas, las entradas de hoy. Corriendo se tiñe con el
    // color de "corriendo" y lleva un anillo, en vez de solo un borde.
    val container = LinearLayout(this)
    container.orientation = LinearLayout.VERTICAL
    container.layoutParams = lp(bottom = 8)
    val bg = if (running) rounded(blend(stateColor, col(R.color.card), 0.12f), 16, 2, stateColor)
             else rounded(col(R.color.card), 16)
    container.background = bg
    if (archived) container.alpha = 0.6f

    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(12), dp(11), dp(4), dp(11))

    row.addView(swatch(color, cat.optString("icon", ""), act.getString("name"), 42, solid = false))

    val mid = LinearLayout(this)
    mid.orientation = LinearLayout.VERTICAL
    mid.layoutParams = lp(w = 0, weight = 1f, left = 12, right = 6)
    val nameView = label(act.getString("name"), 15f, col(R.color.ink), bold = true)
    nameView.maxLines = 2
    nameView.ellipsize = android.text.TextUtils.TruncateAt.END
    mid.addView(nameView)
    val catName = cat.optString("name", "")
    mid.addView(label(if (archived) "$catName · Archivada" else catName, 12.5f, col(R.color.muted), single = true))
    row.addView(mid)

    val today = label(Store.exact(todaySecs), 15f,
        when {
            running -> stateColor
            todaySecs == 0L -> col(R.color.muted)
            else -> col(R.color.ink)
        }, bold = todaySecs > 0 || running, tnum = true)
    today.setPadding(0, 0, dp(8), 0)
    row.addView(today)
    todayViews[id] = today

    // Botón redondo: forma = qué hace tocar la fila (▶ arranca, ■ para); lleno = corriendo.
    val play = android.widget.FrameLayout(this)
    play.layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
    play.background = oval(if (running) stateColor else blend(col(R.color.indigo), col(R.color.card), 0.13f))
    val playIcon = iconView(if (running) R.drawable.ic_stop else R.drawable.ic_play,
        if (running) col(R.color.onAccent) else col(R.color.indigo), 18)
    playIcon.layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
    play.addView(playIcon)
    row.addView(play)

    val chev = iconView(R.drawable.ic_chevron_down, col(R.color.muted), 18)
    chev.rotation = if (expanded) 180f else 0f
    val chevBox = android.widget.FrameLayout(this)
    chevBox.layoutParams = LinearLayout.LayoutParams(dp(30), dp(40))
    chev.layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
    chevBox.addView(chev)
    chevBox.setOnClickListener {
        if (expanded) expandedIds.remove(id) else expandedIds.add(id)
        renderTimer()
    }
    row.addView(chevBox)

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
    val rippleBg = withRipple(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), dp(16).toFloat())
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

// Entradas de hoy, dentro de la misma tarjeta de la fila (alineadas con el nombre).
internal fun MainActivity.buildSessionsPanel(act: JSONObject): View {
    val id = act.getString("id")
    val panel = LinearLayout(this)
    panel.orientation = LinearLayout.VERTICAL
    panel.setPadding(dp(66), 0, dp(12), dp(10))

    val title = label("Entradas de hoy", 12f, col(R.color.muted), bold = true)
    title.setPadding(0, dp(2), 0, dp(4))
    panel.addView(title)

    val sessions = Store.sessionsForActivityToday(this, id)
    if (sessions.isEmpty()) {
        panel.addView(label("Sin entradas cerradas todavía.", 13f, col(R.color.muted)))
        return panel
    }

    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    for ((i, s) in sessions.withIndex()) {
        if (i > 0) {
            val div = View(this)
            div.setBackgroundColor(col(R.color.line))
            div.layoutParams = lp(h = dp(1))
            panel.addView(div)
        }
        val srow = LinearLayout(this)
        srow.orientation = LinearLayout.HORIZONTAL
        srow.gravity = Gravity.CENTER_VERTICAL
        srow.setPadding(0, dp(4), 0, dp(4))

        val range = fmt.format(s.getLong("start")) + " – " + fmt.format(s.getLong("end"))
        val rangeView = label(range, 14f, col(R.color.inkSoft), tnum = true)
        rangeView.layoutParams = lp(w = 0, weight = 1f)
        srow.addView(rangeView)
        val secs = (s.getLong("end") - s.getLong("start")) / 1000
        srow.addView(label(Store.exact(secs), 14f, col(R.color.ink), bold = true, tnum = true))

        val del = android.widget.FrameLayout(this)
        del.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        val x = iconView(R.drawable.ic_close, col(R.color.muted), 16)
        x.layoutParams = android.widget.FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
        del.addView(x)
        del.setOnClickListener {
            showStyled(
                AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("Borrar entrada")
                    .setMessage("Se borra el registro de $range. ¿Seguro?")
                    .setPositiveButton("Borrar") { _, _ ->
                        Store.deleteSession(this, s.getString("id"))
                        renderTimer()
                        doSync()
                    }
                    .setNegativeButton("Cancelar", null),
                dangerPositive = true
            )
        }
        srow.addView(del)
        panel.addView(srow)
    }
    return panel
}

internal fun MainActivity.updateRunningRow() {
    if (tab != "timer") return
    val runId = Store.runningActId(this)
    if (runId.isEmpty() || Store.runningPaused(this)) return
    todayViews[runId]?.text = Store.exact(Store.secsFor(this, runId, Store.startOfToday()))
    updateTodaySub()
}
