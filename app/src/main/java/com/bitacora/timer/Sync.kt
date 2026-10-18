package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sincronización contra Supabase. Todas las funciones públicas hacen red: llamarlas
 * fuera del hilo principal. Todas devuelven true si algo local cambió (hay que redibujar).
 *
 * Cada bucket sigue el mismo ciclo: bajar → fusionar → subir SOLO si el remoto quedó
 * atrás (ver Merge.behind). Nunca subir sin necesidad es la regla más importante desde
 * que existe el push: cada escritura dispara el trigger que despierta a los demás
 * dispositivos.
 */
object Sync {

    const val DEFAULT_TIMEOUT_MS = 12000

    /** Índice de perfiles + perfil activo. */
    fun syncNow(ctx: Context): Boolean {
        val index = syncIndex(ctx)
        val data = syncProfile(ctx)
        return index || data
    }

    /**
     * Solo el perfil activo. timeoutMs se acorta en el camino del push: al recibir un
     * mensaje FCM el sistema da del orden de 10 segundos antes de poder matar el proceso.
     *
     * El perfil se fija UNA vez, acá: si el usuario cambia de perfil mientras se baja el
     * remoto, Store.merge lo detecta y descarta el ciclo entero (ver su comentario).
     */
    fun syncProfile(ctx: Context, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean {
        val pid = Store.currentProfileId(ctx)
        return syncBucket(Config.bucketKey(pid), timeoutMs) { remote -> Store.merge(ctx, pid, remote) }
    }

    /** Solo el índice de perfiles (pantalla de selección, renombres y temas). */
    fun syncIndex(ctx: Context): Boolean =
        syncBucket(Config.INDEX_KEY, DEFAULT_TIMEOUT_MS) { remote -> ProfileStore.merge(ctx, remote) }

    /**
     * Registra este dispositivo en el bucket de tokens.
     *
     * El orden importa y es la parte delicada: primero se BAJA el registro remoto y se
     * fusiona, y recién después se registra el token local. Así, si el servidor marcó
     * esta entrada como borrada — cosa que hace la Edge Function cuando FCM le contesta
     * que el token está muerto —, la app se entera y se vuelve a registrar. Al revés el
     * dispositivo nunca se enteraría de esa baja y el push quedaría roto, en silencio.
     * Devices.mergeAndRegister hace las dos cosas en ese orden.
     */
    fun syncDevices(ctx: Context, token: String) {
        syncBucket(Config.DEVICES_KEY, DEFAULT_TIMEOUT_MS) { remote -> Devices.mergeAndRegister(ctx, remote, token) }
    }

    private fun syncBucket(userKey: String, timeoutMs: Int, merge: (JSONObject?) -> MergeResult?): Boolean {
        if (!Config.syncEnabled()) return false
        // Si falla la bajada no se sube nada: subir a ciegas pisaría la fila remota con
        // una versión que no incluye lo que otros dispositivos subieron.
        val result = (try { merge(pull(userKey, timeoutMs)) } catch (e: Exception) { null }) ?: return false
        val upload = result.upload
        if (upload != null) {
            // Si falla, no se pierde nada: el remoto sigue atrás y el próximo ciclo
            // vuelve a detectarlo y a subir.
            try { push(userKey, upload, timeoutMs) } catch (e: Exception) {}
        }
        return result.changed
    }

    private fun base() = Config.SUPABASE_URL.trimEnd('/')

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
        if (Config.SUPABASE_KEY.startsWith("eyJ")) {
            conn.setRequestProperty("Authorization", "Bearer ${Config.SUPABASE_KEY}")
        }
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn
    }

    /** null = el bucket todavía no existe. Cualquier error de red o HTTP lanza excepción. */
    private fun pull(userKey: String, timeoutMs: Int): JSONObject? {
        val key = URLEncoder.encode(userKey, "UTF-8")
        val conn = open("${base()}/rest/v1/buckets?user_key=eq.$key&select=data", timeoutMs)
        try {
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("pull $code")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            return if (arr.length() == 0) null else arr.getJSONObject(0).optJSONObject("data")
        } finally {
            conn.disconnect()
        }
    }

    private fun push(userKey: String, data: JSONObject, timeoutMs: Int) {
        val conn = open("${base()}/rest/v1/buckets?on_conflict=user_key", timeoutMs)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            val row = JSONObject()
                .put("user_key", userKey)
                .put("data", data)
                .put("updated_at", Store.now())
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(JSONArray().put(row).toString()) }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("push $code")
        } finally {
            conn.disconnect()
        }
    }
}
