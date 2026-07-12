package placeholderxposed.xposed.modules.bridge

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import placeholderxposed.xposed.Module

/**
 * Spoofs what Discord reports about the device. Discord reads all of this from
 * com.discord.device.DeviceModule (its own React Native TurboModule, unobfuscated
 * in the release build), com.discord.device.utils.DeviceHardwareInfoKt, and
 * com.discord.client_info.ClientInfoModule. Hooking all three keeps the spoofed
 * identity internally consistent instead of e.g. reporting a fake model with the
 * real device's actual RAM/SoC. Verified against the real gateway IDENTIFY payload
 * (captured live via the Gateway Diagnostics plugin): "device" comes out in
 * Build.DEVICE codename form, and "device_vendor_id" is the persistent per-install
 * UUID from ClientInfoCache.getDeviceVendorId(), which docs.discord.food specifically
 * calls out as the actual anti-abuse device identifier on Android, worth spoofing
 * alongside the cosmetic device/model fields, not instead of them.
 */
object DeviceSpoofModule : Module() {
    private var model: String? = null
    private var brand: String? = null
    private var manufacturer: String? = null
    private var product: String? = null
    private var device: String? = null
    private var socName: String? = null
    private var ramSize: String? = null
    private var maxCpuFreq: String? = null
    private var deviceVendorId: String? = null

    override fun onLoad(param: XC_LoadPackage.LoadPackageParam) {
        val deviceModuleClass = param.classLoader.safeLoadClass("com.discord.device.DeviceModule")
        if (deviceModuleClass == null) {
            XposedBridge.log("[DeviceSpoofModule] com.discord.device.DeviceModule not found")
        } else {
            deviceModuleClass.hookMethod("getTypedExportedConstants") {
                after {
                    @Suppress("UNCHECKED_CAST")
                    val original = result as? Map<String, Any?> ?: return@after
                    val spoofed = HashMap(original)
                    device?.let { spoofed["device"] = it }
                    model?.let { spoofed["deviceModel"] = it }
                    brand?.let { spoofed["deviceBrand"] = it }
                    product?.let { spoofed["deviceProduct"] = it }
                    manufacturer?.let { spoofed["deviceManufacturer"] = it }
                    result = spoofed
                }
            }
        }

        val hardwareInfoClass = param.classLoader.safeLoadClass("com.discord.device.utils.DeviceHardwareInfoKt")
        if (hardwareInfoClass == null) {
            XposedBridge.log("[DeviceSpoofModule] com.discord.device.utils.DeviceHardwareInfoKt not found")
            return
        }

        runCatching {
            hardwareInfoClass.hookMethod("socName") {
                after { socName?.let { result = it } }
            }
        }.onFailure { XposedBridge.log("[DeviceSpoofModule] socName hook failed: $it") }

        runCatching {
            hardwareInfoClass.hookMethod("ramSize", android.content.Context::class.java) {
                after { ramSize?.let { result = it } }
            }
        }.onFailure { XposedBridge.log("[DeviceSpoofModule] ramSize hook failed: $it") }

        runCatching {
            hardwareInfoClass.hookMethod("maxCpuFreq") {
                after { maxCpuFreq?.let { result = it } }
            }
        }.onFailure { XposedBridge.log("[DeviceSpoofModule] maxCpuFreq hook failed: $it") }

        val clientInfoModuleClass = param.classLoader.safeLoadClass("com.discord.client_info.ClientInfoModule")
        if (clientInfoModuleClass == null) {
            XposedBridge.log("[DeviceSpoofModule] com.discord.client_info.ClientInfoModule not found")
        } else {
            runCatching {
                clientInfoModuleClass.hookMethod("getTypedExportedConstants") {
                    after {
                        val vendorId = deviceVendorId ?: return@after
                        @Suppress("UNCHECKED_CAST")
                        val original = result as? Map<String, Any?> ?: return@after
                        val spoofed = HashMap(original)
                        spoofed["DeviceVendorID"] = vendorId
                        result = spoofed
                    }
                }
            }.onFailure { XposedBridge.log("[DeviceSpoofModule] ClientInfoModule hook failed: $it") }
        }

        BridgeModule.registerMethod("device.spoof") { args ->
            @Suppress("UNCHECKED_CAST")
            val opts = args.getOrNull(0) as? Map<String, Any?>
            device = opts?.get("device") as? String
            model = opts?.get("model") as? String
            brand = opts?.get("brand") as? String
            product = opts?.get("product") as? String
            manufacturer = opts?.get("manufacturer") as? String
            socName = opts?.get("socName") as? String
            ramSize = opts?.get("ramSize") as? String
            maxCpuFreq = opts?.get("maxCpuFreq") as? String
            deviceVendorId = opts?.get("deviceVendorId") as? String
            currentState()
        }

        BridgeModule.registerMethod("device.resetSpoof") {
            device = null; model = null; brand = null; product = null; manufacturer = null
            socName = null; ramSize = null; maxCpuFreq = null; deviceVendorId = null
            currentState()
        }

        BridgeModule.registerMethod("device.getSpoofState") {
            currentState()
        }
    }

    private fun currentState(): Map<String, Any?> = mapOf(
        "device" to device,
        "model" to model,
        "brand" to brand,
        "product" to product,
        "manufacturer" to manufacturer,
        "socName" to socName,
        "ramSize" to ramSize,
        "maxCpuFreq" to maxCpuFreq,
        "deviceVendorId" to deviceVendorId,
    )
}
