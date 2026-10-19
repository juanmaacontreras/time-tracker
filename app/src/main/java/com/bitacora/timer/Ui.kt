package com.bitacora.timer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

// Kit de componentes de la interfaz: el mismo sistema de diseño que web/index.html
// (superficies sin bordes, swatch de identidad, botones pastilla, números tabulares).
// Funciones de extensión de MainActivity porque dependen de dp() y col() (tema del perfil).

internal const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Mezcla fg sobre bg: ratio = cuánto de fg (0..1). Para fondos tenues del color de una actividad. */
internal fun blend(fg: Int, bg: Int, ratio: Float): Int {
    val r = ratio.coerceIn(0f, 1f)
    fun ch(a: Int, b: Int) = (a * r + b * (1 - r)).toInt()
    return Color.rgb(ch(Color.red(fg), Color.red(bg)), ch(Color.green(fg), Color.green(bg)), ch(Color.blue(fg), Color.blue(bg)))
}

internal fun parseColor(hex: String?): Int =
    try { Color.parseColor(hex ?: "#2F4B8F") } catch (e: IllegalArgumentException) { Color.parseColor("#2F4B8F") }

internal fun MainActivity.rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor)
    return d
}

internal fun oval(color: Int): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.OVAL
    d.setColor(color)
    return d
}

/** LayoutParams de LinearLayout: w/h en px (o MATCH/WRAP), márgenes en dp. */
internal fun MainActivity.lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight).also {
        it.topMargin = dp(top); it.bottomMargin = dp(bottom); it.leftMargin = dp(left); it.rightMargin = dp(right)
    }

/** TextView con las opciones de siempre. tnum: dígitos tabulares (no "bailan" al cambiar). */
internal fun MainActivity.label(
    text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false,
    tnum: Boolean = false, single: Boolean = false
): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sizeSp
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    if (tnum) t.fontFeatureSettings = "tnum"
    if (single) { t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END }
    return t
}

internal fun MainActivity.iconView(res: Int, tint: Int, sizeDp: Int): ImageView {
    val iv = ImageView(this)
    iv.setImageResource(res)
    iv.setColorFilter(tint)
    iv.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return iv
}

/**
 * Swatch de identidad: ícono de la categoría (o la inicial) sobre el color de la
 * actividad. Tenue en las filas; solid (fondo lleno, ícono blanco) en la tarjeta de lo
 * que corre — el mismo elemento que la notificación.
 */
internal fun MainActivity.swatch(color: Int, iconKey: String, name: String, sizeDp: Int, solid: Boolean): View {
    val box = FrameLayout(this)
    box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    val radius = (sizeDp * 0.31f).toInt()
    // En oscuro los colores de actividad (apagados) se aclaran para que el ícono se lea.
    val fg = when {
        solid -> Color.WHITE
        Themes.isNight(this) -> blend(color, Color.WHITE, 0.5f)
        else -> color
    }
    box.background = rounded(if (solid) color else blend(color, col(R.color.card), 0.15f), radius)
    val res = CategoryIcons.resOf(iconKey)
    if (res != null) {
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.setColorFilter(fg)
        val s = dp((sizeDp * 0.52f).toInt())
        iv.layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        box.addView(iv)
    } else {
        val t = label(name.trim().take(1).uppercase().ifEmpty { "?" }, sizeDp * 0.4f, fg, bold = true)
        t.gravity = Gravity.CENTER
        t.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        box.addView(t)
    }
    return box
}

/** Botón pastilla con ícono. primary = lleno con el acento; si no, tonal (tinta tenue
 *  sobre surface, el fondo sobre el que va apoyado). */
internal fun MainActivity.pillButton(text: String, iconRes: Int, primary: Boolean, surface: Int, onClick: () -> Unit): LinearLayout {
    val b = LinearLayout(this)
    b.orientation = LinearLayout.HORIZONTAL
    b.gravity = Gravity.CENTER
    b.minimumHeight = dp(48)
    b.setPadding(dp(16), 0, dp(16), 0)
    val fg = if (primary) col(R.color.onAccent) else col(R.color.ink)
    b.background = withRipple(
        rounded(if (primary) col(R.color.indigo) else blend(col(R.color.ink), surface, 0.09f), 24), dp(24).toFloat()
    )
    b.addView(iconView(iconRes, fg, 18))
    val t = label(text, 15f, fg, bold = true)
    t.setPadding(dp(8), 0, 0, 0)
    b.addView(t)
    b.setOnClickListener { onClick() }
    return b
}

