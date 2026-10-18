package com.bitacora.timer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

// Hojas de alta/edición de actividades y categorías, y confirmación de borrado.
// Funciones de extensión de MainActivity: comparten su estado (campos internal).

internal fun MainActivity.openActivitySheet(existing: JSONObject?) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(8), dp(20), 0)

    val nameIn = EditText(this)
    nameIn.hint = "Nombre (ej. Análisis de circuitos)"
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    nameIn.setText(existing?.optString("name") ?: "")

    box.addView(nameIn)

    // ---------------- categoría (dropdown, no texto libre) ----------------
    val catLabel = TextView(this)
    catLabel.text = "Categoría"
    catLabel.setTextColor(col(R.color.muted))
    catLabel.textSize = 12f
    catLabel.setPadding(0, dp(14), 0, dp(6))
    box.addView(catLabel)

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

    fun rebuildCatRow() {
        catRow.removeAllViews()
        for (cat in Store.categories(this)) {
            val id = cat.getString("id")
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPadding(dp(10), dp(7), dp(12), dp(7))
            val clp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clp.rightMargin = dp(6)
            chip.layoutParams = clp
            val active = id == selectedCatId
            val bg = GradientDrawable()
            bg.cornerRadius = dp(16).toFloat()
            if (active) bg.setColor(col(R.color.ink))
            else { bg.setColor(col(R.color.card)); bg.setStroke(dp(1), col(R.color.line)) }
            chip.background = bg

            val resId = CategoryIcons.resOf(cat.optString("icon", ""))
            if (resId != null) {
                val iv = ImageView(this)
                iv.setImageResource(resId)
                iv.setColorFilter(if (active) col(R.color.paper) else Color.parseColor(cat.optString("color", "#2F4B8F")))
                val ilp = LinearLayout.LayoutParams(dp(14), dp(14)); ilp.rightMargin = dp(6)
                iv.layoutParams = ilp
                chip.addView(iv)
            } else {
                val dot = View(this)
                val dotBg = GradientDrawable()
                dotBg.shape = GradientDrawable.OVAL
                dotBg.setColor(Color.parseColor(cat.optString("color", "#2F4B8F")))
                dot.background = dotBg
                val dlp = LinearLayout.LayoutParams(dp(8), dp(8)); dlp.rightMargin = dp(6)
                dot.layoutParams = dlp
                chip.addView(dot)
            }

            val t = TextView(this)
            t.text = cat.optString("name", "General")
            t.textSize = 12f
            t.setTextColor(if (active) col(R.color.paper) else col(R.color.inkSoft))
            chip.addView(t)

            chip.setOnClickListener { selectedCatId = id; rebuildCatRow() }
            chip.setOnLongClickListener {
                openCategorySheet(cat) { keepId -> selectedCatId = keepId; rebuildCatRow() }
                true
            }
            catRow.addView(chip)
        }

        val add = TextView(this)
        add.text = "+ Nueva"
        add.textSize = 12f
        add.setTypeface(add.typeface, Typeface.BOLD)
        add.setTextColor(col(R.color.indigo))
        add.setPadding(dp(12), dp(7), dp(12), dp(7))
        val abg = GradientDrawable()
        abg.cornerRadius = dp(16).toFloat()
        abg.setStroke(dp(1), col(R.color.indigo))
        add.background = abg
        add.setOnClickListener {
            openCategorySheet(null) { newId -> selectedCatId = newId; rebuildCatRow() }
        }
        catRow.addView(add)
    }
    rebuildCatRow()

    val catHint = TextView(this)
    catHint.text = "Mantené presionada una categoría para editarla o borrarla"
    catHint.textSize = 10.5f
    catHint.setTextColor(col(R.color.muted))
    catHint.setPadding(0, dp(6), 0, 0)
    box.addView(catHint)

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
    val dialog = builder.create()
    dlg = dialog
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(col(R.color.live))
}

