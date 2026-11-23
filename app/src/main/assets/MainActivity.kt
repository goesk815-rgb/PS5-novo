package com.ps5.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val ACTION = "com.ps5.app.INSTALL_RESULT"
    private var pendingPkg = ""
    // Seletor de arquivos: o usuário escolhe o(s) APK(s) no próprio celular
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) js("ps5.status('$pendingPkg','failed','Nenhum arquivo selecionado')")
        else doInstall(pendingPkg, uris.map { u -> { contentResolver.openInputStream(u)!! } })
    }

    private fun doInstall(pkg: String, opens: List<() -> java.io.InputStream>) {
        Thread {
            try {
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    opens.forEachIndexed { n, open -> open().use { ins -> s.openWrite("part$n.apk", 0, -1).use { o -> ins.copyTo(o); s.fsync(o) } } }
                    val intent = Intent(ACTION).setPackage(packageName).putExtra("pkg", pkg)
                    val pend = PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(pend.intentSender)
                }
                js("ps5.status('$pkg','installing')")
            } catch (e: Exception) { js("ps5.status('$pkg','failed','${e.message?.replace("'", " ")}')") }
        }.start()
    }

    // Resultado da instalação (PackageInstaller) -> JS
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.getStringExtra("pkg") ?: return
            when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // tela de confirmação do sistema (obrigatória)
                    val confirm = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> js("ps5.status('$pkg','success')")
                else -> js("ps5.status('$pkg','failed','Falha na instalação (código $st)')")
            }
        }
    }
    // Pacote instalado/removido por qualquer meio (ex.: Play Store) -> atualiza Biblioteca
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { js("ps5.refresh()") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "PS5Native")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, IntentFilter(ACTION), RECEIVER_NOT_EXPORTED)
            registerReceiver(pkgReceiver, f, RECEIVER_EXPORTED)
        } else {
            registerReceiver(installReceiver, IntentFilter(ACTION))
            registerReceiver(pkgReceiver, f)
        }
    }

    override fun onResume() { super.onResume(); js("ps5.refresh()") }
    override fun onDestroy() { unregisterReceiver(installReceiver); unregisterReceiver(pkgReceiver); super.onDestroy() }
    private fun js(code: String) = runOnUiThread { web.evaluateJavascript("window.$code", null) }

    inner class Bridge {
        @JavascriptInterface
        fun installedPackages(json: String): String {
            val out = JSONArray(); val arr = JSONArray(json)
            for (k in 0 until arr.length()) {
                val p = arr.getString(k)
                try { packageManager.getPackageInfo(p, 0); out.put(p) } catch (_: Exception) {}
            }
            return out.toString()
        }

        @JavascriptInterface
        fun launch(pkg: String) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)   // abre o jogo em tela cheia (fora do app)
            }
        }

        @JavascriptInterface
        fun install(pkg: String, source: String, asset: String) {
            if (source == "play") {   // caminho oficial
                val u = Uri.parse("market://details?id=$pkg")
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                js("ps5.status('$pkg','installing')"); return
            }
            if (!packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                js("ps5.status('$pkg','failed','Permita instalar apps deste aplicativo e toque em Instalar de novo')"); return
            }
            val names = asset.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val embedded = source == "apk" && names.isNotEmpty() &&
                names.all { runCatching { assets.open(it).close() }.isSuccess }
            if (embedded) {           // APK(s) dentro do app: abre direto a tela "Deseja instalar?"
                doInstall(pkg, names.map { name -> { assets.open(name) } })
            } else {                  // sem APK embutido: escolhe o arquivo no celular
                pendingPkg = pkg
                runOnUiThread { picker.launch(arrayOf("*/*")) }
            }
        }
    }
}
