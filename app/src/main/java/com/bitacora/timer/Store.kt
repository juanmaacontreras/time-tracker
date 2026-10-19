package com.bitacora.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID

object Store {
    private const val PREFS = "bitacora"
    private const val APP_PREFS = "bitacora_app"
    private const val CURRENT_KEY = "currentProfile"

    // Colores para actividades y categorías: Nord (Frost / Aurora) en tonos lo bastante
    // profundos para que un ícono blanco se lea encima. 14 tonos distinguibles entre sí.
    // Mismo set y orden que COLORS en web/index.html. Las actividades existentes
    // conservan el color que ya tenían guardado.
    val COLORS = arrayOf(
        "#5E81AC", "#BF616A", "#6E9F5A", "#D08770", "#8A6585", "#4C8A8A", "#B48EAD",
        "#C29B4A", "#4C566A", "#3E7A99", "#A35D6A", "#5F7F4A", "#B4694F", "#6F8FB4"
    )

    fun now(): Long = System.currentTimeMillis()
    fun uid(): String = UUID.randomUUID().toString()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun appPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    // ---------- perfil activo (local, por dispositivo) ----------
    fun hasChosenProfile(ctx: Context): Boolean = appPrefs(ctx).getString(CURRENT_KEY, null) != null
    fun currentProfileId(ctx: Context): String =
        appPrefs(ctx).getString(CURRENT_KEY, null) ?: Config.DEFAULT_PROFILE

    @Synchronized
    fun setCurrentProfile(ctx: Context, id: String) {
        appPrefs(ctx).edit().putString(CURRENT_KEY, id).apply()
        cachedRoot = null; cachedProfile = null  // invalida cache: cambia el dataset
    }

    // Clave de SharedPreferences donde vive el dataset de un perfil.
    // El perfil "default" usa la clave histórica "data" (adopta lo que ya había).
    private fun dataKey(profileId: String): String =
        if (profileId == Config.DEFAULT_PROFILE || profileId.isEmpty()) "data" else "data_$profileId"

    @Synchronized
    fun clearProfileData(ctx: Context, profileId: String) {
        prefs(ctx).edit().remove(dataKey(profileId)).apply()
        if (cachedProfile == profileId) { cachedRoot = null; cachedProfile = null }
    }

    // Cache en memoria del JSON completo del perfil activo. Sin esto, cada llamada a root()
    // volvía a leer SharedPreferences y a parsear TODO el historial desde cero.
    @Volatile private var cachedRoot: JSONObject? = null
    @Volatile private var cachedProfile: String? = null

    // Sube en cada escritura del dataset. La UI lo mira para redibujar ante CUALQUIER
    // cambio, no solo los del cronómetro: un push que trae una actividad renombrada
    // desde otro dispositivo se fusiona en un hilo de fondo, y sin esto la pantalla
    // abierta no se enteraba hasta el siguiente ciclo de sync (hasta 60 s).
    // Es un contador en memoria, mucho más barato que serializar todo para comparar.
    @Volatile var revision: Long = 0L
        private set

    @Synchronized
    fun root(ctx: Context): JSONObject {
        val pid = currentProfileId(ctx)
        cachedRoot?.let { if (cachedProfile == pid) return it }
        val raw = prefs(ctx).getString(dataKey(pid), null)
        val obj = if (raw != null) JSONObject(raw) else JSONObject()
        if (!obj.has("activities")) obj.put("activities", JSONArray())
        if (!obj.has("sessions")) obj.put("sessions", JSONArray())
        if (!obj.has("runActId")) obj.put("runActId", "")
        if (!obj.has("runStart")) obj.put("runStart", 0L)
        if (!obj.has("runChangedAt")) obj.put("runChangedAt", 0L)
        if (!obj.has("runPaused")) obj.put("runPaused", false)
        if (!obj.has("runPausedAt")) obj.put("runPausedAt", 0L)
        if (!obj.has("runPausedAccum")) obj.put("runPausedAccum", 0L)
        // Limpiezas idempotentes: solo escriben si de verdad cambiaron algo, así dos
        // cargas seguidas nunca generan escrituras (ni sync) de la nada.
        var changed = false
        if (!obj.has("categories")) { migrateCategoriesFromTypes(obj); changed = true }
        changed = dedupeCategoriesByName(obj) || changed
        cachedRoot = obj; cachedProfile = pid
        if (changed) write(ctx, obj)
        return obj
    }

