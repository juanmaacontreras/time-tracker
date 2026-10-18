package com.bitacora.timer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fusión "gana el updatedAt más nuevo", común a todos los buckets (dataset de cada
 * perfil, índice de perfiles y registro de dispositivos). Espejo de mergeList/isBehind
 * en web/index.html: si se cambia acá, se cambia allá.
 */
object Merge {

    /** Incorpora a local lo que remote trae nuevo o más reciente. Devuelve true si local cambió. */
    fun list(local: JSONArray, remote: JSONArray?, idKey: String = "id"): Boolean {
        if (remote == null) return false
        val index = indexOf(local, idKey)
        var changed = false
        for (i in 0 until remote.length()) {
            val r = remote.optJSONObject(i) ?: continue
            val id = r.optString(idKey, "")
            if (id.isEmpty()) continue
            val at = index[id]
            if (at == null) {
                local.put(r)
                index[id] = local.length() - 1
                changed = true
            } else if (r.optLong("updatedAt", 0L) > local.getJSONObject(at).optLong("updatedAt", 0L)) {
                local.put(at, r)
                changed = true
            }
        }
        return changed
    }

    /**
     * True si local tiene alguna entrada que remote no tiene, o que tiene más vieja: o sea,
     * si hay que subir. Es la guarda que evita escrituras de más — cada escritura dispara
     * el push a los demás dispositivos — y, a diferencia de recordar "lo último que subí",
     * sobrevive a reinicios del proceso y detecta cuando otro dispositivo pisó la fila con
     * una versión que no tenía lo nuestro.
     */
    fun behind(remote: JSONArray?, local: JSONArray, idKey: String = "id"): Boolean {
        val remoteIndex = if (remote == null) HashMap() else indexOf(remote, idKey)
        for (i in 0 until local.length()) {
            val l = local.getJSONObject(i)
            val at = remoteIndex[l.optString(idKey, "")] ?: return true
            if (l.optLong("updatedAt", 0L) > remote!!.getJSONObject(at).optLong("updatedAt", 0L)) return true
        }
        return false
    }

    private fun indexOf(arr: JSONArray, idKey: String): HashMap<String, Int> {
        val index = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString(idKey, "") ?: continue
            if (id.isNotEmpty()) index[id] = i
        }
        return index
    }
}

/**
 * Resultado de fusionar un bucket remoto. changed: algo local cambió (hay que redibujar).
 * upload: si no es null, el remoto quedó atrás y esto es lo que hay que subir — armado
 * dentro del mismo bloque sincronizado que la fusión, así no se cuela otro cambio en medio.
 */
class MergeResult(val changed: Boolean, val upload: JSONObject?)
