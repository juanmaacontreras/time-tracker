package com.bitacora.timer

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Sesiones de lectura y escucha que llegan de Atril (el lector de EPUBs, otro proyecto).
 *
 * Atril publica cada 15 minutos su "bandeja" en el bucket USER_KEY::atril (ver
 * docs/atril.md): los libros (id, título, autor, materia) y las sesiones de los últimos 60
 * días. Atril es el único que escribe ese bucket; Bitácora solo lo lee. Este objeto lo
 * convierte en actividades y sesiones normales y las pasa por Merge.list, la misma fusión
 * de siempre, así que todo lo demás (subir, otros dispositivos, la web) no cambia.
 *
 * Pura y determinística: dos dispositivos con los mismos datos arman exactamente las mismas
 * entradas (mismos ids y mismos updatedAt), por eso no se duplican ni se pisan en bucle.
 * Espejo de AtrilImport en web/index.html; los casos de tests/fixtures/shared.json
 * ("atril") corren contra los dos.
 *
 * A qué actividad va cada libro:
 *  1. Si el libro trae materia (los del resumidor), a la actividad con ese nombre.
 *  2. Si no, a una actividad que se llame como el libro ("Bóvedas de acero" encuentra
 *     "Bovédas de Acero (Isaac Asimov)": se comparan sin tildes, sin lo que va entre
 *     paréntesis y sin palabras de una o dos letras).
 *  3. Si no hay ninguna, a una actividad nueva "Título (Autor)" en la categoría Libro.
 * Una sesión de Atril que se superpone con una sesión cargada a mano en la misma actividad
 * queda borrada: ese rato ya lo contó el cronómetro.
 */
object AtrilImport {
    const val VERSION = 1
    const val SESSION_PREFIX = "atril-"
    const val BOOK_PREFIX = "atril-book-"
    private const val MIN_SECONDS = 60L

    /** Nombre comparable: sin tildes, sin "(…)", sin signos y sin palabras de ≤ 2 letras. */
    internal fun key(s: String): String {
        val plain = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
        return plain.split(' ').filter { it.length > 2 }.joinToString(" ")
    }

    private fun live(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { !it.optBoolean("deleted", false) }

    /** La actividad viva cuyo nombre coincide; si hay varias: no archivada, no creada por Atril, menor id. */
    private fun activityNamed(acts: List<JSONObject>, name: String): String? {
        val k = key(name)
        if (k.isEmpty()) return null
        return acts.filter { key(it.optString("name", "")) == k }
            .sortedWith(compareBy<JSONObject>(
                { it.optBoolean("archived", false) },
                { it.optString("id").startsWith(SESSION_PREFIX) },
                { it.optString("id") },
            ))
            .firstOrNull()?.optString("id")
    }

    /** Incorpora la bandeja a obj. Devuelve true si obj cambió. */
    fun fold(obj: JSONObject, inbox: JSONObject?, profileId: String): Boolean {
        if (inbox == null || inbox.optInt("v", 0) != VERSION || inbox.optString("profile", "") != profileId) return false
        val acts = live(obj.getJSONArray("activities"))
        val libro = live(obj.getJSONArray("categories"))
            .filter { key(it.optString("name", "")) == "libro" }
            .minByOrNull { it.optString("id") }?.optString("id") ?: ""

        val newActs = JSONArray()
        val actOf = HashMap<String, String>()
        val books = inbox.optJSONArray("books") ?: JSONArray()
        for (i in 0 until books.length()) {
            val b = books.optJSONObject(i) ?: continue
            val id = b.optString("id", "")
            if (id.isEmpty()) continue
            val title = b.optString("title", "")
            val author = b.optString("author", "").takeIf { !b.isNull("author") } ?: ""
            val subject = if (b.isNull("subject")) "" else b.optString("subject", "")
            val found = (if (subject.isNotEmpty()) activityNamed(acts, subject) else null) ?: activityNamed(acts, title)
            if (found != null) { actOf[id] = found; continue }
            val actId = BOOK_PREFIX + id
            actOf[id] = actId
            val hash = Store.stableCategoryId(id).removePrefix("cat-").toLong(16)
            newActs.put(JSONObject()
                .put("id", actId)
                .put("name", if (author.isNotEmpty()) "$title ($author)" else title)
                .put("type", libro)
                .put("color", Store.COLORS[(hash % Store.COLORS.size).toInt()])
                .put("archived", false)
                .put("updatedAt", 1L)  // lo mínimo: cualquier cambio tuyo le gana
                .put("deleted", false))
        }
        var changed = Merge.list(obj.getJSONArray("activities"), newActs)

        val all = obj.getJSONArray("sessions")
        val manual = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .filter { !it.optString("id", "").startsWith(SESSION_PREFIX) }
        val newSessions = JSONArray()
        val sessions = inbox.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            val actId = actOf[s.optString("bookId", "")] ?: continue
            val seconds = s.optLong("seconds", 0L)
            if (seconds < MIN_SECONDS || s.optString("id", "").isEmpty()) continue
            val start = s.optLong("start", 0L)
            val end = start + seconds * 1000
            // Sesiones a mano de la misma actividad que se cruzan, vivas o borradas: el
            // updatedAt de la importada sigue al más nuevo de ellas, así borrar o agregar
            // una a mano siempre le gana a lo que había.
            var stamp = s.optLong("updatedAt", 0L)
            var covered = false
            for (m in manual) {
                if (m.optString("actId") != actId) continue
                if (m.optLong("start", 0L) >= end || m.optLong("end", 0L) <= start) continue
                stamp = maxOf(stamp, m.optLong("updatedAt", 0L))
                if (!m.optBoolean("deleted", false)) covered = true
            }
            newSessions.put(JSONObject()
                .put("id", SESSION_PREFIX + s.getString("id"))
                .put("actId", actId)
                .put("start", start)
                .put("end", end)
                .put("updatedAt", stamp + if (covered) 1 else 0)
                .put("deleted", covered))
        }
        changed = Merge.list(all, newSessions) || changed
        return changed
    }
}
