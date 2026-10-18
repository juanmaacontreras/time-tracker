package com.bitacora.timer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Corre los casos de tests/fixtures/shared.json contra la lógica pura de Store. La web
 * corre los mismos casos (tests/web.test.js): si un lado cambia y el otro no, alguno de
 * los dos falla, en vez de que la sincronización empiece a perder datos en silencio.
 */
class SharedFixturesTest {

    private val fixtures = JSONObject(File("../tests/fixtures/shared.json").readText())

    // Mismo relleno de campos que Store.root() para un dataset recién cargado.
    private fun dataset(src: JSONObject?): JSONObject {
        val o = JSONObject((src ?: JSONObject()).toString())
        for (k in listOf("activities", "sessions", "categories")) if (!o.has(k)) o.put(k, JSONArray())
        if (!o.has("runActId")) o.put("runActId", "")
        for (k in listOf("runStart", "runChangedAt", "runPausedAt", "runPausedAccum")) if (!o.has(k)) o.put(k, 0L)
        if (!o.has("runPaused")) o.put("runPaused", false)
        return o
    }

    private fun byId(arr: JSONArray): Map<String, JSONObject> =
        (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("id") }

    @Test
    fun categoryIds() {
        val cases = fixtures.getJSONArray("categoryIds")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("name"), c.getString("id"), Store.stableCategoryId(c.getString("name")))
        }
    }

    @Test
    fun merge() {
        val cases = fixtures.getJSONArray("merge")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val obj = dataset(c.getJSONObject("local"))
            val remote = c.optJSONObject("remote")
            val exp = c.getJSONObject("expect")
            assertEquals("$name: changed", exp.getBoolean("changed"), Store.mergeData(obj, remote))
            assertEquals("$name: behind", exp.getBoolean("behind"), Store.remoteBehind(obj, remote))
            exp.optJSONObject("counts")?.let { counts ->
                for (k in counts.keys()) assertEquals("$name: count $k", counts.getInt(k), obj.getJSONArray(k).length())
            }
            for (kind in listOf("activities", "sessions", "categories")) {
                val want = exp.optJSONObject(kind) ?: continue
                val have = byId(obj.getJSONArray(kind))
                for (id in want.keys()) {
                    val fields = want.getJSONObject(id)
                    val entry = have[id] ?: throw AssertionError("$name: falta $kind/$id")
                    for (f in fields.keys()) {
                        assertEquals("$name: $kind/$id.$f", fields.get(f).toString(), entry.opt(f)?.toString() ?: defaultOf(f))
                    }
                }
            }
            exp.optJSONObject("run")?.let { run ->
                for (f in run.keys()) assertEquals("$name: $f", run.get(f).toString(), obj.get(f).toString())
            }
        }
    }

    // "deleted" ausente equivale a false, igual que en Store.
    private fun defaultOf(field: String): String = if (field == "deleted") "false" else "null"

    @Test
    fun stats() {
        val cases = fixtures.getJSONArray("stats")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val to = if (c.isNull("to")) Long.MAX_VALUE else c.getLong("to")
            val ms = Store.msByActivity(dataset(c.getJSONObject("data")), c.getLong("from"), to, c.getLong("now"))
            val exp = c.getJSONObject("expect")
            val got = ms.mapValues { it.value / 1000 }.filterValues { it > 0 }
            assertEquals(c.getString("name"), exp.keys().asSequence().associateWith { exp.getLong(it) }, got)
        }
    }
}
