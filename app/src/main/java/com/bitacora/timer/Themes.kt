package com.bitacora.timer

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat

/**
 * Temas por perfil. Cada tema cambia el color de ACENTO (el "indigo" que colorea
 * botones, íconos y resaltados) y el color de "corriendo" (live, el borde/tiempo
 * de una actividad activa). Los neutros (papel, tinta, líneas) se mantienen iguales
 * en las 5 paletas — así los fondos redondeados de XML siguen siendo coherentes y
 * el tema aplica limpio sobre toda la app. Cada tema tiene versión clara y oscura.
 */
object Themes {
    data class Accent(
        val lightAccent: Int, val darkAccent: Int,
        val lightLive: Int, val darkLive: Int
    )

    val ORDER = listOf("azul", "verde", "violeta", "arcilla", "grafito")

    // Acentos Nord: Frost para azul/grafito, Aurora para verde/violeta/arcilla. En claro
    // van un poco más oscuros que los originales de Nord para que se lean sobre blanco.
    private val PALETTES = mapOf(
        "azul"    to Accent(0xFF5E81AC.toInt(), 0xFF81A1C1.toInt(), 0xFF3F86B0.toInt(), 0xFF88C0D0.toInt()),
        "verde"   to Accent(0xFF5F8A4E.toInt(), 0xFFA3BE8C.toInt(), 0xFF6E9F5A.toInt(), 0xFFB4CF9C.toInt()),
        "violeta" to Accent(0xFF8A6585.toInt(), 0xFFB48EAD.toInt(), 0xFF9C74A0.toInt(), 0xFFC6A4C0.toInt()),
        "arcilla" to Accent(0xFFB4694F.toInt(), 0xFFD08770.toInt(), 0xFFC9784F.toInt(), 0xFFE09A82.toInt()),
        "grafito" to Accent(0xFF4C566A.toInt(), 0xFFD8DEE9.toInt(), 0xFF5E81AC.toInt(), 0xFF88C0D0.toInt())
    )

    fun isValid(id: String): Boolean = PALETTES.containsKey(id)

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Color de acento (indigo) de un tema, en su versión clara/oscura. Para las muestras del selector.
    fun accentOf(theme: String, night: Boolean): Int {
        val p = PALETTES[theme] ?: PALETTES["azul"]!!
        return if (night) p.darkAccent else p.lightAccent
    }

    // Resuelve un @color: solo intercepta indigo (acento) y live (corriendo) según el
    // tema del perfil activo; el resto cae al recurso base (que ya tiene claro/oscuro).
    fun color(ctx: Context, resId: Int): Int {
        val theme = ProfileStore.themeOf(ctx, Store.currentProfileId(ctx))
        val p = PALETTES[theme] ?: return ContextCompat.getColor(ctx, resId)
        val night = isNight(ctx)
        return when (resId) {
            R.color.indigo -> if (night) p.darkAccent else p.lightAccent
            R.color.live -> if (night) p.darkLive else p.lightLive
            else -> ContextCompat.getColor(ctx, resId)
        }
    }
}
