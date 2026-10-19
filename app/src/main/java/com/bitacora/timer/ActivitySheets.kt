package com.bitacora.timer

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

// Hojas de alta/edición de actividades y categorías, y confirmación de borrado.
// Funciones de extensión de MainActivity: comparten su estado (campos internal).

internal fun MainActivity.openActivitySheet(existing: JSONObject?) {
    val box = dialogBox()

    val nameIn = EditText(this)
    nameIn.hint = "Nombre (ej. Análisis de circuitos)"
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    nameIn.setText(existing?.optString("name") ?: "")
    styleField(nameIn)
    box.addView(nameIn)

    // ---------------- categoría (selector, no texto libre) ----------------
    box.addView(fieldLabel("Categoría"))

    // Si todavía no hay ninguna categoría (perfil nuevo, o se borró la única que
    // había), el selector queda vacío con "+ Nueva" — nada de sembrar una "General"
    // automática: eso hacía que borrar la única categoría pareciera "recrearse sola".
    var selectedCatId = existing?.let { Store.categoryForActivity(this, it).getString("id") }
        ?: (Store.categories(this).firstOrNull()?.getString("id") ?: "")

    val catScroll = android.widget.HorizontalScrollView(this)
    catScroll.isHorizontalScrollBarEnabled = false
    val catRow = LinearLayout(this)
    catRow.orientation = LinearLayout.HORIZONTAL
    catScroll.addView(catRow)
    box.addView(catScroll)

    fun chipBase(active: Boolean): LinearLayout {
        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setPadding(dp(13), dp(8), dp(13), dp(8))
        chip.layoutParams = lp(w = WRAP, right = 8)
        chip.background = rounded(if (active) col(R.color.ink) else col(R.color.surface2), 18)
        return chip
    }

    fun rebuildCatRow() {
        catRow.removeAllViews()
        for (cat in Store.categories(this)) {
            val id = cat.getString("id")
            val active = id == selectedCatId
            val chip = chipBase(active)
            val catColor = parseColor(cat.optString("color"))
            val resId = CategoryIcons.resOf(cat.optString("icon", ""))
            if (resId != null) {
                val iv = iconView(resId, if (active) col(R.color.paper) else catColor, 15)
                (iv.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(7)
                chip.addView(iv)
            } else {
                val dot = View(this)
                dot.background = oval(catColor)
                dot.layoutParams = lp(w = dp(8), h = dp(8), right = 7)
                chip.addView(dot)
            }
            chip.addView(label(cat.optString("name", "General"), 13.5f, if (active) col(R.color.paper) else col(R.color.inkSoft), bold = true))
            chip.setOnClickListener { selectedCatId = id; rebuildCatRow() }
            chip.setOnLongClickListener {
                openCategorySheet(cat) { keepId -> selectedCatId = keepId; rebuildCatRow() }
                true
            }
            catRow.addView(chip)
        }

        val add = chipBase(false)
        add.background = rounded(blend(col(R.color.indigo), col(R.color.card), 0.13f), 18)
        val plus = iconView(R.drawable.ic_plus, col(R.color.indigo), 15)
        (plus.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(5)
        add.addView(plus)
        add.addView(label("Nueva", 13.5f, col(R.color.indigo), bold = true))
        add.setOnClickListener {
            openCategorySheet(null) { newId -> selectedCatId = newId; rebuildCatRow() }
        }
        catRow.addView(add)
    }
    rebuildCatRow()

    val catHint = label("Mantené presionada una categoría para editarla.", 12f, col(R.color.muted))
    catHint.setPadding(0, dp(8), 0, 0)
    box.addView(catHint)

    box.addView(fieldLabel("Color"))
    var picked = existing?.optString("color")
        ?: Store.COLORS[Store.activities(this).size % Store.COLORS.size]
    val palette = Store.COLORS.map { parseColor(it) }
    box.addView(colorPicker(palette, parseColor(picked)) { c -> picked = Store.COLORS[palette.indexOf(c)] })

    var dlg: AlertDialog? = null

    // Archivar / Desarchivar (solo para actividades existentes).
    if (existing != null) {
        val archived = existing.optBoolean("archived", false)
        val arch = LinearLayout(this)
        arch.orientation = LinearLayout.HORIZONTAL
        arch.gravity = Gravity.CENTER_VERTICAL
        arch.setPadding(0, dp(18), 0, dp(4))
        arch.addView(iconView(R.drawable.ic_archive, col(R.color.inkSoft), 18))
        val t = label(if (archived) "Desarchivar actividad" else "Archivar actividad", 14f, col(R.color.inkSoft), bold = true)
        t.setPadding(dp(8), 0, 0, 0)
        arch.addView(t)
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
        .setView(scrollBox(box))
        .setPositiveButton("Guardar") { _, _ ->
            val name = nameIn.text.toString().trim()
            if (name.isNotEmpty()) {
                if (existing == null) Store.addActivity(this, name, selectedCatId, picked)
                else Store.updateActivity(this, existing.getString("id"), name, selectedCatId, picked)
                render()
                doSync()
            }
        }
        .setNegativeButton("Cancelar", null)
    if (existing != null) {
        builder.setNeutralButton("Borrar") { _, _ -> confirmDelete(existing) }
    }
    dlg = showStyled(builder)
}

// Alta/edición de una categoría (nombre + ícono + color). onDone recibe el id que
// el selector de categorías de openActivitySheet debe dejar seleccionado al volver
// (el nuevo id si se creó, el mismo id si se editó, o un id de reemplazo si se borró).
internal fun MainActivity.openCategorySheet(existing: JSONObject?, onDone: (String) -> Unit) {
    val box = dialogBox()

    val nameIn = EditText(this)
    nameIn.hint = "Nombre (ej. Materia, Libro, Proyecto…)"
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    nameIn.setText(existing?.optString("name") ?: "")
    styleField(nameIn)
    box.addView(nameIn)

    box.addView(fieldLabel("Ícono"))
    var pickedIcon = existing?.optString("icon", "") ?: ""
    val iconRow = LinearLayout(this)
    iconRow.orientation = LinearLayout.HORIZONTAL
    val iconViews = ArrayList<Pair<String, View>>()
    fun styleIcon(key: String, v: View) {
        val on = key == pickedIcon
        v.background = if (on) rounded(blend(col(R.color.indigo), col(R.color.card), 0.13f), 13, 2, col(R.color.indigo))
                       else rounded(col(R.color.surface2), 13)
    }
    // "sin ícono" = usar la inicial del nombre (comportamiento de siempre).
    val noneBtn = label("Aa", 13f, col(R.color.ink), bold = true)
    noneBtn.gravity = Gravity.CENTER
    noneBtn.layoutParams = lp(w = dp(44), h = dp(44), right = 8)
    iconRow.addView(noneBtn)
    iconViews.add("" to noneBtn)
    for (key in CategoryIcons.ORDER) {
        val cell = android.widget.FrameLayout(this)
        cell.layoutParams = lp(w = dp(44), h = dp(44), right = 8)
        val iv = iconView(CategoryIcons.resOf(key)!!, col(R.color.ink), 20)
        iv.layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        cell.addView(iv)
        iconRow.addView(cell)
        iconViews.add(key to cell)
    }
    for ((key, v) in iconViews) {
        styleIcon(key, v)
        v.setOnClickListener {
            pickedIcon = key
            for ((k, vv) in iconViews) styleIcon(k, vv)
        }
    }
    val iconScroll = android.widget.HorizontalScrollView(this)
    iconScroll.isHorizontalScrollBarEnabled = false
    iconScroll.addView(iconRow)
    box.addView(iconScroll)

    box.addView(fieldLabel("Color"))
    var pickedColor = existing?.optString("color")
        ?: Store.COLORS[Store.categories(this).size % Store.COLORS.size]
    val palette = Store.COLORS.map { parseColor(it) }
    box.addView(colorPicker(palette, parseColor(pickedColor)) { c -> pickedColor = Store.COLORS[palette.indexOf(c)] })

    val builder = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle(if (existing == null) "Nueva categoría" else "Editar categoría")
        .setView(scrollBox(box))
        .setPositiveButton("Guardar") { _, _ ->
            val name = nameIn.text.toString().trim().ifEmpty { "General" }
            if (existing == null) {
                onDone(Store.addCategory(this, name, pickedIcon, pickedColor))
            } else {
                Store.updateCategory(this, existing.getString("id"), name, pickedIcon, pickedColor)
                onDone(existing.getString("id"))
            }
            render()
            doSync()
        }
        .setNegativeButton("Cancelar", null)
    if (existing != null) {
        builder.setNeutralButton("Borrar") { _, _ ->
            showStyled(
                AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("Borrar categoría \"${existing.optString("name", "")}\"")
                    .setMessage("Las actividades que la usan quedan sin categoría (General).")
                    .setPositiveButton("Borrar") { _, _ ->
                        Store.deleteCategory(this, existing.getString("id"))
                        // "" = sin categoría (cae al "General" virtual de categoryForActivity)
                        // en vez de forzar la creación de una categoría real de reemplazo.
                        val fallback = Store.categories(this).firstOrNull()?.getString("id") ?: ""
                        onDone(fallback)
                        render()
                        doSync()
                    }
                    .setNegativeButton("Cancelar", null),
                dangerPositive = true
            )
        }
    }
    showStyled(builder)
}

internal fun MainActivity.confirmDelete(act: JSONObject) {
    showStyled(
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Borrar \"${act.getString("name")}\"")
            .setMessage("También se borran sus sesiones. ¿Seguro?")
            .setPositiveButton("Borrar") { _, _ ->
                Store.deleteActivity(this, act.getString("id"))
                render()
                doSync()
            }
            .setNegativeButton("Cancelar", null),
        dangerPositive = true
    )
}
