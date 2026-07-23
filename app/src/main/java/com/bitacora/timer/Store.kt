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

    val COLORS = arrayOf("#2F4B8F", "#B5432E", "#2E7D5B", "#8A5A2B", "#6A4C93", "#1F6F8B")

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
        return obj
    }

    private fun newActivity(name: String, type: String, color: String): JSONObject =
        JSONObject()
            .put("id", uid())
            .put("name", name)
            .put("type", type)
            .put("color", color)
            .put("updatedAt", now())
            .put("deleted", false)

    fun write(ctx: Context, obj: JSONObject) {
        prefs(ctx).edit().putString(KEY, obj.toString()).apply()
    }

    // ---------- activities ----------
    fun activities(ctx: Context): List<JSONObject> {
        val arr = root(ctx).getJSONArray("activities")
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (!a.optBoolean("deleted", false)) out.add(a)
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
            obj.put("runActId", "").put("runStart", 0L).put("runChangedAt", now())
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

    fun toggle(ctx: Context, actId: String) {
        val obj = root(ctx)
        val cur = obj.optString("runActId", "")
        if (cur == actId) {
            stopInternal(obj)
        } else {
            if (cur.isNotEmpty()) stopInternal(obj)
            obj.put("runActId", actId).put("runStart", now())
        }
        obj.put("runChangedAt", now())
        write(ctx, obj)
    }

    fun stop(ctx: Context) {
        val obj = root(ctx)
        stopInternal(obj)
        obj.put("runChangedAt", now())
        write(ctx, obj)
    }

    private fun stopInternal(obj: JSONObject) {
        val cur = obj.optString("runActId", "")
        val start = obj.optLong("runStart", 0L)
        if (cur.isNotEmpty() && start > 0) {
            val end = now()
            if (end - start >= 1000) {
                obj.getJSONArray("sessions").put(
                    JSONObject()
                        .put("id", uid())
                        .put("actId", cur)
                        .put("start", start)
                        .put("end", end)
                        .put("updatedAt", now())
                        .put("deleted", false)
                )
            }
        }
        obj.put("runActId", "").put("runStart", 0L)
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
    fun secsFor(ctx: Context, actId: String, from: Long): Long {
        var t = 0L
        val ss = root(ctx).getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            if (s.getString("actId") != actId) continue
            val end = s.getLong("end")
            if (end >= from) t += (end - maxOf(s.getLong("start"), from))
        }
        if (runningActId(ctx) == actId) t += (now() - maxOf(runningStart(ctx), from))
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

    // Total (todas las actividades) dentro de la ventana [from, to), en segundos.
    fun totalBetween(ctx: Context, from: Long, to: Long): Long {
        var t = 0L
        val ss = root(ctx).getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            val a = maxOf(s.getLong("start"), from)
            val b = minOf(s.getLong("end"), to)
            if (b > a) t += (b - a)
        }
        val runId = runningActId(ctx)
        if (runId.isNotEmpty()) {
            val a = maxOf(runningStart(ctx), from)
            val b = minOf(now(), to)
            if (b > a) t += (b - a)
        }
        return t / 1000
    }

    fun periodStart(period: String): Long = when (period) {
        "day" -> startOfToday()
        "week" -> startOfWeek()
        "month" -> startOfMonth()
        else -> 0L
    }

    private fun midnight(c: Calendar): Long {
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- csv ----------
    fun exportCsv(ctx: Context): String {
        val sb = StringBuilder("actividad,tipo,inicio,fin,duracion_min\n")
        val ss = root(ctx).getJSONArray("sessions")
        val list = ArrayList<JSONObject>()
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (!s.optBoolean("deleted", false)) list.add(s)
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
        val rr = remote.optJSONObject("run")
        if (rr != null) {
            val remoteChanged = rr.optLong("changedAt", 0L)
            if (remoteChanged > obj.optLong("runChangedAt", 0L)) {
                obj.put("runActId", rr.optString("actId", ""))
                obj.put("runStart", rr.optLong("start", 0L))
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
