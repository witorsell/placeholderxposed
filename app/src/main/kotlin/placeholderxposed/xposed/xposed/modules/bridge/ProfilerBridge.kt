package placeholderxposed.xposed.modules.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import placeholderxposed.xposed.Utils.Log
import placeholderxposed.xposed.modules.RnFlagsModule
import java.io.File

/**
 * Samples the Hermes JS thread so we can see which functions eat the time while the UI feels slow.
 * Uses Discord's own HermesSamplingProfiler (still present on 352), so it needs no extra native code.
 */
object ProfilerBridge {
    private const val PROFILER = "com.facebook.hermes.instrumentation.HermesSamplingProfiler"

    fun register(context: Context) {
        val loader = context.classLoader

        BridgeModule.registerMethod("profiler.start") {
            startSampling(loader)
            true
        }

        BridgeModule.registerMethod("profiler.stop") {
            stopSampling(loader, File(context.cacheDir, "hermes_profile.json"))
        }
    }

    private var keyTriggerInstalled = false
    private var profiling = false
    private var sampler: ThreadSampler? = null

    private fun startSampling(loader: ClassLoader) {
        call(loader, "enable")
        sampler = ThreadSampler().also { it.start() }
    }

    private fun stopSampling(loader: ClassLoader, out: File): String {
        val javaReport = sampler?.finish() ?: "(no java sampler)"
        sampler = null
        call(loader, "disable")
        call(loader, "dumpSampledTraceToFile", out.absolutePath)
        return "RN flags: ${RnFlagsModule.applied.ifEmpty { listOf("none applied") }}\n" + summarize(out) + "\n== java threads ==\n" + javaReport
    }

    /**
     * Hold Volume Down for about a second to start sampling, hold it again to stop. The summary goes
     * to the clipboard. Works without the JS bridge, so it still runs when bridge calls don't land.
     */
    fun installKeyTrigger(activity: Activity) {
        if (keyTriggerInstalled) return
        keyTriggerInstalled = true

        val loader = activity.classLoader
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())

        fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val event = param.args[0] as? KeyEvent ?: return
                if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 12) return

                if (!profiling) {
                    runCatching { startSampling(loader) }
                        .onSuccess {
                            profiling = true
                            toast("Profiling. Scroll and tap around, then hold Volume Down again.")
                        }
                        .onFailure { toast("Profiler start failed: ${it.cause ?: it}") }
                } else {
                    profiling = false
                    toast("Stopping profiler...")
                    Thread {
                        val out = File(app.cacheDir, "hermes_profile.json")
                        val summary = runCatching { stopSampling(loader, out) }.getOrElse { "profiler stop failed: ${it.cause ?: it}" }

                        summary.lines().chunked(12).forEach { Log.i("profile\n" + it.joinToString("\n")) }
                        main.post {
                            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("profile", summary))
                            Toast.makeText(app, "Profile copied to the clipboard.", Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            }
        }

        XposedBridge.hookMethod(Activity::class.java.getDeclaredMethod("dispatchKeyEvent", KeyEvent::class.java), hook)
        Log.i("Profiler key trigger installed (hold Volume Down)")
    }

    private fun call(loader: ClassLoader, name: String, vararg args: String) {
        val cls = Class.forName(PROFILER, true, loader)
        val method = if (args.isEmpty()) cls.getMethod(name) else cls.getMethod(name, String::class.java)
        method.invoke(null, *args)
    }

    private fun summarize(file: File): String = runCatching {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val samples = root["samples"] as? JsonArray ?: return "no samples in ${file.name} (keys: ${root.keys})"
        val frames = root["stackFrames"] as? JsonObject ?: return "no stackFrames"

        fun label(id: String): String {
            val f = frames[id]?.jsonObject ?: return "?$id"
            val name = f["name"]?.jsonPrimitive?.content?.ifEmpty { null } ?: "(anon)"
            val line = f["line"]?.jsonPrimitive?.content
            val col = f["column"]?.jsonPrimitive?.content
            return if (line != null) "$name @$line:$col" else name
        }

        val self = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        for (s in samples) {
            val sf = s.jsonObject["sf"]?.jsonPrimitive?.content ?: continue
            self.merge(label(sf), 1, Int::plus)
            val seen = HashSet<String>()
            var cur: String? = sf
            var guard = 0
            while (cur != null && guard++ < 200) {
                if (seen.add(label(cur))) total.merge(label(cur), 1, Int::plus)
                cur = frames[cur]?.jsonObject?.get("parent")?.jsonPrimitive?.content
            }
        }

        val n = samples.size
        buildString {
            appendLine("samples=$n file=${file.length() / 1024}KB")
            appendLine("-- top self --")
            self.entries.sortedByDescending { it.value }.take(25).forEach {
                appendLine("${it.value * 100 / n}% ${it.value}  ${it.key}")
            }
            appendLine("-- top inclusive --")
            total.entries.sortedByDescending { it.value }.take(25).forEach {
                appendLine("${it.value * 100 / n}% ${it.value}  ${it.key}")
            }
        }
    }.getOrElse {
        Log.e("profiler summarize failed", it)
        "summarize failed: $it"
    }
}
