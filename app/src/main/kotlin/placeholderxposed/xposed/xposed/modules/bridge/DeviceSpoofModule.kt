package placeholderxposed.xposed.modules.bridge

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import placeholderxposed.xposed.Module
import placeholderxposed.xposed.Utils.Log
import java.io.File
import org.json.JSONObject

object DeviceSpoofModule : Module() {
    private var spoofConfig: JSONObject? = null

    override fun onContext(context: Context) {
        val spoofFile = File(context.filesDir, "pyoncord/spoof.json")
        if (spoofFile.exists()) {
            try {
                spoofConfig = JSONObject(spoofFile.readText())
            } catch (e: Exception) {
                Log.e("Failed to parse spoof.json", e)
            }
        }

        BridgeModule.registerMethod("device.spoof") { it ->
            if (it.isEmpty()) return@registerMethod null
            @Suppress("UNCHECKED_CAST")
            val map = it[0] as? Map<String, String> ?: return@registerMethod null
            
            val json = JSONObject()
            map.forEach { (key, value) -> json.put(key, value) }
            spoofConfig = json
            
            try {
                spoofFile.parentFile?.mkdirs()
                spoofFile.writeText(json.toString())
            } catch (e: Exception) {
                Log.e("Failed to write spoof.json", e)
            }
            null
        }
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        with(packageParam) {
            try {
                classLoader.loadClass("com.discord.device.DeviceModule")
                    .hookMethod("getTypedExportedConstants") {
                        after {
                            val map = result as? MutableMap<String, Any> ?: return@after
                            val config = spoofConfig ?: return@after

                            if (config.has("deviceBrand")) map["deviceBrand"] = config.getString("deviceBrand")
                            if (config.has("deviceModel")) map["deviceModel"] = config.getString("deviceModel")
                            if (config.has("deviceManufacturer")) map["deviceManufacturer"] = config.getString("deviceManufacturer")
                            if (config.has("deviceProduct")) map["deviceProduct"] = config.getString("deviceProduct")
                            if (config.has("device")) map["device"] = config.getString("device")
                        }
                    }
            } catch (e: Exception) {
                Log.e("Failed to hook DeviceModule", e)
            }
        }
    }
}
