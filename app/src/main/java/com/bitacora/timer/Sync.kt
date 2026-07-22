package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Sync {

    /** Pull + merge + push. Returns true on success. Must be called OFF the main thread. */
    fun syncNow(ctx: Context): Boolean {
        if (!Config.syncEnabled()) return false
        return try {
            val remote = pull()
            if (remote != null) Store.merge(ctx, remote)
            push(Store.payload(ctx))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun base() = Config.SUPABASE_URL.trimEnd('/')

    private fun auth(conn: HttpURLConnection) {
        conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
        // Las llaves nuevas (sb_publishable_...) van solo en "apikey".
        // Las viejas (JWT, empiezan con eyJ) necesitan tambien Authorization.
        if (Config.SUPABASE_KEY.startsWith("eyJ")) {
            conn.setRequestProperty("Authorization", "Bearer ${Config.SUPABASE_KEY}")
        }
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
    }

    private fun pull(): JSONObject? {
        val key = URLEncoder.encode(Config.USER_KEY, "UTF-8")
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

    private fun push(data: JSONObject) {
        val conn = URL("${base()}/rest/v1/buckets?on_conflict=user_key")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        auth(conn)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
        val row = JSONObject()
            .put("user_key", Config.USER_KEY)
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