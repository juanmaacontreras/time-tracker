package com.bitacora.timer

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Supabase Realtime mientras la app está en pantalla. Espejo de `Live` en
 * web/index.html: mismo protocolo (Phoenix vsn 2.0.0, cada mensaje es un array
 * [join_ref, ref, topic, event, payload]) y mismo criterio — el aviso es un "timbre",
 * se ignora lo que trae y se sale a buscar por el camino normal de Sync, que ya sabe
 * fusionar y descartar lo de otro perfil.
 *
 * Por qué además del push: el push pasa por trigger → Edge Function → FCM → teléfono,
 * y eso tarda 3-4 s. Con la app abierta, un WebSocket directo llega en menos de 1 s.
 * Con la app en segundo plano Android no deja sostener una conexión abierta, y ahí el
 * push sigue siendo la única vía: este objeto vive entre onResume y onPause, nada más.
 *
 * Todo el estado se toca solo desde el hilo principal: los callbacks de OkHttp llegan
 * en sus propios hilos y se pasan al main con `main.post`.
 */
object Live {
    @Volatile var ok = false
        private set

    private val client by lazy {
        // readTimeout 0: un WebSocket pasa mucho tiempo sin recibir nada y eso es normal.
        // El ping de OkHttp detecta una conexión muerta sin aviso (cambio de red, NAT).
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
    private val main = Handler(Looper.getMainLooper())

    private var ws: WebSocket? = null
    private var keys: List<String> = emptyList()
    private var onChange: (() -> Unit)? = null
    private var active = false
    private var ref = 0
    private var topic = ""
    private var retry = 0

    private const val HEARTBEAT_MS = 25000L  // Phoenix corta un socket callado 60 s
    private const val DEBOUNCE_MS = 150L

    private val heartbeat = object : Runnable {
        override fun run() {
            send(null, "phoenix", "heartbeat", JSONObject())
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }
    private val reconnect = Runnable { connect() }
    private val fire = Runnable { onChange?.invoke() }

    /** Escucha esos buckets y llama a onChange (en el main) cuando cambian. */
    fun start(watch: List<String>, onChange: () -> Unit) {
        if (!Config.syncEnabled()) return
        this.onChange = onChange
        active = true
        if (watch == keys && ws != null) return
        keys = watch
        if (ws != null) join() else connect()
    }

    /** Cierra todo. Suelta onChange: este objeto es global y no debe retener la Activity. */
    fun stop() {
        active = false
        onChange = null
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(reconnect)
        main.removeCallbacks(fire)
        ws?.close(1000, null)
        ws = null
        topic = ""
        ok = false
    }

    private fun connect() {
        if (!active) return
        ws?.cancel()
        val url = Config.SUPABASE_URL + "/realtime/v1/websocket?apikey=" +
            URLEncoder.encode(Config.SUPABASE_KEY, "UTF-8") + "&vsn=2.0.0"
        topic = ""
        ws = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    // Cada socket tiene su propio listener y todo callback chequea que siga siendo el
    // actual: los avisos tardíos de un socket ya descartado se ignoran.
    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (ws !== webSocket) return@post
                join()
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, HEARTBEAT_MS)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { if (ws === webSocket) handle(webSocket, text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { if (ws === webSocket) drop(webSocket) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post { if (ws === webSocket) drop(webSocket) }
        }
    }

    private fun handle(socket: WebSocket, text: String) {
        val m = try { JSONArray(text) } catch (e: Exception) { return }
        if (m.optString(2) != topic) return
        val event = m.optString(3)
        val payload = m.optJSONObject(4)
        // Un rechazo de la suscripción reconecta con espera creciente (la web antes no lo
        // hacía y quedaba en el sondeo lento hasta recargar la página).
        val failed = (event == "phx_reply" || event == "system") &&
            payload != null && payload.optString("status") != "ok"
        when {
            failed -> drop(socket)
            event == "system" && payload != null -> {
                // Suscripción confirmada: el reintento vuelve a empezar de 1 s, y se
                // sincroniza una vez por lo que haya cambiado sin conexión.
                val wasOk = ok
                ok = true
                retry = 0
                if (!wasOk) ring()
            }
            event == "postgres_changes" -> { ok = true; ring() }
        }
    }

    private fun ring() {
        main.removeCallbacks(fire)
        main.postDelayed(fire, DEBOUNCE_MS)
    }

    private fun drop(socket: WebSocket) {
        socket.cancel()
        ws = null
        ok = false
        main.removeCallbacks(heartbeat)
        if (!active) return
        // retry se resetea recién con la suscripción confirmada, no al abrir el socket:
        // si el servidor acepta la conexión y rechaza la suscripción, reintentaría cada
        // 1 s para siempre. Espera: 1 s, 2 s, 4 s... hasta 60 s.
        val wait = minOf(60000L, 1000L shl minOf(retry, 6))
        retry++
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, wait)
    }

    private fun join() {
        if (topic.isNotEmpty()) send(null, topic, "phx_leave", JSONObject())
        ok = false
        ref++
        topic = "realtime:bitacora-$ref"
        val changes = JSONArray()
        for (k in keys) {
            changes.put(JSONObject()
                .put("event", "*").put("schema", "public").put("table", "buckets")
                .put("filter", "user_key=eq.$k"))
        }
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false))
            .put("presence", JSONObject().put("key", "").put("enabled", false))
            .put("postgres_changes", changes)
            .put("private", false)
        send(ref.toString(), topic, "phx_join", JSONObject().put("config", config))
    }

    private fun send(joinRef: String?, topic: String, event: String, payload: JSONObject) {
        val msg = JSONArray()
            .put(joinRef ?: JSONObject.NULL)
            .put((++ref).toString())
            .put(topic).put(event).put(payload)
        ws?.send(msg.toString())
    }
}
