package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Sync {

    private fun currentDataKey(ctx: Context) = Config.bucketKey(Store.currentProfileId(ctx))

    /** Pull + merge + push del índice de perfiles y del perfil activo. Fuera del hilo principal. */
    fun syncNow(ctx: Context): Boolean {
        if (!Config.syncEnabled()) return false
        var ok = true
        ok = syncBucket(Config.INDEX_KEY, { ProfileStore.payload(ctx) }, { r -> ProfileStore.merge(ctx, r) }) && ok
        ok = syncBucket(currentDataKey(ctx), { Store.payload(ctx) }, { r -> Store.merge(ctx, r) }) && ok
        return ok
    }

    /** Solo baja el estado del perfil activo y lo fusiona (sin subir). Fuera del hilo principal. */
    fun pullMerge(ctx: Context): Boolean {
        if (!Config.syncEnabled()) return false
        return try {
            val remote = pull(currentDataKey(ctx))
            if (remote != null) Store.merge(ctx, remote)
            true
        } catch (e: Exception) { false }
    }

    /** Solo sube el estado del perfil activo. Fuera del hilo principal. */
    fun pushOnly(ctx: Context): Boolean {
        if (!Config.syncEnabled()) return false
        return try { push(currentDataKey(ctx), Store.payload(ctx)); true } catch (e: Exception) { false }
    }

    /** Baja y fusiona solo el índice de perfiles (para la pantalla de selección). */
    fun pullIndex(ctx: Context): Boolean {
        if (!Config.syncEnabled()) return false
        return try {
            val remote = pull(Config.INDEX_KEY)
            if (remote != null) ProfileStore.merge(ctx, remote)
            true
        } catch (e: Exception) { false }
    }

    /** Baja el dataset crudo de un bucket cualquiera (para previsualizar un perfil sin activarlo). */
    fun pullBucketRaw(userKey: String): JSONObject? =
        try { pull(userKey) } catch (e: Exception) { null }

    private fun syncBucket(userKey: String, payloadFn: () -> JSONObject, mergeFn: (JSONObject) -> Unit): Boolean {
        return try {
            val remote = pull(userKey)
            if (remote != null) mergeFn(remote)
            push(userKey, payloadFn())
            true
        } catch (e: Exception) { false }
    }

    private fun base() = Config.SUPABASE_URL.trimEnd('/')

    private fun auth(conn: HttpURLConnection) {
        conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
        if (Config.SUPABASE_KEY.startsWith("eyJ")) {
            conn.setRequestProperty("Authorization", "Bearer ${Config.SUPABASE_KEY}")
        }
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
    }

    private fun pull(userKey: String): JSONObject? {
        val key = URLEncoder.encode(userKey, "UTF-8")
        val conn = URL("${base()}/rest/v1/buckets?user_key=eq.$key&select=data")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        auth(conn)
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); return null }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).optJSONObject("data")
    }

    private fun push(userKey: String, data: JSONObject) {
        val conn = URL("${base()}/rest/v1/buckets?on_conflict=user_key")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        auth(conn)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
        val row = JSONObject()
            .put("user_key", userKey)
            .put("data", data)
            .put("updated_at", Store.now())
        val payload = JSONArray().put(row).toString()
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload) }
        val code = conn.responseCode
        try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) {}
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("push $code")
    }
}
