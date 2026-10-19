package com.bitacora.timer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    internal lateinit var list: LinearLayout
    private lateinit var timerView: LinearLayout
    internal lateinit var resumenView: LinearLayout
    internal lateinit var hero: LinearLayout
    internal lateinit var heroTop: View
    internal lateinit var heroSwatch: FrameLayout
    internal lateinit var heroDot: View
    internal lateinit var heroCat: TextView
    internal lateinit var heroHint: TextView
    internal lateinit var heroIdle: TextView
    internal lateinit var heroActions: LinearLayout
    internal lateinit var bannerLabel: TextView
    internal lateinit var bannerName: TextView
    internal lateinit var bannerChrono: Chronometer
    private lateinit var tabs: LinearLayout
    private lateinit var tabTimer: TextView
    private lateinit var tabResumen: TextView
    private lateinit var profileChip: View
    private lateinit var profAvatar: TextView
    private lateinit var profName: TextView
    private lateinit var todaySub: TextView
    internal var pickerDialog: AlertDialog? = null
    private var updateChecked = false

    internal var tab = "timer"
    internal var statsPeriod = "day"
    internal var statsCategory = "all"
    internal var statsOffset = 0  // 0 = período actual, -1 = el anterior, etc.
    internal var showArchived = false

    internal val todayViews = HashMap<String, TextView>()
    internal val expandedIds = HashSet<String>()
    internal val handler = Handler(Looper.getMainLooper())

    // Firma del estado "corriendo" tal como quedó dibujado en pantalla. Sirve para
    // detectar cambios hechos por fuera de la UI (botones de la notificación o del
    // widget, sync en background) y redibujar enseguida.
    private var lastStateSig = ""
    private var resumenMinute = 0L
    // Incluye Store.revision para captar TODO cambio del dataset, no solo los del
    // cronómetro: una actividad renombrada en otro dispositivo llega por push y se
    // fusiona en un hilo de fondo, y sin eso la pantalla abierta no se enteraba hasta
    // el siguiente ciclo de sync.
    private fun stateSig(): String =
        Store.runningActId(this) + "|" + Store.runningPaused(this) + "|" +
            Store.runningChangedAt(this) + "|" + Store.revision

    private val ticker = object : Runnable {
        override fun run() {
            // Comparación barata (lee 3 campos del JSON ya cacheado en memoria, sin
            // tocar disco ni red). Antes la app solo se enteraba de estos cambios en el
            // syncLoop, que corre cada 10s: por eso pausar desde la notificación tardaba
            // varios segundos en reflejarse con la app abierta.
            if (stateSig() != lastStateSig) render() else {
                updateRunningRow()
                // El Resumen muestra minutos: con algo corriendo, se rehace una vez por minuto.
                val minute = Store.now() / 60000
                if (tab == "resumen" && minute != resumenMinute && Store.runningActId(this@MainActivity).isNotEmpty() &&
                    !Store.runningPaused(this@MainActivity)) { resumenMinute = minute; renderResumen() }
            }
            handler.postDelayed(this, 1000)
        }
    }
    // Antes cada 10s, cuando el sync periódico era la ÚNICA forma de enterarse de un
    // cambio hecho en otro dispositivo. Con el push andando eso llega en segundos por
    // la otra vía, así que este ciclo quedó solo como respaldo para cuando el push no
    // llega (sin señal, o si algo del lado del servidor se rompe).
    private val SYNC_LOOP_MS = 60000L

    private val syncLoop = object : Runnable {
        override fun run() {
            doSync()
            handler.postDelayed(this, SYNC_LOOP_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        timerView = findViewById(R.id.timerView)
        resumenView = findViewById(R.id.resumenView)
        hero = findViewById(R.id.hero)
        heroTop = findViewById(R.id.heroTop)
        heroSwatch = findViewById(R.id.heroSwatch)
        heroDot = findViewById(R.id.heroDot)
        heroCat = findViewById(R.id.heroCat)
        heroHint = findViewById(R.id.heroHint)
        heroIdle = findViewById(R.id.heroIdle)
        heroActions = findViewById(R.id.heroActions)
        bannerLabel = findViewById(R.id.bannerLabel)
        bannerName = findViewById(R.id.bannerName)
        bannerChrono = findViewById(R.id.bannerChrono)
        tabs = findViewById(R.id.tabs)
        tabTimer = findViewById(R.id.tabTimer)
        tabResumen = findViewById(R.id.tabResumen)
        profileChip = findViewById(R.id.profileChip)
        profAvatar = findViewById(R.id.profAvatar)
        profName = findViewById(R.id.profName)
        todaySub = findViewById(R.id.todaySub)

        tabTimer.setOnClickListener { switchTab("timer") }
        tabResumen.setOnClickListener { switchTab("resumen") }
        profileChip.setOnClickListener { openProfileSheet() }

        requestNotifPermission()
        scheduleBackgroundSync()
        switchTab("timer")

        if (!Store.hasChosenProfile(this)) chooseProfileFirst()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
    }

    // Con el push andando, este worker dejó de ser la vía principal para enterarse de
    // los cambios y quedó solo como red de seguridad (el push es best-effort: sin señal
    // se pierde). Por eso pasó de 15 minutos a 1 hora: de ~96 despertadas diarias a ~24,
    // casi todas para descubrir que no cambió nada.
    //
    // UPDATE en vez de KEEP a propósito: con KEEP, WorkManager conserva el trabajo ya
    // encolado con el intervalo viejo y el cambio no tendría ningún efecto en los
    // dispositivos donde la app ya venía instalada.
    private fun scheduleBackgroundSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("bitacora-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    override fun onResume() {
        super.onResume()
        if (!Store.hasChosenProfile(this)) return  // esperando elegir perfil
        beginSession()
    }

    // Arranca (o reanuda) la sesión con el perfil activo: refresca todo y los loops.
    internal fun beginSession() {
        if (leaveDeletedProfile()) return  // selectProfile vuelve a entrar acá
        render()
        handler.removeCallbacks(ticker); handler.post(ticker)
        doSync()
        handler.removeCallbacks(syncLoop); handler.postDelayed(syncLoop, SYNC_LOOP_MS)
        // Con la app en pantalla los cambios de otros dispositivos llegan por Realtime en
        // menos de 1 s, sin esperar el push. Cambiar de perfil vuelve a pasar por acá y
        // Live se resuscribe a los buckets del perfil nuevo.
        Live.start(listOf(Config.INDEX_KEY, Config.bucketKey(Store.currentProfileId(this)))) { doSync() }
        registerPushToken()
        if (!updateChecked) { updateChecked = true; checkForUpdates() }
    }

    // Registra el token de push junto con el perfil activo. Se llama desde beginSession,
    // que corre tanto al abrir la app como al cambiar de perfil (selectProfile termina
    // acá): así el registro siempre refleja en qué perfil está parado este dispositivo,
    // que es lo que decide a quién despertar. Devices.registerLocal devuelve false si
    // nada cambió, así que el arranque normal no genera tráfico.
    private fun registerPushToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                // Se sincroniza SIEMPRE, no solo cuando el token cambió localmente: es
                // la única forma de enterarse de que el servidor dio de baja esta
                // entrada por token muerto. Es un pedido por apertura de la app, no por
                // ciclo de sync, así que el costo es despreciable.
                Thread { Sync.syncDevices(applicationContext, token) }.start()
            }
        } catch (e: Exception) {
            // Dispositivo sin Google Play Services: la app sigue funcionando igual,
            // solo que se entera de los cambios por el SyncWorker y no al instante.
        }
    }

    // Al abrir la app: si hay una versión nueva en GitHub, ofrece actualizar.
    private fun checkForUpdates() {
        Updater.check(this, { r -> runOnUiThread(r) }) { version, apkUrl ->
            showStyled(
                AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("Actualización disponible")
                    .setMessage("Hay una versión nueva de Bitácora. ¿Actualizar ahora?")
                    .setPositiveButton("Actualizar") { _, _ -> Updater.downloadAndInstall(this, apkUrl) }
                    .setNegativeButton("Ahora no", null)
            )
        }
    }

    // Encabezado: avatar (inicial sobre el color del tema del perfil) + nombre, y la
    // fecha de hoy con lo acumulado en el día.
    internal fun updateHeader() {
        val pid = Store.currentProfileId(this)
        val name = ProfileStore.nameOf(this, pid)
        profName.text = name
        profAvatar.text = name.trim().take(1).uppercase()
        profAvatar.background = oval(Themes.accentOf(ProfileStore.themeOf(this, pid), night = false))
        profileChip.background = withRipple(rounded(col(R.color.card), 20), dp(20).toFloat())
        updateTodaySub()
    }

    internal fun updateTodaySub() {
        val fecha = java.text.SimpleDateFormat("EEEE d 'de' MMMM", java.util.Locale("es"))
            .format(java.util.Date()).replaceFirstChar { it.uppercase() }
        val total = Store.totalBetween(this, Store.startOfToday(), Long.MAX_VALUE)
        todaySub.text = if (total > 0) "$fecha · ${Store.human(total)}" else fecha
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(syncLoop)
        Live.stop()  // en segundo plano el aviso vuelve a llegar solo por push
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    // Enruta por el tema del perfil activo (acento/corriendo); el resto cae al recurso base.
    internal fun col(id: Int) = Themes.color(this, id)

    internal fun switchTab(t: String) {
        tab = t
        timerView.visibility = if (t == "timer") View.VISIBLE else View.GONE
        resumenView.visibility = if (t == "resumen") View.VISIBLE else View.GONE
        styleTabs()
        render()
    }

    private fun styleTabs() {
        tabs.background = rounded(col(R.color.surface2), 14)
        paintSegment(tabTimer, tab == "timer")
        paintSegment(tabResumen, tab == "resumen")
    }

    // Opción de un control segmentado (pestañas, período): la activa queda "levantada".
    internal fun paintSegment(tv: TextView, active: Boolean) {
        tv.background = if (active) rounded(col(R.color.raised), 10) else null
        tv.elevation = if (active && !Themes.isNight(this)) dp(1).toFloat() else 0f
        tv.setTextColor(if (active) col(R.color.ink) else col(R.color.inkSoft))
    }

    internal fun render() {
        updateHeader()
        if (tab == "timer") renderTimer() else renderResumen()
        Surfaces.refresh(this)
        // Queda registrado qué estado corresponde a lo que hay dibujado ahora, para que
        // el ticker solo vuelva a redibujar si algo lo cambia por fuera (ver stateSig).
        lastStateSig = stateSig()
    }

    // Envuelve un fondo con un ripple recortado a la misma forma, para dar feedback
    // táctil inmediato (tap corto o mantener presionado) sin animaciones vistosas.
    internal fun withRipple(content: Drawable, cornerRadius: Float): Drawable {
        val mask = GradientDrawable()
        mask.cornerRadius = cornerRadius
        mask.setColor(Color.WHITE)
        val rippleColor = ColorStateList.valueOf(col(R.color.indigo))
        return RippleDrawable(rippleColor, content, mask)
    }

    // Solo redibuja si el sync trajo cambios reales: render() reconstruye la lista, la
    // notificación y los dos widgets, y refrescar el widget reaplica setChronometer sobre
    // un cronómetro en vivo (el "flash del número gigante").
    internal fun doSync() {
        if (!Config.syncEnabled()) return
        Thread {
            if (Sync.syncNow(applicationContext)) runOnUiThread { onRemoteChange() }
        }.start()
    }

    // Llegó algo nuevo de otro dispositivo. Si fue el borrado del perfil en el que está
    // parado este, se pasa al Principal: seguir usándolo dejaría escribiendo en un bucket
    // que ya no figura en ningún lado.
    private fun onRemoteChange() {
        if (isFinishing || isDestroyed) return
        if (!leaveDeletedProfile()) render()
    }

    // También corre al abrir la app: el borrado pudo haber llegado con la app cerrada
    // (SyncWorker), y entonces el sync de la pantalla ya no lo ve como un cambio.
    private fun leaveDeletedProfile(): Boolean {
        val cur = Store.currentProfileId(this)
        if (cur == Config.DEFAULT_PROFILE || !ProfileStore.isDeleted(this, cur)) return false
        pickerDialog?.dismiss()
        selectProfile(Config.DEFAULT_PROFILE)
        return true
    }

}