/** Botón ancho tonal con el acento (Nueva actividad, Nuevo perfil, Exportar). */
internal fun MainActivity.wideButton(text: String, iconRes: Int, onClick: () -> Unit): LinearLayout {
    val b = LinearLayout(this)
    b.orientation = LinearLayout.HORIZONTAL
    b.gravity = Gravity.CENTER
    b.setPadding(dp(14), dp(15), dp(14), dp(15))
    b.background = withRipple(rounded(blend(col(R.color.indigo), col(R.color.card), 0.13f), 16), dp(16).toFloat())
    b.addView(iconView(iconRes, col(R.color.indigo), 18))
    val t = label(text, 15f, col(R.color.indigo), bold = true)
    t.setPadding(dp(8), 0, 0, 0)
    b.addView(t)
    b.layoutParams = lp(top = 10)
    b.setOnClickListener { onClick() }
    return b
}

/** Encabezado de sección ("Actividades", "Por actividad"...) con una acción opcional a la derecha. */
internal fun MainActivity.sectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.layoutParams = lp(top = 22, bottom = 10)
    row.setPadding(dp(4), 0, dp(4), 0)
    val t = label(title, 13f, col(R.color.inkSoft), bold = true)
    t.layoutParams = lp(w = 0, weight = 1f)
    row.addView(t)
    if (action != null && onAction != null) {
        val a = label(action, 13f, col(R.color.indigo), bold = true)
        a.setPadding(dp(8), dp(4), 0, dp(4))
        a.setOnClickListener { onAction() }
        row.addView(a)
    }
    return row
}

/** Tarjeta: superficie sin borde, esquinas amplias. */
internal fun MainActivity.cardBox(vertical: Boolean = true, radiusDp: Int = 16): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    c.background = rounded(col(R.color.card), radiusDp)
    return c
}

// ---------- diálogos ----------

internal fun MainActivity.dialogBox(): LinearLayout {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(4), dp(24), 0)
    return box
}

internal fun MainActivity.fieldLabel(text: String): TextView {
    val t = label(text, 13f, col(R.color.inkSoft), bold = true)
    t.setPadding(0, dp(18), 0, dp(8))
    return t
}

/** Campo de texto "relleno" (sin la línea de abajo de Material viejo). */
internal fun MainActivity.styleField(et: EditText) {
    et.background = rounded(col(R.color.surface2), 14)
    et.setPadding(dp(14), dp(13), dp(14), dp(13))
    et.textSize = 16f
    et.setTextColor(col(R.color.ink))
    et.setHintTextColor(col(R.color.muted))
}

/** Fila de colores (círculos; el elegido lleva un anillo). onPick recibe el elegido. */
internal fun MainActivity.colorPicker(colors: List<Int>, initial: Int, onPick: (Int) -> Unit): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.setPadding(0, dp(3), 0, dp(3))
    val views = ArrayList<View>()
    fun paint(v: View, c: Int, sel: Boolean) {
        if (!sel) { v.background = oval(c); return }
        // Anillo de tinta, separado del círculo por un borde del color de fondo del diálogo.
        val inner = oval(c)
        inner.setStroke(dp(3), col(R.color.card))
        val ring = android.graphics.drawable.LayerDrawable(arrayOf(oval(col(R.color.ink)), inner))
        ring.setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
        v.background = ring
    }
    var current = initial
    for (c in colors) {
        val v = View(this)
        v.layoutParams = lp(w = dp(36), h = dp(36), right = 10)
        paint(v, c, c == current)
        v.setOnClickListener {
            current = c
            for ((i, vv) in views.withIndex()) paint(vv, colors[i], colors[i] == current)
            onPick(c)
        }
        views.add(v)
        row.addView(v)
    }
    val scroll = android.widget.HorizontalScrollView(this)
    scroll.isHorizontalScrollBarEnabled = false
    scroll.addView(row)
    return scroll
}

/**
 * Muestra un AlertDialog con el estilo de la app: título grande, botón principal lleno
 * (rojo si es destructivo), secundario en texto y "Borrar" (neutral) en rojo.
 */
internal fun MainActivity.showStyled(builder: AlertDialog.Builder, dangerPositive: Boolean = false): AlertDialog {
    val dlg = builder.create()
    dlg.show()
    styleDialog(dlg, dangerPositive)
    return dlg
}

internal fun MainActivity.styleDialog(dlg: AlertDialog, dangerPositive: Boolean = false) {
    dlg.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.let {
        it.textSize = 20f
        it.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        it.setTextColor(col(R.color.ink))
    }
    dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.let {
        it.isAllCaps = false
        it.textSize = 15f
        it.setTextColor(if (dangerPositive) Color.WHITE else col(R.color.onAccent))
        it.background = rounded(if (dangerPositive) col(R.color.danger) else col(R.color.indigo), 22)
        it.setPadding(dp(20), 0, dp(20), 0)
        it.minHeight = dp(44); it.minimumHeight = dp(44)
    }
    dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.let {
        it.isAllCaps = false
        it.textSize = 15f
        it.setTextColor(col(R.color.inkSoft))
    }
    dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.let {
        it.isAllCaps = false
        it.textSize = 15f
        it.setTextColor(col(R.color.danger))
    }
}
