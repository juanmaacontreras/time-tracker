package com.bitacora.timer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-actualización desde GitHub Releases. Al abrir la app se consulta el último
 * release; si su versión (tag v<N>) es mayor a la instalada, se ofrece actualizar.
 * Android nunca instala en silencio un APK de fuera de la tienda: siempre confirma el
 * usuario. Todos los APK comparten la misma firma (ver app.keystore) para poder
 * instalarse uno sobre otro.
 */
object Updater {
    // Repo público: la API de releases y la descarga del asset no requieren token.
    private const val LATEST_URL =
        "https://api.github.com/repos/juanmaacontreras/time-tracker/releases/latest"

    fun currentVersionCode(ctx: Context): Long {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    /** Consulta en segundo plano. Llama a onAvailable(versionRemota, urlApk) en el hilo dado por post. */
    fun check(ctx: Context, post: (Runnable) -> Unit, onAvailable: (Long, String) -> Unit) {
        Thread {
            try {
                val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "bitacora-app")  // GitHub exige User-Agent
                conn.connectTimeout = 12000; conn.readTimeout = 12000
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@Thread }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val obj = org.json.JSONObject(body)
                val tag = obj.optString("tag_name", "")           // ej. "v28"
                val remote = tag.trimStart('v', 'V').toLongOrNull() ?: return@Thread
                var apkUrl: String? = null
                val assets: JSONArray = obj.optJSONArray("assets") ?: JSONArray()
                for (i in 0 until assets.length()) {
                    val name = assets.getJSONObject(i).optString("name", "")
                    if (name.endsWith(".apk")) { apkUrl = assets.getJSONObject(i).optString("browser_download_url"); break }
                }
                val url = apkUrl ?: return@Thread
                if (remote > currentVersionCode(ctx)) post(Runnable { onAvailable(remote, url) })
            } catch (e: Exception) { /* sin conexión / sin releases: ignorar */ }
        }.start()
    }

    /** Descarga el APK y lanza el instalador del sistema (el usuario confirma). */
    fun downloadAndInstall(activity: Activity, apkUrl: String) {
        // En Android 8+ hace falta permiso de "instalar apps desconocidas" para esta app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Permití \"instalar apps desconocidas\" y volvé a tocar Actualizar.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {}
            return
        }
        Toast.makeText(activity, "Descargando actualización…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val file = File(activity.cacheDir, "update.apk")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
                conn.disconnect()
                activity.runOnUiThread { launchInstaller(activity, file) }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "No se pudo descargar la actualización.", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun launchInstaller(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(activity, "No se pudo abrir el instalador.", Toast.LENGTH_SHORT).show()
        }
    }
}
