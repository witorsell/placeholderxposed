package placeholderxposed.xposed.modules

import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import placeholderxposed.xposed.Constants
import placeholderxposed.xposed.Module
import placeholderxposed.xposed.Utils.Companion.JSON
import placeholderxposed.xposed.Utils.Log
import java.io.File

/**
 * Flips React Native feature flags that Discord leaves off.
 *
 * A JS profile of 352.17 spends about 70% of its busy time inside Fabric's native commit
 * (`completeRoot`: shadow tree diff, Yoga layout, text measurement) and only a few percent in React
 * render, so these layout and diffing flags are the knobs that can actually help.
 *
 * Discord installs its provider through ReactNativeFeatureFlagsCxxInterop.override(provider). We hook
 * that call and override the chosen flags on whatever class it passes, which avoids depending on the
 * obfuscated provider class name.
 *
 * Override the preset with `files/pyoncord/rn_flags.json`, e.g.
 * `{"enabled": true, "flags": {"enableViewCulling": true, "enableDoubleMeasurementFixAndroid": false}}`.
 */
object RnFlagsModule : Module() {
    private const val CXX = "com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsCxxInterop"

    /** Flags whose provider method we hooked, for the profiler report. */
    val applied = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val PRESET = mapOf(
        "enableAndroidTextMeasurementOptimizations" to true,
        "enableDoubleMeasurementFixAndroid" to true,
        "useLISAlgorithmInDifferentiator" to true,
        "enableDifferentiatorMutationVectorPreallocation" to true,
    )

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val config = readConfig(File(appInfo.dataDir, "${Constants.FILES_DIR}/rn_flags.json"))
        if (config.enabled == false) {
            Log.i("RnFlags disabled by rn_flags.json")
            return@with
        }

        val flags = PRESET + config.flags
        val cxx = classLoader.safeLoadClass(CXX)
        if (cxx == null) {
            Log.e("RnFlags: $CXX not found")
            return@with
        }

        cxx.hookMethod("override", Any::class.java) {
            before {
                val provider = args[0] ?: return@before
                val cls = provider.javaClass
                for ((name, value) in flags) {
                    runCatching {
                        cls.method(name).hook { before { result = value } }
                    }.onSuccess {
                        applied.add("$name=$value")
                        Log.i("RnFlags ${cls.name}.$name -> $value")
                    }.onFailure {
                        Log.e("RnFlags $name not hookable on ${cls.name}: ${it.message}")
                    }
                }
            }
        }
        Log.i("RnFlags watching ReactNativeFeatureFlagsCxxInterop.override (${flags.size} flags)")
    }

    private class Config(val enabled: Boolean?, val flags: Map<String, Boolean>)

    private fun readConfig(file: File): Config {
        if (!file.isFile) return Config(null, emptyMap())
        return runCatching {
            val root = JSON.parseToJsonElement(file.readText()).jsonObject
            val flags = (root["flags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap()
            Config(root["enabled"]?.jsonPrimitive?.boolean, flags)
        }.getOrElse {
            Log.e("RnFlags: bad rn_flags.json: ${it.message}")
            Config(null, emptyMap())
        }
    }
}
