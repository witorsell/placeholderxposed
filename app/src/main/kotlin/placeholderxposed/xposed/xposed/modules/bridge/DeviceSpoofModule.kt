package placeholderxposed.xposed.modules.bridge

import android.content.Context
import android.os.Build
import placeholderxposed.xposed.Module
import placeholderxposed.xposed.Utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object DeviceSpoofModule : Module() {
    override fun onContext(context: Context) {
        BridgeModule.registerMethod("device.spoof") { it ->
            if (it.isEmpty()) return@registerMethod null
            @Suppress("UNCHECKED_CAST")
            val map = it[0] as? Map<String, String> ?: return@registerMethod null
            
            map.forEach { (key, value) ->
                try {
                    val field = Build::class.java.getField(key)
                    field.isAccessible = true
                    
                    try {
                        val modifiersField = Field::class.java.getDeclaredField("accessFlags")
                        modifiersField.isAccessible = true
                        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
                    } catch (e: Exception) {
                        // Might not work on newer Android, but field.set might still succeed
                    }
                    
                    field.set(null, value)
                    Log.i("Spoofed Build.$key to $value")
                } catch (e: Exception) {
                    Log.e("Failed to spoof Build.$key", e)
                }
            }
            null
        }
    }
}
