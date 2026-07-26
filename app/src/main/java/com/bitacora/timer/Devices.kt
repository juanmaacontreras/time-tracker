package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registro de dispositivos para push, sincronizado bajo Config.DEVICES_KEY.
 * Cada entrada: { deviceId, token, profileId, updatedAt, deleted }.
 *
 *  - deviceId: id local estable de la instalación. Sin esto, cada arranque agregaría
 *    una entrada nueva en vez de pisar la propia.
 *  - profileId: el perfil que este dispositivo tiene activo AHORA. Es lo que permite
 *    que la Edge Function despierte solo a los dispositivos parados en el perfil que
 *    cambió; sin ese dato, un cambio en cualquier perfil despertaría a todos al pedo
 *    (cada uno iría a buscar su propio bucket, que no cambió, y se volvería a dormir).
 *
 * Se fusiona por deviceId + updatedAt, igual que actividades/sesiones/perfiles.
 */
object Devices {
    private const val PREFS = "bitacora_app"
    private const val KEY = "devicesIndex"
    private const val DEVICE_ID_KEY = "deviceId"
    private const val TOKEN_KEY = "fcmToken"

    @Volatile private var cache: JSONObject? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Id de la instalación, no de la persona: se genera al azar la primera vez y no
    // sale de acá salvo dentro del propio bucket del usuario.
    @Synchronized
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString(DEVICE_ID_KEY, null)
        if (id == null) {
            id = Store.uid()
            p.edit().putString(DEVICE_ID_KEY, id).apply()
        }
        return id
    }

    fun savedToken(ctx: Context): String = prefs(ctx).getString(TOKEN_KEY, "") ?: ""

    @Synchronized
    fun index(ctx: Context): JSONObject {
        cache?.let { return it }
        val raw = prefs(ctx).getString(KEY, null)
        val obj = if (raw != null) JSONObject(raw) else JSONObject()
        if (!obj.has("devices")) obj.put("devices", JSONArray())
        cache = obj
        return obj
    }

    @Synchronized
    fun write(ctx: Context, obj: JSONObject) {
        cache = obj
        prefs(ctx).edit().putString(KEY, obj.toString()).apply()
    }

    /**
     * Deja registrado este dispositivo con su token y el perfil activo actual.
     * Devuelve true solo si algo cambió de verdad — así el arranque normal de la app
     * no reescribe updatedAt ni dispara una subida cada vez.
     */
    @Synchronized
    fun registerLocal(ctx: Context, token: String): Boolean {
        if (token.isEmpty()) return false
        prefs(ctx).edit().putString(TOKEN_KEY, token).apply()
        val id = deviceId(ctx)
        val profileId = Store.currentProfileId(ctx)
        val obj = index(ctx)
        val arr = obj.getJSONArray("devices")
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            if (d.optString("deviceId") == id) {
                if (d.optString("token") == token &&
                    d.optString("profileId") == profileId &&
                    !d.optBoolean("deleted", false)
                ) return false
                d.put("token", token).put("profileId", profileId)
                    .put("deleted", false).put("updatedAt", Store.now())
                write(ctx, obj)
                return true
            }
        }
        arr.put(
            JSONObject()
                .put("deviceId", id).put("token", token).put("profileId", profileId)
                .put("updatedAt", Store.now()).put("deleted", false)
        )
        write(ctx, obj)
        return true
    }

    // ---- sync ----
    @Synchronized
    fun payload(ctx: Context): JSONObject =
        JSONObject().put("devices", JSONArray(index(ctx).getJSONArray("devices").toString()))

    @Synchronized
    fun merge(ctx: Context, remote: JSONObject) {
        val obj = index(ctx)
        val local = obj.getJSONArray("devices")
        val rem = remote.optJSONArray("devices") ?: JSONArray()
        val idx = HashMap<String, Int>()
        for (i in 0 until local.length()) idx[local.getJSONObject(i).optString("deviceId")] = i
        for (i in 0 until rem.length()) {
            val r = rem.getJSONObject(i)
            val id = r.optString("deviceId", "")
            if (id.isEmpty()) continue
            val at = idx[id]
            if (at == null) {
                local.put(r); idx[id] = local.length() - 1
            } else if (r.optLong("updatedAt", 0) > local.getJSONObject(at).optLong("updatedAt", 0)) {
                local.put(at, r)
            }
        }
        write(ctx, obj)
    }
}
