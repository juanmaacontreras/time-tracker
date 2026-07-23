package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID

object Store {
    private const val PREFS = "bitacora"
    private const val KEY = "data"

    // Paleta apagada tipo "papel/tinta", 14 tonos distinguibles entre sí.
    val COLORS = arrayOf(
        "#2F4B8F", "#B5432E", "#2E7D5B", "#8A5A2B", "#6A4C93", "#1F6F8B", "#9C4368",
        "#3E6B2F", "#B08A1E", "#4A5A78", "#7A3E9C", "#2B7A78", "#A6552E", "#5B6BB5"
    )

    fun now(): Long = System.currentTimeMillis()
    fun uid(): String = UUID.randomUUID().toString()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun root(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY, null)
        val obj = if (raw != null) JSONObject(raw) else JSONObject()
        if (!obj.has("activities")) obj.put("activities", JSONArray())
        if (!obj.has("sessions")) obj.put("sessions", JSONArray())
        if (!obj.has("runActId")) obj.put("runActId", "")
        if (!obj.has("runStart")) obj.put("runStart", 0L)
        if (!obj.has("runChangedAt")) obj.put("runChangedAt", 0L)
        if (!obj.has("runPaused")) obj.put("runPaused", false)
        if (!obj.has("runPausedAt")) obj.put("runPausedAt", 0L)
        if (!obj.has("runPausedAccum")) obj.put("runPausedAccum", 0L)
        return obj
    }

    private fun newActivity(name: String, type: String, color: String): JSONObject =
        JSONObject()
            .put("id", uid())
            .put("name", name)
            .put("type", type)
            .put("color", color)
            .put("archived", false)
            .put("updatedAt", now())
            .put("deleted", false)

    fun write(ctx: Context, obj: JSONObject) {
        prefs(ctx).edit().putString(KEY, obj.toString()).apply()
    }

    // ---------- activities ----------
    // Por defecto excluye borradas y archivadas. La pestaña Resumen pasa
    // includeArchived=true para que el historial archivado siga contando.
    fun activities(ctx: Context, includeArchived: Boolean = false): List<JSONObject> {
        val arr = root(ctx).getJSONArray("activities")
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.optBoolean("deleted", false)) continue
            if (!includeArchived && a.optBoolean("archived", false)) continue
            out.add(a)
        }
        return out
    }

    fun addActivity(ctx: Context, name: String, type: String, color: String) {
        val obj = root(ctx)
        obj.getJSONArray("activities").put(newActivity(name, type, color))
        write(ctx, obj)
    }

    fun updateActivity(ctx: Context, id: String, name: String, type: String, color: String) {
        val obj = root(ctx)
        val arr = obj.getJSONArray("activities")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("id") == id) {
                a.put("name", name).put("type", type).put("color", color).put("updatedAt", now())
            }
        }
        write(ctx, obj)
    }

    fun deleteActivity(ctx: Context, id: String) {
        val obj = root(ctx)
        val arr = obj.getJSONArray("activities")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("id") == id) a.put("deleted", true).put("updatedAt", now())
        }
        val ss = obj.getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.getString("actId") == id) s.put("deleted", true).put("updatedAt", now())
        }
        if (obj.optString("runActId", "") == id) {
            obj.put("runActId", "").put("runStart", 0L)
                .put("runPaused", false).put("runPausedAt", 0L).put("runPausedAccum", 0L)
                .put("runChangedAt", now())
        }
        write(ctx, obj)
    }

    fun archiveActivity(ctx: Context, id: String) {
        setArchived(ctx, id, true)
    }

    fun unarchiveActivity(ctx: Context, id: String) {
        setArchived(ctx, id, false)
    }

    private fun setArchived(ctx: Context, id: String, value: Boolean) {
        val obj = root(ctx)
        val arr = obj.getJSONArray("activities")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("id") == id) a.put("archived", value).put("updatedAt", now())
        }
        // Si se archiva la que está corriendo, se corta (sin borrar su historial).
        if (value && obj.optString("runActId", "") == id) {
            stopInternal(obj)
            obj.put("runChangedAt", now())
        }
        write(ctx, obj)
    }

    fun activityById(ctx: Context, id: String): JSONObject? {
        val arr = root(ctx).getJSONArray("activities")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("id") == id) return a
        }
        return null
    }

    // ---------- running ----------
    fun runningActId(ctx: Context): String = root(ctx).optString("runActId", "")
    fun runningStart(ctx: Context): Long = root(ctx).optLong("runStart", 0L)
    fun runningPaused(ctx: Context): Boolean = root(ctx).optBoolean("runPaused", false)
    fun runningPausedAt(ctx: Context): Long = root(ctx).optLong("runPausedAt", 0L)
    fun runningPausedAccum(ctx: Context): Long = root(ctx).optLong("runPausedAccum", 0L)

    // Milisegundos reales corridos de la sesión activa (descontando pausas). 0 si no hay nada.
    fun runningElapsedMs(ctx: Context): Long {
        if (runningActId(ctx).isEmpty()) return 0L
        val end = if (runningPaused(ctx)) runningPausedAt(ctx) else now()
        return (end - runningStart(ctx) - runningPausedAccum(ctx)).coerceAtLeast(0L)
    }

    // Tocar la actividad que ya está corriendo (o pausada) la para del todo.
    // Pausar/resumir es una acción explícita aparte (ver Store.pause/resume), nunca
    // pasa por acá — así el toque tiene un solo significado en toda la app y los widgets.
    // Tocar otra actividad distinta corta la anterior (con su tiempo real) y arranca la nueva.
    fun toggle(ctx: Context, actId: String) {
        val obj = root(ctx)
        val cur = obj.optString("runActId", "")
        if (cur == actId) {
            stopInternal(obj)
        } else {
            if (cur.isNotEmpty()) stopInternal(obj)
            obj.put("runActId", actId).put("runStart", now())
                .put("runPaused", false).put("runPausedAt", 0L).put("runPausedAccum", 0L)
        }
        obj.put("runChangedAt", now())
        write(ctx, obj)
    }

    fun pause(ctx: Context) {
        val obj = root(ctx)
        if (obj.optString("runActId", "").isNotEmpty() && !obj.optBoolean("runPaused", false)) {
            pauseInternal(obj)
            obj.put("runChangedAt", now())
            write(ctx, obj)
        }
    }

    fun resume(ctx: Context) {
        val obj = root(ctx)
        if (obj.optString("runActId", "").isNotEmpty() && obj.optBoolean("runPaused", false)) {
            resumeInternal(obj)
            obj.put("runChangedAt", now())
            write(ctx, obj)
        }
    }

    fun stop(ctx: Context) {
        val obj = root(ctx)
        stopInternal(obj)
        obj.put("runChangedAt", now())
        write(ctx, obj)
    }

    private fun pauseInternal(obj: JSONObject) {
        obj.put("runPaused", true).put("runPausedAt", now())
    }

    private fun resumeInternal(obj: JSONObject) {
        val pausedAt = obj.optLong("runPausedAt", 0L)
        if (pausedAt > 0) {
            obj.put("runPausedAccum", obj.optLong("runPausedAccum", 0L) + (now() - pausedAt))
        }
        obj.put("runPaused", false).put("runPausedAt", 0L)
    }

    private fun stopInternal(obj: JSONObject) {
        val cur = obj.optString("runActId", "")
        val start = obj.optLong("runStart", 0L)
        if (cur.isNotEmpty() && start > 0) {
            // Fórmula canónica compartida con la webapp: el tiempo pausado no se cuenta.
            val paused = obj.optBoolean("runPaused", false)
            val currentEnd = if (paused) obj.optLong("runPausedAt", 0L) else now()
            val real = currentEnd - start - obj.optLong("runPausedAccum", 0L)
            if (real >= 1000) {
                obj.getJSONArray("sessions").put(
                    JSONObject()
                        .put("id", uid())
                        .put("actId", cur)
                        .put("start", start)
                        .put("end", start + real)
                        .put("updatedAt", now())
                        .put("deleted", false)
                )
            }
        }
        obj.put("runActId", "").put("runStart", 0L)
            .put("runPaused", false).put("runPausedAt", 0L).put("runPausedAccum", 0L)
    }

    // ---------- sessions ----------
    fun addSession(ctx: Context, actId: String, start: Long, end: Long) {
        val obj = root(ctx)
        obj.getJSONArray("sessions").put(
            JSONObject().put("id", uid()).put("actId", actId)
                .put("start", start).put("end", end)
                .put("updatedAt", now()).put("deleted", false)
        )
        write(ctx, obj)
    }

    fun deleteSession(ctx: Context, id: String) {
        val obj = root(ctx)
        val ss = obj.getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.getString("id") == id) s.put("deleted", true).put("updatedAt", now())
        }
        write(ctx, obj)
    }

    // Sesiones de una actividad que terminaron hoy (incluye las que empezaron ayer
    // y quedaron corriendo hasta que se pararon hoy).
    fun sessionsForActivityToday(ctx: Context, actId: String): List<JSONObject> {
        val from = startOfToday()
        val ss = root(ctx).getJSONArray("sessions")
        val list = ArrayList<JSONObject>()
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            if (s.getString("actId") != actId) continue
            if (s.getLong("end") >= from) list.add(s)
        }
        list.sortByDescending { it.getLong("start") }
        return list
    }

    fun recentSessions(ctx: Context, limit: Int): List<JSONObject> {
        val ss = root(ctx).getJSONArray("sessions")
        val list = ArrayList<JSONObject>()
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (!s.optBoolean("deleted", false)) list.add(s)
        }
        list.sortByDescending { it.getLong("start") }
        return if (list.size > limit) list.subList(0, limit) else list
    }

    // ---------- stats ----------
    // "to" por defecto es infinito (todo lo transcurrido desde "from" hasta ahora),
    // que es el comportamiento de siempre. Se puede acotar para navegar a períodos
    // pasados sin arrastrar tiempo posterior a ese período.
    fun secsFor(ctx: Context, actId: String, from: Long, to: Long = Long.MAX_VALUE): Long {
        var t = 0L
        val ss = root(ctx).getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            if (s.getString("actId") != actId) continue
            val a = maxOf(s.getLong("start"), from)
            val b = minOf(s.getLong("end"), to)
            if (b > a) t += (b - a)
        }
        if (runningActId(ctx) == actId) {
            val end = if (runningPaused(ctx)) runningPausedAt(ctx) else now()
            val a = maxOf(runningStart(ctx), from)
            val b = minOf(end, to)
            val c = (b - a) - runningPausedAccum(ctx)
            if (c > 0) t += c
        }
        return t / 1000
    }

    fun startOfToday(): Long = midnight(Calendar.getInstance())

    fun startOfWeek(): Long {
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        c.add(Calendar.DAY_OF_MONTH, -back)
        return midnight(c)
    }

    fun startOfMonth(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        return midnight(c)
    }

    // Total dentro de la ventana [from, to), en segundos. Si actIds no es null,
    // solo suma las actividades incluidas en ese set.
    fun totalBetween(ctx: Context, from: Long, to: Long, actIds: Set<String>? = null): Long {
        var t = 0L
        val ss = root(ctx).getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            if (actIds != null && s.getString("actId") !in actIds) continue
            val a = maxOf(s.getLong("start"), from)
            val b = minOf(s.getLong("end"), to)
            if (b > a) t += (b - a)
        }
        val runId = runningActId(ctx)
        if (runId.isNotEmpty() && (actIds == null || runId in actIds)) {
            val end = if (runningPaused(ctx)) runningPausedAt(ctx) else now()
            val a = maxOf(runningStart(ctx), from)
            val b = minOf(end, to)
            if (b > a) t += (b - a - runningPausedAccum(ctx)).coerceAtLeast(0L)
        }
        return t / 1000
    }

    fun periodStart(period: String): Long = when (period) {
        "day" -> startOfToday()
        "week" -> startOfWeek()
        "month" -> startOfMonth()
        else -> 0L
    }

    // Rango [from, to) de un período con offset respecto del actual: 0 = el actual,
    // -1 = el anterior, -2 = dos atrás, etc. Es la base de la navegación "◀ ▶" del Resumen.
    fun periodRange(period: String, offset: Int): Pair<Long, Long> = when (period) {
        "day" -> {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_MONTH, offset)
            val from = midnight(c)
            from to (from + DAY_MS)
        }
        "week" -> {
            val c = Calendar.getInstance()
            c.add(Calendar.WEEK_OF_YEAR, offset)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
            c.add(Calendar.DAY_OF_MONTH, -back)
            val from = midnight(c)
            from to (from + 7 * DAY_MS)
        }
        "month" -> {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, offset)
            c.set(Calendar.DAY_OF_MONTH, 1)
            val from = midnight(c)
            val c2 = c.clone() as Calendar
            c2.add(Calendar.MONTH, 1)
            from to midnight(c2)
        }
        else -> 0L to now()
    }

    // Cantidad de días del mes al que pertenece "from" (para recorrer un mes pasado completo).
    fun daysInMonth(from: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = from
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private const val DAY_MS = 86400000L

    private fun midnight(c: Calendar): Long {
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- csv ----------
    // Si from/to/actIds se pasan, exporta solo las sesiones que caen en ese rango
    // y pertenecen a esas actividades (para respetar el filtro activo del resumen).
    fun exportCsv(
        ctx: Context,
        from: Long = 0L,
        to: Long = Long.MAX_VALUE,
        actIds: Set<String>? = null
    ): String {
        val sb = StringBuilder("actividad,tipo,inicio,fin,duracion_min\n")
        val ss = root(ctx).getJSONArray("sessions")
        val list = ArrayList<JSONObject>()
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            if (actIds != null && s.getString("actId") !in actIds) continue
            // Se incluye si la sesión se solapa con [from, to).
            if (s.getLong("end") < from || s.getLong("start") >= to) continue
            list.add(s)
        }
        list.sortBy { it.getLong("start") }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (s in list) {
            val a = activityById(ctx, s.getString("actId"))
            val name = a?.getString("name") ?: "?"
            val type = a?.optString("type", "") ?: ""
            val min = (s.getLong("end") - s.getLong("start")) / 60000.0
            sb.append("\"$name\",\"$type\",\"${fmt.format(s.getLong("start"))}\",")
            sb.append("\"${fmt.format(s.getLong("end"))}\",${String.format(Locale.US, "%.1f", min)}\n")
        }
        return sb.toString()
    }

    // ---------- sync payload + merge ----------
    fun payload(ctx: Context): JSONObject {
        val r = root(ctx)
        val run = JSONObject()
            .put("actId", r.optString("runActId", ""))
            .put("start", r.optLong("runStart", 0L))
            .put("paused", r.optBoolean("runPaused", false))
            .put("pausedAt", r.optLong("runPausedAt", 0L))
            .put("pausedAccum", r.optLong("runPausedAccum", 0L))
            .put("changedAt", r.optLong("runChangedAt", 0L))
        return JSONObject()
            .put("activities", r.getJSONArray("activities"))
            .put("sessions", r.getJSONArray("sessions"))
            .put("run", run)
    }

    fun merge(ctx: Context, remote: JSONObject) {
        val obj = root(ctx)
        mergeList(obj.getJSONArray("activities"), remote.optJSONArray("activities"))
        mergeList(obj.getJSONArray("sessions"), remote.optJSONArray("sessions"))
        // El estado "corriendo" cruza entre dispositivos: gana el cambio mas nuevo.
        // El bloque run se reemplaza entero, nunca campo por campo.
        val rr = remote.optJSONObject("run")
        if (rr != null) {
            val remoteChanged = rr.optLong("changedAt", 0L)
            if (remoteChanged > obj.optLong("runChangedAt", 0L)) {
                obj.put("runActId", rr.optString("actId", ""))
                obj.put("runStart", rr.optLong("start", 0L))
                obj.put("runPaused", rr.optBoolean("paused", false))
                obj.put("runPausedAt", rr.optLong("pausedAt", 0L))
                obj.put("runPausedAccum", rr.optLong("pausedAccum", 0L))
                obj.put("runChangedAt", remoteChanged)
            }
        }
        write(ctx, obj)
    }

    private fun mergeList(local: JSONArray, remote: JSONArray?) {
        if (remote == null) return
        val index = HashMap<String, Int>()
        for (i in 0 until local.length()) index[local.getJSONObject(i).getString("id")] = i
        for (i in 0 until remote.length()) {
            val r = remote.getJSONObject(i)
            val id = r.optString("id", "")
            if (id.isEmpty()) continue
            val at = index[id]
            if (at == null) {
                local.put(r)
                index[id] = local.length() - 1
            } else {
                val l = local.getJSONObject(at)
                if (r.optLong("updatedAt", 0) > l.optLong("updatedAt", 0)) local.put(at, r)
            }
        }
    }

    // ---------- formatting helpers ----------
    fun human(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m"
            else -> "${sec}s"
        }
    }

    // Valor exacto siempre: H:MM:SS si hay horas, si no M:SS.
    fun exact(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0)
            "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else
            "$m:${s.toString().padStart(2, '0')}"
    }
}
