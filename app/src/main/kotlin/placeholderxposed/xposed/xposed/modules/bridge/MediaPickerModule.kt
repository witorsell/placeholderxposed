package placeholderxposed.xposed.modules.bridge

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.callbacks.XC_LoadPackage
import placeholderxposed.xposed.Module
import placeholderxposed.xposed.Utils.Log

object MediaPickerModule : Module() {
    private var pickResult: String? = null
    private var isPicking = false
    private var currentActivity: Activity? = null

    override fun onActivity(activity: Activity) {
        currentActivity = activity
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        with(packageParam) {
            classLoader.loadClass("android.app.Activity").hookMethod("onActivityResult", Int::class.java, Int::class.java, Intent::class.java) {
                before {
                    val requestCode = args[0] as Int
                    if (requestCode == 9999) {
                        val resultCode = args[1] as Int
                        val data = args[2] as? Intent
                        if (resultCode == Activity.RESULT_OK && data != null) {
                            val uri = data.data
                            pickResult = uri?.toString() ?: "CANCELLED"
                        } else {
                            pickResult = "CANCELLED"
                        }
                        isPicking = false
                    }
                }
            }

            BridgeModule.registerMethod("mediaPicker.start") {
                if (isPicking) return@registerMethod null
                isPicking = true
                pickResult = null
                try {
                    val activity = currentActivity
                    
                    if (activity != null) {
                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                        intent.type = "*/*"
                        val mimeTypes = arrayOf("image/*", "video/*")
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                        activity.startActivityForResult(intent, 9999)
                    } else {
                        Log.e("MediaPicker start failed: Activity is null")
                        isPicking = false
                        pickResult = "CANCELLED"
                    }
                } catch (e: Exception) {
                    Log.e("MediaPicker start failed", e)
                    isPicking = false
                    pickResult = "CANCELLED"
                }
                null
            }

            BridgeModule.registerMethod("mediaPicker.poll") {
                val res = pickResult
                if (res != null) {
                    pickResult = null // reset
                }
                res
            }
        }
    }
}
