package com.bitacora.timer

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

// Selección, alta, edición y borrado de perfiles.
// Funciones de extensión de MainActivity: comparten su estado (campos internal).

internal fun MainActivity.chooseProfileFirst() {
    showProfilePicker(mandatory = true)
    // Traemos el índice remoto por si hay perfiles creados en otro dispositivo.
    Thread {
        Sync.syncIndex(applicationContext)
        runOnUiThread {
            if (!Store.hasChosenProfile(this) && pickerDialog?.isShowing == true) {
                pickerDialog?.dismiss(); showProfilePicker(true)
            }
        }
    }.start()
}

internal fun MainActivity.openProfileSheet() { showProfilePicker(mandatory = false) }

internal fun MainActivity.scrollBox(box: View): View {
    val sc = android.widget.ScrollView(this)
    sc.addView(box)
    return sc
}

internal fun MainActivity.showProfilePicker(mandatory: Boolean) {
    val manage = !mandatory
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(8), dp(20), dp(4))
    val current = if (mandatory) "" else Store.currentProfileId(this)
    for (p in ProfileStore.profiles(this)) box.addView(profileRow(p, current, manage))
    box.addView(newProfileButton())

    val b = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle(if (mandatory) "¿Quién sos?" else "Perfiles")
        .setView(scrollBox(box))
    if (mandatory) b.setCancelable(false) else b.setNegativeButton("Cerrar", null)
    pickerDialog = b.create()
    pickerDialog?.show()
}

internal fun MainActivity.profileRow(p: JSONObject, current: String, manage: Boolean): View {
    val id = p.getString("id")
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(14), dp(14), dp(10), dp(14))
    val bg = card()
    if (id == current) bg.setStroke(dp(2), col(R.color.indigo))
    row.background = bg
    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
    lp.bottomMargin = dp(8); row.layoutParams = lp

    val nm = TextView(this)
    nm.text = p.optString("name")
    nm.textSize = 16f
    nm.setTypeface(nm.typeface, Typeface.BOLD)
    nm.setTextColor(col(R.color.ink))
    nm.maxLines = 1
    nm.ellipsize = TextUtils.TruncateAt.END
    nm.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    row.addView(nm)

    if (id == current) {
        val chk = TextView(this)
        chk.text = "✓"; chk.textSize = 16f; chk.setTextColor(col(R.color.indigo)); chk.setPadding(0, 0, dp(8), 0)
        row.addView(chk)
    }
    row.setOnClickListener { selectProfile(id); pickerDialog?.dismiss() }
    if (manage) {
        val edit = TextView(this)
        edit.text = "✎"; edit.textSize = 16f; edit.setTextColor(col(R.color.muted))
        edit.setPadding(dp(12), dp(4), dp(4), dp(4))
        edit.setOnClickListener { openProfileEdit(p) }
        row.addView(edit)
    }
    return row
}

internal fun MainActivity.newProfileButton(): View {
    val add = TextView(this)
    add.text = "+  Nuevo perfil"
    add.gravity = Gravity.CENTER
    add.setTextColor(col(R.color.indigo))
    add.setTypeface(add.typeface, Typeface.BOLD)
    add.setPadding(dp(14), dp(16), dp(14), dp(16))
    val d = GradientDrawable()
    d.cornerRadius = dp(12).toFloat()
    d.setStroke(dp(2), col(R.color.line))
    add.background = d
    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
    lp.topMargin = dp(4); add.layoutParams = lp
    add.setOnClickListener { openNewProfile() }
    return add
}

internal fun MainActivity.selectProfile(id: String) {
    if (id == Store.currentProfileId(this) && Store.hasChosenProfile(this)) return
    Store.setCurrentProfile(this, id)
    // Reset del estado transitorio de la UI al cambiar de dataset.
    showArchived = false; expandedIds.clear()
    statsOffset = 0; statsCategory = "all"; catExpanded = null
    tab = "timer"; switchTab("timer")
    beginSession()
}

