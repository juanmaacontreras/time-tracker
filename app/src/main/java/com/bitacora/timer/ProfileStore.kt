package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Índice de perfiles, sincronizado entre dispositivos bajo Config.INDEX_KEY.
 * Cada perfil: { id, name, theme, updatedAt, deleted }. Se fusiona por id+updatedAt,
 * igual que actividades/sesiones. El perfil "default" (Principal) siempre existe y
 * apunta al bucket base, para adoptar los datos que ya había sin migrar nada.
 */
object ProfileStore {
    private const val PREFS = "bitacora_app"
    private const val KEY = "profilesIndex"
    const val DEFAULT_THEME = "azul"

    @Volatile private var cache: JSONObject? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun index(ctx: Context): JSONObject {
        cache?.let { return it }
        val raw = prefs(ctx).getString(KEY, null)
        val obj = if (raw != null) JSONObject(raw) else JSONObject()
        if (!obj.has("profiles")) obj.put("profiles", JSONArray())
        ensureDefault(obj)
        cache = obj
        return obj
    }

    private fun ensureDefault(obj: JSONObject) {
        val arr = obj.getJSONArray("profiles")
        var has = false
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == Config.DEFAULT_PROFILE) has = true
        if (!has) {
            arr.put(JSONObject()
                .put("id", Config.DEFAULT_PROFILE)
                .put("name", "Principal")
                .put("theme", DEFAULT_THEME)
                .put("updatedAt", Store.now())
                .put("deleted", false))
        }
    }

    @Synchronized
    fun write(ctx: Context, obj: JSONObject) {
        cache = obj
        prefs(ctx).edit().putString(KEY, obj.toString()).apply()
    }

    @Synchronized
    fun profiles(ctx: Context): List<JSONObject> {
        val arr = index(ctx).getJSONArray("profiles")
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (!p.optBoolean("deleted", false)) out.add(p)
        }
        return out
    }

    @Synchronized
    fun profileById(ctx: Context, id: String): JSONObject? {
        val arr = index(ctx).getJSONArray("profiles")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optString("id") == id) return p
        }
        return null
    }

    fun nameOf(ctx: Context, id: String): String = profileById(ctx, id)?.optString("name") ?: "Perfil"
    fun themeOf(ctx: Context, id: String): String = profileById(ctx, id)?.optString("theme", DEFAULT_THEME) ?: DEFAULT_THEME

    @Synchronized
    fun addProfile(ctx: Context, name: String, theme: String): String {
        val obj = index(ctx)
        val id = Store.uid()
        obj.getJSONArray("profiles").put(JSONObject()
            .put("id", id).put("name", name).put("theme", theme)
            .put("updatedAt", Store.now()).put("deleted", false))
        write(ctx, obj)
        return id
    }

    @Synchronized
    fun renameProfile(ctx: Context, id: String, name: String) {
        val obj = index(ctx)
        val arr = obj.getJSONArray("profiles")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optString("id") == id) p.put("name", name).put("updatedAt", Store.now())
        }
        write(ctx, obj)
    }

    @Synchronized
    fun setTheme(ctx: Context, id: String, theme: String) {
        val obj = index(ctx)
        val arr = obj.getJSONArray("profiles")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optString("id") == id) p.put("theme", theme).put("updatedAt", Store.now())
        }
        write(ctx, obj)
    }

    @Synchronized
    fun deleteProfile(ctx: Context, id: String) {
        if (id == Config.DEFAULT_PROFILE) return  // el Principal no se borra
        val obj = index(ctx)
        val arr = obj.getJSONArray("profiles")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optString("id") == id) p.put("deleted", true).put("updatedAt", Store.now())
        }
        write(ctx, obj)
        Store.clearProfileData(ctx, id)
    }

    // ---- sync ----
    @Synchronized
    fun payload(ctx: Context): JSONObject {
        val r = index(ctx)
        return JSONObject().put("profiles", JSONArray(r.getJSONArray("profiles").toString()))
    }

    @Synchronized
    fun merge(ctx: Context, remote: JSONObject) {
        val obj = index(ctx)
        val local = obj.getJSONArray("profiles")
        val rem = remote.optJSONArray("profiles") ?: JSONArray()
        val idx = HashMap<String, Int>()
        for (i in 0 until local.length()) idx[local.getJSONObject(i).optString("id")] = i
        for (i in 0 until rem.length()) {
            val r = rem.getJSONObject(i)
            val id = r.optString("id", "")
            if (id.isEmpty()) continue
            val at = idx[id]
            if (at == null) { local.put(r); idx[id] = local.length() - 1 }
            else if (r.optLong("updatedAt", 0) > local.getJSONObject(at).optLong("updatedAt", 0)) local.put(at, r)
        }
        write(ctx, obj)
    }
}