    // Categoría "General" implícita para actividades cuyo id de categoría no resuelve
    // (dato viejo/corrupto, o categoría borrada) — nunca debería pasar en uso normal,
    // pero evita nulls en cascada por toda la UI.
    private fun fallbackCategory(): JSONObject =
        JSONObject().put("id", "").put("name", "General").put("icon", "").put("color", "#2F4B8F")

    // Detecta un string con forma de id (UUID random o el hash estable de abajo). Sirve
    // para blindar la migración: si "type" YA es un id (por ejemplo porque la migración
    // corrió antes en otro dispositivo/versión y esto es un re-run sobre datos que ya
    // estaban migrados), nunca hay que adoptar ese id como si fuera el nombre legible
    // de una categoría.
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private fun looksLikeId(s: String): Boolean = UUID_RE.matches(s) || s.startsWith("cat-")

    // Id ESTABLE derivado del nombre (FNV-1a 32 bits, mismo algoritmo bit a bit en
    // web/index.html). Antes la migración usaba uid() (random): si corría de forma
    // independiente en el celu, en la web, o en una reinstalación, cada una generaba un
    // id DISTINTO para la misma categoría ("Materia" x3, "Libro" x3...) — el merge por id
    // nunca las veía como la misma categoría. Con un id determinístico, dos migraciones
    // para el mismo nombre siempre coinciden, así que ya no pueden crear duplicados.
    internal fun stableCategoryId(name: String): String {
        val bytes = name.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
        var hash = 0x811c9dc5L
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return "cat-" + hash.toString(16).padStart(8, '0')
    }

    // Antes "type" en cada actividad era un string libre. Esta migración corre una sola
    // vez (cuando el perfil todavía no tiene "categories") y convierte cada valor distinto
    // de "type" en una categoría real, reescribiendo el campo de la actividad de nombre a
    // id. El color de cada categoría nueva es el de su primera actividad (mismo criterio
    // que ya usaba categoryColors() en MainActivity), y quedan con updatedAt=0L para que
    // cualquier edición real posterior (acá o en otro dispositivo) siempre les gane en el merge.
    private fun migrateCategoriesFromTypes(obj: JSONObject) {
        val activities = obj.getJSONArray("activities")
        val byName = LinkedHashMap<String, JSONObject>()
        for (i in 0 until activities.length()) {
            val a = activities.getJSONObject(i)
            val raw = a.optString("type", "").trim()
            val name = if (raw.isEmpty() || looksLikeId(raw)) "General" else raw
            val cat = byName.getOrPut(name) {
                JSONObject()
                    .put("id", stableCategoryId(name))
                    .put("name", name)
                    .put("icon", "")
                    .put("color", a.optString("color", COLORS[byName.size % COLORS.size]))
                    .put("updatedAt", 0L)
                    .put("deleted", false)
            }
            a.put("type", cat.getString("id"))
        }
        val arr = JSONArray()
        for (cat in byName.values) arr.put(cat)
        obj.put("categories", arr)
    }