// Alta/edición de una categoría (nombre + ícono + color). onDone recibe el id que
// el selector de categorías de openActivitySheet debe dejar seleccionado al volver
// (el nuevo id si se creó, el mismo id si se editó, o un id de reemplazo si se borró).
internal fun MainActivity.openCategorySheet(existing: JSONObject?, onDone: (String) -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(8), dp(20), 0)

    val nameIn = EditText(this)
    nameIn.hint = "Nombre (ej. Materia, Libro, Proyecto…)"
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    nameIn.setText(existing?.optString("name") ?: "")
    box.addView(nameIn)

    val iconLabel = TextView(this)
    iconLabel.text = "Ícono"
    iconLabel.setTextColor(col(R.color.muted))
    iconLabel.textSize = 12f
    iconLabel.setPadding(0, dp(14), 0, dp(6))
    box.addView(iconLabel)

    var pickedIcon = existing?.optString("icon", "") ?: ""
    val iconRow = LinearLayout(this)
    iconRow.orientation = LinearLayout.HORIZONTAL
    val iconViews = ArrayList<Pair<String, View>>()
    fun styleIcon(key: String, v: View) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(8).toFloat()
        bg.setColor(col(R.color.card))
        val on = key == pickedIcon
        bg.setStroke(if (on) dp(2) else dp(1), if (on) col(R.color.indigo) else col(R.color.line))
        v.background = bg
    }
    // "sin ícono" = usar la inicial del nombre (comportamiento de siempre).
    val noneBtn = TextView(this)
    noneBtn.text = "Aa"
    noneBtn.gravity = Gravity.CENTER
    noneBtn.setTextColor(col(R.color.inkSoft))
    noneBtn.textSize = 13f
    val nlp = LinearLayout.LayoutParams(dp(38), dp(38)); nlp.rightMargin = dp(8)
    noneBtn.layoutParams = nlp
    iconRow.addView(noneBtn)
    iconViews.add("" to noneBtn)
    for (key in CategoryIcons.ORDER) {
        val iv = ImageView(this)
        iv.setImageResource(CategoryIcons.resOf(key)!!)
        iv.setColorFilter(col(R.color.ink))
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setPadding(dp(9), dp(9), dp(9), dp(9))
        val ilp = LinearLayout.LayoutParams(dp(38), dp(38)); ilp.rightMargin = dp(8)
        iv.layoutParams = ilp
        iconRow.addView(iv)
        iconViews.add(key to iv)
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

    val colorLabel = TextView(this)
    colorLabel.text = "Color"
    colorLabel.setTextColor(col(R.color.muted))
    colorLabel.textSize = 12f
    colorLabel.setPadding(0, dp(14), 0, dp(6))
    box.addView(colorLabel)

    var pickedColor = existing?.optString("color")
        ?: Store.COLORS[Store.categories(this).size % Store.COLORS.size]
    val colorRow = LinearLayout(this)
    colorRow.orientation = LinearLayout.HORIZONTAL
    val swatches = ArrayList<View>()
    fun styleColor(v: View, c: String) {
        val d = GradientDrawable()
        d.cornerRadius = dp(8).toFloat()
        d.setColor(Color.parseColor(c))
        if (c == pickedColor) d.setStroke(dp(3), col(R.color.ink))
        v.background = d
    }
    for (c in Store.COLORS) {
        val sw = View(this)
        val slp = LinearLayout.LayoutParams(dp(34), dp(34)); slp.rightMargin = dp(8)
        sw.layoutParams = slp
        styleColor(sw, c)
        sw.setOnClickListener {
            pickedColor = c
            for ((i, s) in swatches.withIndex()) styleColor(s, Store.COLORS[i])
        }
        swatches.add(sw)
        colorRow.addView(sw)
    }
    val colorScroll = android.widget.HorizontalScrollView(this)
    colorScroll.isHorizontalScrollBarEnabled = false
    colorScroll.addView(colorRow)
    box.addView(colorScroll)

    val builder = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle(if (existing == null) "Nueva categoría" else "Editar categoría")
        .setView(box)
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
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
    val dialog = builder.create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(col(R.color.live))
}

internal fun MainActivity.confirmDelete(act: JSONObject) {
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
