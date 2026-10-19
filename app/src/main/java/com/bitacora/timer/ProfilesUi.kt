package com.bitacora.timer

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
    val box = dialogBox()
    val current = if (mandatory) "" else Store.currentProfileId(this)
    for (p in ProfileStore.profiles(this)) box.addView(profileRow(p, current, manage))
    box.addView(wideButton("Nuevo perfil", R.drawable.ic_plus) { openNewProfile() })

    val b = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle(if (mandatory) "¿Quién sos?" else "Perfiles")
        .setView(scrollBox(box))
    if (mandatory) b.setCancelable(false) else b.setNegativeButton("Cerrar", null)
    pickerDialog = showStyled(b)
}

internal fun MainActivity.profileRow(p: JSONObject, current: String, manage: Boolean): View {
    val id = p.getString("id")
    val isCur = id == current
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(10), dp(10), dp(6), dp(10))
    row.background = if (isCur) rounded(blend(col(R.color.indigo), col(R.color.card), 0.13f), 16, 2, col(R.color.indigo))
                     else rounded(col(R.color.surface2), 16)
    row.layoutParams = lp(bottom = 6)

    val name = p.optString("name")
    val avatar = label(name.trim().take(1).uppercase(), 16f, android.graphics.Color.WHITE, bold = true)
    avatar.gravity = Gravity.CENTER
    avatar.background = oval(Themes.accentOf(p.optString("theme", ProfileStore.DEFAULT_THEME), night = false))
    avatar.layoutParams = lp(w = dp(38), h = dp(38), right = 12)
    row.addView(avatar)

    val nm = label(name, 15.5f, col(R.color.ink), bold = true, single = true)
    nm.layoutParams = lp(w = 0, weight = 1f)
    row.addView(nm)

    if (isCur) row.addView(iconView(R.drawable.ic_check, col(R.color.indigo), 18))
    row.setOnClickListener { selectProfile(id); pickerDialog?.dismiss() }
    if (manage) {
        val edit = android.widget.FrameLayout(this)
        edit.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        val ic = iconView(R.drawable.ic_edit, col(R.color.muted), 18)
        ic.layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        edit.addView(ic)
        edit.setOnClickListener { openProfileEdit(p) }
        row.addView(edit)
    }
    return row
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
    styleField(nameIn)
    val box = dialogBox()
    box.addView(nameIn)
    showStyled(AlertDialog.Builder(this, R.style.AppDialog)
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
        .setNegativeButton("Cancelar", null))
    nameIn.requestFocus()
}

internal fun MainActivity.openProfileEdit(p: JSONObject) {
    val id = p.getString("id")
    val nameIn = EditText(this)
    nameIn.setText(p.optString("name"))
    nameIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    styleField(nameIn)
    val box = dialogBox()
    box.addView(nameIn)

    // ---- selector de tema (paletas) ----
    box.addView(fieldLabel("Tema"))
    var pickedTheme = p.optString("theme", ProfileStore.DEFAULT_THEME)
    if (!Themes.isValid(pickedTheme)) pickedTheme = ProfileStore.DEFAULT_THEME
    val night = Themes.isNight(this)
    val accents = Themes.ORDER.map { Themes.accentOf(it, night) }
    box.addView(colorPicker(accents, Themes.accentOf(pickedTheme, night)) { c ->
        pickedTheme = Themes.ORDER[accents.indexOf(c)]
    })

    val builder = AlertDialog.Builder(this, R.style.AppDialog)
        .setTitle("Editar perfil")
        .setView(box)
        .setPositiveButton("Guardar") { _, _ ->
            val name = nameIn.text.toString().trim()
            if (name.isNotEmpty()) ProfileStore.renameProfile(this, id, name)
            ProfileStore.setTheme(this, id, pickedTheme)
            updateHeader()
            pickerDialog?.dismiss(); showProfilePicker(false)
            // Si es el perfil activo, re-renderiza para aplicar el tema al instante.
            if (id == Store.currentProfileId(this)) render()
            doSync()
        }
        .setNegativeButton("Cancelar", null)
    if (id != Config.DEFAULT_PROFILE) {
        builder.setNeutralButton("Borrar") { _, _ -> confirmDeleteProfile(p) }
    }
    showStyled(builder)
}

internal fun MainActivity.confirmDeleteProfile(p: JSONObject) {
    val id = p.getString("id")
    val input = EditText(this)
    input.hint = "escribí borrar"
    input.inputType = InputType.TYPE_CLASS_TEXT
    styleField(input)
    val box = dialogBox()
    val msg = label("Se borra el perfil \"${p.optString("name")}\" y TODAS sus actividades e historial. Esto no se puede deshacer.\n\nPara confirmar, escribí la palabra borrar:", 15f, col(R.color.inkSoft))
    msg.setPadding(0, 0, 0, dp(12))
    box.addView(msg); box.addView(input)

    val dlg = showStyled(
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Borrar perfil")
            .setView(box)
            .setPositiveButton("Borrar", null)
            .setNegativeButton("Cancelar", null),
        dangerPositive = true
    )
    val pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
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
