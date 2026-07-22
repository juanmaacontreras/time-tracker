package com.bitacora.timer

// =====================================================================
//  PEGÁ ACÁ TUS 3 DATOS (ver SETUP.md, paso 2).
//  Si dejás SUPABASE_URL vacío, la app funciona igual pero SIN sync.
// =====================================================================
object Config {
    // Ej: "https://abcdefgh.supabase.co"
    const val SUPABASE_URL = ""

    // La "anon public" key de tu proyecto Supabase.
    const val SUPABASE_KEY = ""

    // Un código secreto tuyo (inventalo). El MISMO en todos tus dispositivos.
    // Ej: "juanma-2026-xR7k". Todo lo que comparta este código ve los mismos datos.
    const val USER_KEY = "cambiame-por-un-codigo-secreto"

    fun syncEnabled(): Boolean = SUPABASE_URL.isNotBlank() && SUPABASE_KEY.isNotBlank()
}
