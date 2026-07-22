package com.bitacora.timer

// =====================================================================
//  Datos de conexión. Ya están completos.
//  El MISMO USER_KEY va en todos tus dispositivos (celu, compu, tablet).
// =====================================================================
object Config {
    const val SUPABASE_URL = "https://rjjrudenrxixofwhsknk.supabase.co"

    const val SUPABASE_KEY = "sb_publishable_moRNgc6pbh-OfyK9193D9Q_hQblC5xz"

    const val USER_KEY = "juanma-electro-9Kx7mQ2p"

    fun syncEnabled(): Boolean = SUPABASE_URL.isNotBlank() && SUPABASE_KEY.isNotBlank()
}