    // Limpieza de las categorías duplicadas que ya se crearon ANTES de este fix (ids
    // random distintos para el mismo nombre). Agrupa por nombre normalizado, conserva
    // una sola por grupo — con el id que ya tenía, sin reescribirlo —, reapunta las
    // actividades de las otras hacia ella y marca esas otras como borradas. Corre al
    // cargar y después de cada merge: una vez consolidado no encuentra nada y devuelve
    // false. Devuelve true si cambió algo.
    internal fun dedupeCategoriesByName(obj: JSONObject): Boolean {
        val cats = obj.getJSONArray("categories")
        // OJO: agrupamos TAMBIÉN las ya borradas. Una actividad puede seguir apuntando
        // al id de una categoría que quedó marcada "deleted" en una limpieza anterior
        // (o en una versión previa de esta migración) — si la excluimos del grupo, esa
        // actividad nunca entra al remapeo y se queda huérfana (cae a "General").
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            val key = c.optString("name", "General").trim().lowercase(Locale.ROOT).ifEmpty { "general" }
            groups.getOrPut(key) { mutableListOf() }.add(c)
        }
        val remap = HashMap<String, String>()
        var changed = false
        for ((_, group) in groups) {
            if (group.size < 2) continue
            val alive = group.filter { !it.optBoolean("deleted", false) }
            // Preferimos una viva con ícono ya elegido a mano. Si TODAS están borradas,
            // se consolida igual (para que el remap de actividades funcione) pero queda
            // borrada: nunca se resucita una categoría que el usuario borró a propósito.
            //
            // Dentro de cada opción, gana el id menor: la elección tiene que ser la MISMA
            // en todos los dispositivos. Antes se tomaba "la primera de la lista local",
            // que en cada dispositivo podía ser otra: si dos creaban "Libro" a la vez,
            // cada uno borraba la del otro, al sincronizar ganaban los dos borrados y la
            // categoría desaparecía.
            val pool = alive.filter { it.optString("icon", "").isNotEmpty() }
                .ifEmpty { alive }.ifEmpty { group }
            val keep = pool.minByOrNull { it.getString("id") } ?: continue
            // El id de la que se conserva NO se reescribe nunca. Antes se le asignaba un
            // "id canónico" derivado del nombre, y como la tabla de remapeo es común a
            // todos los grupos, el id viejo de un grupo podía coincidir con el id canónico
            // de otro: el remapeo se pisaba y las actividades terminaban en la categoría
            // equivocada. Usando el id que la conservada ya tenía, un id que se conserva
            // nunca es origen de un remapeo y esa colisión no puede existir.
            val keepId = keep.getString("id")
            for (c in group) {
                if (c === keep) continue
                remap[c.getString("id")] = keepId
                // Solo se toca lo que realmente cambia: reescribir updatedAt de entradas
                // ya borradas en cada carga generaba escrituras y sync a lo pavote.
                if (!c.optBoolean("deleted", false)) {
                    c.put("deleted", true).put("updatedAt", now())
                    changed = true
                }
            }
        }
        if (remap.isNotEmpty()) {
            val activities = obj.getJSONArray("activities")
            for (i in 0 until activities.length()) {
                val a = activities.getJSONObject(i)
                val newId = remap[a.optString("type", "")]
                if (newId != null) { a.put("type", newId).put("updatedAt", now()); changed = true }
            }
        }
        return changed
    }

    // ---------- categories ----------
    @Synchronized
    fun categories(ctx: Context): List<JSONObject> {
        val arr = root(ctx).getJSONArray("categories")
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (!c.optBoolean("deleted", false)) out.add(c)
        }
        return out
    }

    @Synchronized
    fun categoryById(ctx: Context, id: String): JSONObject? {
        if (id.isEmpty()) return null
        val arr = root(ctx).getJSONArray("categories")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.getString("id") == id && !c.optBoolean("deleted", false)) return c
        }
        return null
    }

    // Resuelve la categoría de una actividad (el campo "type" ahora guarda un id, no un
    // nombre libre). Nunca devuelve null: cae a "General" si el id no resuelve.
    @Synchronized
    fun categoryForActivity(ctx: Context, act: JSONObject): JSONObject =
        categoryById(ctx, act.optString("type", "")) ?: fallbackCategory()

    @Synchronized
    fun addCategory(ctx: Context, name: String, icon: String, color: String): String {
        val obj = root(ctx)
        val id = uid()
        obj.getJSONArray("categories").put(
            JSONObject().put("id", id).put("name", name.trim())
                .put("icon", icon).put("color", color)
                .put("updatedAt", now()).put("deleted", false)
        )
        write(ctx, obj)
        return id
    }

    @Synchronized
    fun updateCategory(ctx: Context, id: String, name: String, icon: String, color: String) {
        val obj = root(ctx)
        val arr = obj.getJSONArray("categories")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.getString("id") == id) {
                c.put("name", name.trim()).put("icon", icon).put("color", color).put("updatedAt", now())
            }
        }
        write(ctx, obj)
    }

    // Borra la categoría (tombstone, igual que activities/sessions) y deja "sin
    // categoría" a las actividades que la usaban en vez de arrastrar un id fantasma.
    @Synchronized
    fun deleteCategory(ctx: Context, id: String) {
        val obj = root(ctx)
        val arr = obj.getJSONArray("categories")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.getString("id") == id) c.put("deleted", true).put("updatedAt", now())
        }
        val acts = obj.getJSONArray("activities")
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            if (a.optString("type", "") == id) a.put("type", "").put("updatedAt", now())
        }
        write(ctx, obj)
    }

    private fun newActivity(name: String, categoryId: String, color: String): JSONObject =
        JSONObject()
            .put("id", uid())
            .put("name", name)
            .put("type", categoryId)
            .put("color", color)
            .put("archived", false)
            .put("updatedAt", now())
            .put("deleted", false)

    @Synchronized
    fun write(ctx: Context, obj: JSONObject) {
        val pid = currentProfileId(ctx)
        cachedRoot = obj; cachedProfile = pid
        prefs(ctx).edit().putString(dataKey(pid), obj.toString()).apply()
        revision++
    }

    // ---------- activities ----------
    // Por defecto excluye borradas y archivadas. La pestaña Resumen pasa
    // includeArchived=true para que el historial archivado siga contando.
    @Synchronized
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

    @Synchronized
    fun addActivity(ctx: Context, name: String, categoryId: String, color: String) {
        val obj = root(ctx)
        obj.getJSONArray("activities").put(newActivity(name, categoryId, color))
        write(ctx, obj)
    }

    @Synchronized
    fun updateActivity(ctx: Context, id: String, name: String, categoryId: String, color: String) {
        val obj = root(ctx)
        val arr = obj.getJSONArray("activities")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("id") == id) {
                a.put("name", name).put("type", categoryId).put("color", color).put("updatedAt", now())
            }
        }
        write(ctx, obj)
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
    fun activityById(ctx: Context, id: String): JSONObject? {
        val arr = root(ctx).getJSONArray("activities")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("id") == id) return a
        }
        return null
    }

    // ---------- running ----------
    @Synchronized fun runningActId(ctx: Context): String = root(ctx).optString("runActId", "")
    @Synchronized fun runningStart(ctx: Context): Long = root(ctx).optLong("runStart", 0L)
    @Synchronized fun runningPaused(ctx: Context): Boolean = root(ctx).optBoolean("runPaused", false)
    @Synchronized fun runningPausedAt(ctx: Context): Long = root(ctx).optLong("runPausedAt", 0L)
    @Synchronized fun runningPausedAccum(ctx: Context): Long = root(ctx).optLong("runPausedAccum", 0L)
    // Sello de la última mutación del estado "corriendo". La UI lo usa para detectar
    // cambios hechos por fuera de ella (botones de la notificación/widget, sync en
    // background) y redibujar sin esperar al ciclo de sync.
    @Synchronized fun runningChangedAt(ctx: Context): Long = root(ctx).optLong("runChangedAt", 0L)

    // Milisegundos reales corridos de la sesión activa (descontando pausas). 0 si no hay nada.
    @Synchronized
    fun runningElapsedMs(ctx: Context): Long = elapsedMs(root(ctx), now())

    internal fun elapsedMs(obj: JSONObject, now: Long): Long {
        if (obj.optString("runActId", "").isEmpty()) return 0L
        val end = if (obj.optBoolean("runPaused", false)) obj.optLong("runPausedAt", 0L) else now
        return (end - obj.optLong("runStart", 0L) - obj.optLong("runPausedAccum", 0L)).coerceAtLeast(0L)
    }

    // Tocar la actividad que ya está corriendo (o pausada) la para del todo.
    // Pausar/resumir es una acción explícita aparte (ver Store.pause/resume), nunca
    // pasa por acá — así el toque tiene un solo significado en toda la app y los widgets.
    // Tocar otra actividad distinta corta la anterior (con su tiempo real) y arranca la nueva.
    @Synchronized
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

    @Synchronized
    fun pause(ctx: Context) {
        val obj = root(ctx)
        if (obj.optString("runActId", "").isNotEmpty() && !obj.optBoolean("runPaused", false)) {
            pauseInternal(obj)
            obj.put("runChangedAt", now())
            write(ctx, obj)
        }
    }

    @Synchronized
    fun resume(ctx: Context) {
        val obj = root(ctx)
        if (obj.optString("runActId", "").isNotEmpty() && obj.optBoolean("runPaused", false)) {
            resumeInternal(obj)
            obj.put("runChangedAt", now())
            write(ctx, obj)
        }
    }

    @Synchronized
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
    @Synchronized
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
    @Synchronized
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

    // ---------- stats ----------
    // Tramo [inicio, fin) de la sesión en curso tal como va a quedar guardada al pararla:
    // arranca en runStart y dura el tiempo real corrido (las pausas no cuentan, ver
    // stopInternal). Contarla con el mismo criterio que las sesiones guardadas hace que
    // un período sume lo mismo antes y después de parar el cronómetro — antes se restaban
    // TODAS las pausas aunque la ventana cortara la sesión, y un día podía sumar de menos.
    private fun runningSpan(obj: JSONObject, now: Long): LongArray? {
        val start = obj.optLong("runStart", 0L)
        if (obj.optString("runActId", "").isEmpty() || start <= 0L) return null
        return longArrayOf(start, start + elapsedMs(obj, now))
    }

    // Milisegundos por actividad dentro de [from, to), en UNA sola pasada por las sesiones.
    // Antes cada actividad recorría el historial completo por su cuenta (y los widgets lo
    // hacían dentro del comparador del sort): con meses de historial era lo más caro de
    // cada redibujado. Pura (no toca disco): la prueban los tests compartidos con la web.
    internal fun msByActivity(obj: JSONObject, from: Long, to: Long, now: Long): HashMap<String, Long> {
        val out = HashMap<String, Long>()
        fun add(actId: String, start: Long, end: Long) {
            val ms = minOf(end, to) - maxOf(start, from)
            if (ms > 0) out[actId] = (out[actId] ?: 0L) + ms
        }
        val ss = obj.getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            add(s.getString("actId"), s.getLong("start"), s.getLong("end"))
        }
        runningSpan(obj, now)?.let { add(obj.getString("runActId"), it[0], it[1]) }
        return out
    }

    private fun msByActivity(ctx: Context, from: Long, to: Long) = msByActivity(root(ctx), from, to, now())

    // Sesiones (guardadas + la en curso) recortadas a [from, to), ordenadas por inicio:
    // (actId, inicio, fin). Base de la línea de tiempo del Día. Pura (tests compartidos).
    internal fun sessionsBetween(obj: JSONObject, from: Long, to: Long, now: Long): List<Triple<String, Long, Long>> {
        val out = ArrayList<Triple<String, Long, Long>>()
        fun add(actId: String, start: Long, end: Long) {
            val a = maxOf(start, from)
            val b = minOf(end, to)
            if (b > a) out.add(Triple(actId, a, b))
        }
        val ss = obj.getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            add(s.getString("actId"), s.getLong("start"), s.getLong("end"))
        }
        runningSpan(obj, now)?.let { add(obj.getString("runActId"), it[0], it[1]) }
        return out.sortedWith(compareBy({ it.second }, { it.first }))
    }

    // Fin de la última sesión de cada actividad ("última vez hace N días"). La que corre
    // cuenta como ahora. Pura (tests compartidos).
    internal fun lastEndByActivity(obj: JSONObject, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        val ss = obj.getJSONArray("sessions")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i)
            if (s.optBoolean("deleted", false)) continue
            val id = s.getString("actId")
            if (s.getLong("end") > (out[id] ?: 0L)) out[id] = s.getLong("end")
        }
        if (runningSpan(obj, now) != null) out[obj.getString("runActId")] = now
        return out
    }

    @Synchronized
    fun sessionsBetween(ctx: Context, from: Long, to: Long) = sessionsBetween(root(ctx), from, to, now())

    @Synchronized
    fun lastEndByActivity(ctx: Context) = lastEndByActivity(root(ctx), now())

    /** Segundos por actividad dentro de [from, to). Sin "to": hasta ahora. */
    @Synchronized
    fun secsByActivity(ctx: Context, from: Long, to: Long = Long.MAX_VALUE): Map<String, Long> =
        msByActivity(ctx, from, to).mapValues { it.value / 1000 }

    @Synchronized
    fun secsFor(ctx: Context, actId: String, from: Long, to: Long = Long.MAX_VALUE): Long =
        (msByActivity(ctx, from, to)[actId] ?: 0L) / 1000

    fun startOfToday(): Long = midnight(Calendar.getInstance())

    // Total dentro de la ventana [from, to), en segundos. Si actIds no es null,
    // solo suma las actividades incluidas en ese set.
    @Synchronized
    fun totalBetween(ctx: Context, from: Long, to: Long, actIds: Set<String>? = null): Long =
        msByActivity(ctx, from, to).entries
            .filter { actIds == null || it.key in actIds }
            .sumOf { it.value } / 1000

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
    @Synchronized
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
        val acts = HashMap<String, JSONObject>()
        val arr = root(ctx).getJSONArray("activities")
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { acts[it.getString("id")] = it }
        // Comillas internas duplicadas (regla de CSV): un nombre con " ya no corre columnas.
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (s in list) {
            val a = acts[s.getString("actId")]
            val name = a?.getString("name") ?: "?"
            val type = if (a != null) categoryForActivity(ctx, a).optString("name", "") else ""
            val min = (s.getLong("end") - s.getLong("start")) / 60000.0
            sb.append(q(name)).append(',').append(q(type)).append(',')
                .append(q(fmt.format(s.getLong("start")))).append(',')
                .append(q(fmt.format(s.getLong("end")))).append(',')
                .append(String.format(Locale.US, "%.1f", min)).append('\n')
        }
        return sb.toString()
    }

    // ---------- backup ----------
    /**
     * Volcado crudo de TODO lo que este dispositivo tiene guardado: el dataset completo
     * de cada perfil (actividades, sesiones, categorías y estado del cronómetro) más el
     * índice de perfiles. Solo lee, no modifica ni sincroniza nada.
     *
     * Existe como red de seguridad: el CSV exporta sesiones de un período y no sirve
     * para reconstruir el estado. Esto sí.
     *
     * Ojo: incluye únicamente los perfiles cuyos datos estén guardados en ESTE
     * dispositivo. Un perfil que nunca se abrió acá no tiene nada local que volcar.
     *
     * A propósito SIN @Synchronized: no toca el cache de Store (solo lee
     * SharedPreferences, que ya es thread-safe) y llama a ProfileStore. Tomar el lock de
     * Store para después pedir el de ProfileStore invertiría el orden que usa
     * ProfileStore.deleteProfile() -> Store.clearProfileData(), y eso sería un deadlock
     * esperando a que alguien sincronice clearProfileData.
     */
    fun fullBackup(ctx: Context): JSONObject {
        val porPerfil = JSONObject()
        for (p in ProfileStore.profiles(ctx)) {
            val pid = p.optString("id", "")
            if (pid.isEmpty()) continue
            val raw = prefs(ctx).getString(dataKey(pid), null) ?: continue
            try { porPerfil.put(pid, JSONObject(raw)) } catch (e: Exception) { /* dato ilegible: se omite */ }
        }
        return JSONObject()
            .put("app", "bitacora")
            .put("formato", 1)
            .put("exportadoEl", now())
            .put("perfilActivo", currentProfileId(ctx))
            .put("indiceDePerfiles", ProfileStore.payload(ctx))
            .put("perfiles", porPerfil)
    }

    // ---------- sync payload + merge ----------
    // Copias profundas: el payload se serializa fuera del bloque sincronizado (en el hilo
    // de red), y no debe compartir referencia con los arrays cacheados que otro hilo
    // podría seguir mutando mientras tanto.
    private fun payload(r: JSONObject): JSONObject {
        val run = JSONObject()
            .put("actId", r.optString("runActId", ""))
            .put("start", r.optLong("runStart", 0L))
            .put("paused", r.optBoolean("runPaused", false))
            .put("pausedAt", r.optLong("runPausedAt", 0L))
            .put("pausedAccum", r.optLong("runPausedAccum", 0L))
            .put("changedAt", r.optLong("runChangedAt", 0L))
        return JSONObject()
            .put("activities", JSONArray(r.getJSONArray("activities").toString()))
            .put("sessions", JSONArray(r.getJSONArray("sessions").toString()))
            .put("categories", JSONArray(r.getJSONArray("categories").toString()))
            .put("run", run)
    }

    /**
     * Fusiona el bucket remoto (null = todavía no existe) con el dataset de profileId.
     *
     * Devuelve null si el perfil activo ya no es profileId: el remoto se bajó para OTRO
     * perfil, y fusionarlo acá mezclaría los datasets de dos personas — y la subida
     * posterior llevaría la mezcla al bucket equivocado, contaminando los dos para
     * siempre (la fusión nunca borra). El chequeo vive dentro del lock de Store, el mismo
     * que toma setCurrentProfile, así que el perfil no puede cambiar entre el chequeo y
     * la escritura.
     */
    @Synchronized
    fun merge(ctx: Context, profileId: String, remote: JSONObject?): MergeResult? {
        if (currentProfileId(ctx) != profileId) return null
        val obj = root(ctx)
        val changed = mergeData(obj, remote)
        // Solo se escribe (y sube Store.revision, que hace redibujar la UI) si algo
        // cambió de verdad: antes cada sync escribía igual, y la pantalla abierta, la
        // notificación y los widgets se reconstruían en cada ciclo sin motivo.
        if (changed) write(ctx, obj)
        return MergeResult(changed, if (remoteBehind(obj, remote)) payload(obj) else null)
    }

    /**
     * Fusiona remote dentro de obj (el dataset en memoria). Pura: no toca disco, así la
     * prueban los tests compartidos con la web (tests/fixtures). Devuelve true si obj cambió.
     */
    internal fun mergeData(obj: JSONObject, remote: JSONObject?): Boolean {
        var changed = Merge.list(obj.getJSONArray("activities"), remote?.optJSONArray("activities"))
        changed = Merge.list(obj.getJSONArray("sessions"), remote?.optJSONArray("sessions")) || changed
        // Un merge puede traer categorías con un nombre que ya existía acá (creadas en
        // otro dispositivo): misma limpieza que al cargar.
        if (Merge.list(obj.getJSONArray("categories"), remote?.optJSONArray("categories"))) {
            dedupeCategoriesByName(obj)
            changed = true
        }
        // El estado "corriendo" cruza entre dispositivos: gana el cambio más nuevo.
        // El bloque run se reemplaza entero, nunca campo por campo.
        val rr = remote?.optJSONObject("run")
        val remoteRunAt = rr?.optLong("changedAt", 0L) ?: 0L
        if (rr != null && remoteRunAt > obj.optLong("runChangedAt", 0L)) {
            obj.put("runActId", rr.optString("actId", ""))
            obj.put("runStart", rr.optLong("start", 0L))
            obj.put("runPaused", rr.optBoolean("paused", false))
            obj.put("runPausedAt", rr.optLong("pausedAt", 0L))
            obj.put("runPausedAccum", rr.optLong("pausedAccum", 0L))
            obj.put("runChangedAt", remoteRunAt)
            changed = true
        }
        return changed
    }

    /** True si obj tiene algo que remote no tiene o tiene más viejo: hay que subir. */
    internal fun remoteBehind(obj: JSONObject, remote: JSONObject?): Boolean =
        Merge.behind(remote?.optJSONArray("activities"), obj.getJSONArray("activities")) ||
            Merge.behind(remote?.optJSONArray("sessions"), obj.getJSONArray("sessions")) ||
            Merge.behind(remote?.optJSONArray("categories"), obj.getJSONArray("categories")) ||
            obj.optLong("runChangedAt", 0L) > (remote?.optJSONObject("run")?.optLong("changedAt", 0L) ?: 0L)

    // ---------- formatting helpers ----------
    // Compacto para etiquetas chicas (encabezado, gráficos): "2h 05m", "45m", "30s".
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
