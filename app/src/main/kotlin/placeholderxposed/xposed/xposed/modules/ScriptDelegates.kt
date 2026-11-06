package placeholderxposed.xposed.modules

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import placeholderxposed.xposed.Utils.Log

/**
 * Finds the class that implements React Native's JSBundleLoaderDelegate.
 *
 * Older Discord builds expose it under a stable name (ReactInstance$loadJSBundle$1, CatalystInstanceImpl).
 * Newer builds fold it into an obfuscated class (352.17 uses org.chromium.net.j, which implements a pile
 * of unrelated interfaces), so the name can't be relied on. The JSBundleLoader subclasses keep their
 * names though, and each hands the delegate to loadScript, so we also watch those and register
 * whatever class shows up.
 */
object ScriptDelegates {
    private val KNOWN_DELEGATES = listOf(
        "com.facebook.react.runtime.ReactInstance\$loadJSBundle$1",
        "com.facebook.react.runtime.ReactInstance$1",
        "com.facebook.react.bridge.CatalystInstanceImpl",
    )

    private val LOADERS = listOf(
        "com.facebook.react.bridge.JSBundleLoader\$Companion\$createAssetLoader$1",
        "com.facebook.react.bridge.JSBundleLoader\$Companion\$createFileLoader$1",
        "com.facebook.react.bridge.JSBundleLoader\$Companion\$createCachedBundleFromNetworkLoader$1",
        "com.facebook.react.bridge.JSBundleLoader\$Companion\$createCachedSplitBundleFromNetworkLoader$1",
    )

    /** Calls [onDelegate] once for every distinct delegate class, now or when it is first used. */
    fun install(classLoader: ClassLoader, onDelegate: (Class<*>) -> Unit) {
        val seen = HashSet<Class<*>>()
        val register = { cls: Class<*> ->
            val isNew = synchronized(seen) { seen.add(cls) }
            if (isNew) runCatching { onDelegate(cls) }.onFailure {
                Log.e("Failed to hook script delegate ${cls.name}:", it)
            }
        }

        KNOWN_DELEGATES.mapNotNull { load(classLoader, it) }.forEach(register)

        val delegateType = load(classLoader, "com.facebook.react.bridge.JSBundleLoaderDelegate") ?: return
        val watcher = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val delegate = param.args.firstOrNull() ?: return
                if (delegateType.isInstance(delegate)) register(delegate.javaClass)
            }
        }

        for (name in LOADERS) {
            val loader = load(classLoader, name) ?: continue
            runCatching {
                XposedBridge.hookMethod(loader.getDeclaredMethod("loadScript", delegateType), watcher)
            }.onFailure { Log.e("Failed to watch $name:", it) }
        }
    }

    private fun load(classLoader: ClassLoader, name: String): Class<*>? =
        runCatching { classLoader.loadClass(name) }.getOrNull()
}