internal fun MainActivity.openNewProfile() {
    val nameIn = EditText(this)
    nameIn.hint = "Nombre del perfil (ej. María)"
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(8), dp(20), 0)
    box.addView(nameIn)
    AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle("Nuevo perfil")
        .setView(box)
        .setPositiveButton("Crear") { _, _ ->
            val name = nameIn.text.toString().trim()
            if (name.isNotEmpty()) {
                val id = ProfileStore.addProfile(this, name, ProfileStore.DEFAULT_THEME)
                pickerDialog?.dismiss()
                selectProfile(id)
                doSync()
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
    nameIn.requestFocus()
}

internal fun MainActivity.openProfileEdit(p: JSONObject) {
    val id = p.getString("id")
    val nameIn = EditText(this)
    nameIn.setText(p.optString("name"))
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(8), dp(20), 0)
    box.addView(nameIn)

    // ---- selector de tema (paletas) ----
    val themeLabel = TextView(this)
    themeLabel.text = "Tema"
    themeLabel.setTextColor(col(R.color.muted))
    themeLabel.textSize = 12f
    themeLabel.setPadding(0, dp(14), 0, dp(6))
    box.addView(themeLabel)

    var pickedTheme = p.optString("theme", ProfileStore.DEFAULT_THEME)
    if (!Themes.isValid(pickedTheme)) pickedTheme = ProfileStore.DEFAULT_THEME
    val night = Themes.isNight(this)
    val themeRow = LinearLayout(this)
    themeRow.orientation = LinearLayout.HORIZONTAL
    val swatchViews = ArrayList<View>()
    fun styleTheme(v: View, t: String) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(Themes.accentOf(t, night))
        if (t == pickedTheme) d.setStroke(dp(3), col(R.color.ink))
        v.background = d
    }
    for (t in Themes.ORDER) {
        val sw = View(this)
        val slp = LinearLayout.LayoutParams(dp(40), dp(40)); slp.rightMargin = dp(10)
        sw.layoutParams = slp
        styleTheme(sw, t)
        sw.setOnClickListener {
            pickedTheme = t
            for ((i, s) in swatchViews.withIndex()) styleTheme(s, Themes.ORDER[i])
        }
        swatchViews.add(sw)
        themeRow.addView(sw)
    }
    box.addView(themeRow)

    val builder = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle("Editar perfil")
        .setView(box)
        .setPositiveButton("Guardar") { _, _ ->
            val name = nameIn.text.toString().trim()
            if (name.isNotEmpty()) ProfileStore.renameProfile(this, id, name)
            ProfileStore.setTheme(this, id, pickedTheme)
            updateProfileChip()
            pickerDialog?.dismiss(); showProfilePicker(false)
            // Si es el perfil activo, re-renderiza para aplicar el tema al instante.
            if (id == Store.currentProfileId(this)) render()
            doSync()
        }
        .setNegativeButton("Cancelar", null)
    if (id != Config.DEFAULT_PROFILE) {
        builder.setNeutralButton("Borrar") { _, _ -> confirmDeleteProfile(p) }
    }
    val dlg = builder.create(); dlg.show()
    dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(col(R.color.live))
}

internal fun MainActivity.confirmDeleteProfile(p: JSONObject) {
    val id = p.getString("id")
    val input = EditText(this)
    input.hint = "escribí borrar"
    input.inputType = InputType.TYPE_CLASS_TEXT
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(4), dp(20), 0)
    val msg = TextView(this)
    msg.text = "Se borra el perfil \"${p.optString("name")}\" y TODAS sus actividades e historial. Esto no se puede deshacer.\n\nPara confirmar, escribí la palabra borrar:"
    msg.setTextColor(col(R.color.inkSoft))
    msg.setPadding(0, 0, 0, dp(12))
    box.addView(msg); box.addView(input)

    val dlg = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle("Borrar perfil")
        .setView(box)
        .setPositiveButton("Borrar", null)
        .setNegativeButton("Cancelar", null)
        .create()
    dlg.show()
    val pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
    pos.setTextColor(col(R.color.live))
    pos.setOnClickListener {
        if (input.text.toString().trim().lowercase() == "borrar") {
            val wasCurrent = Store.currentProfileId(this) == id
            ProfileStore.deleteProfile(this, id)
            doSync()
            dlg.dismiss(); pickerDialog?.dismiss()
            if (wasCurrent) selectProfile(Config.DEFAULT_PROFILE) else showProfilePicker(false)
        } else {
            input.error = "Escribí borrar para confirmar"
        }
    }
}
