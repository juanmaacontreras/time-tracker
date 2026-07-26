package com.bitacora.timer

// =====================================================================
//  Datos de conexión. Ya están completos.
//  El MISMO USER_KEY va en todos tus dispositivos (celu, compu, tablet).
// =====================================================================
object Config {
    const val SUPABASE_URL = "https://rjjrudenrxixofwhsknk.supabase.co"

    const val SUPABASE_KEY = "sb_publishable_moRNgc6pbh-OfyK9193D9Q_hQblC5xz"

    const val USER_KEY = "juanma-electro-9Kx7mQ2p"

    // Perfiles: cada uno tiene su propio "bucket" en Supabase. El perfil "default"
    // (Principal) usa la USER_KEY base tal cual, así los datos que ya existían se
    // adoptan sin moverse. Los demás perfiles usan USER_KEY::<idPerfil>.
    const val DEFAULT_PROFILE = "default"
    val INDEX_KEY = "$USER_KEY::index"
    // Registro de dispositivos para push (tokens FCM). Compartido por todos los
    // perfiles del mismo USER_KEY: cada entrada dice qué perfil tiene activo cada
    // dispositivo, para despertar solo a los que corresponde.
    val DEVICES_KEY = "$USER_KEY::devices"
    fun bucketKey(profileId: String): String =
        if (profileId == DEFAULT_PROFILE || profileId.isEmpty()) USER_KEY else "$USER_KEY::$profileId"

    fun syncEnabled(): Boolean = SUPABASE_URL.isNotBlank() && SUPABASE_KEY.isNotBlank()
}
