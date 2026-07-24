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
    val NAMES = mapOf(
        "azul" to "Azul", "verde" to "Verde", "violeta" to "Violeta",
        "arcilla" to "Arcilla", "grafito" to "Grafito"
    )

    private val PALETTES = mapOf(
        "azul"    to Accent(0xFF2F4B8F.toInt(), 0xFF7B96D4.toInt(), 0xFF1C74C9.toInt(), 0xFF62AEEA.toInt()),
        "verde"   to Accent(0xFF2E7D5B.toInt(), 0xFF6FBF9B.toInt(), 0xFF1F9D57.toInt(), 0xFF57C98A.toInt()),
        "violeta" to Accent(0xFF6A4C93.toInt(), 0xFFA98FD6.toInt(), 0xFF8A5CD0.toInt(), 0xFFB79BEA.toInt()),
        "arcilla" to Accent(0xFFA6552E.toInt(), 0xFFD69574.toInt(), 0xFFD06A34.toInt(), 0xFFE8956A.toInt()),
        "grafito" to Accent(0xFF4A5A78.toInt(), 0xFFAAB6CC.toInt(), 0xFF5B6BB5.toInt(), 0xFF8AA0E0.toInt())
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
