package placeholderxposed.xposed.modules

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import java.io.File
import java.util.WeakHashMap
import org.json.JSONObject
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.children
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import placeholderxposed.xposed.Module
import placeholderxposed.xposed.Utils.Log
import placeholderxposed.xposed.modules.bridge.BridgeModule
import placeholderxposed.xposed.px

/**
 * Draws rounded chat bubbles behind messages and rounds avatars by hooking Discord's
 * native message view (com.discord.chat.presentation.message.MessageView).
 *
 * Ported from rainXposed. Drawing happens natively because the chat surface is rendered
 * by native Kotlin, not React, so there is no JS message tree to wrap.
 *
 * Bubbles default to disabled; the Bubble Chat plugin turns them on over the Native
 * Bridge. The bridge methods below let the JS layer toggle and restyle them at runtime.
 */
object BubbleModule : Module() {
    private var configureAccessoriesMarginHook: XC_MethodHook.Unhook? = null
    private var configureAuthorHook: XC_MethodHook.Unhook? = null

    // Off by default: bubbles are an opt-in plugin now, not a built-in feature, so a
    // fresh install shouldn't restyle chat until the user actually asks for it.
    private var hooksEnabled = false

    // Guards one-time installation of the method hooks.
    private var hooked = false

    // Avatar corner radius as a percentage of the avatar size (0 = square, 50 = full circle).
    private val DEFAULT_AVATAR_CURVE_RADIUS = 30f
    // How far the avatar (and its decoration) drop to sit lower inside the bubble.
    private val AVATAR_NUDGE = 4.px.toFloat()
    private val DEFAULT_BUBBLE_CURVE_RADIUS = 12.px.toFloat()
    private val DEFAULT_BUBBLE_COLOR = 0x66000000.toInt()
    private val PADDING_SMALL = 6.px
    private val PADDING_MEDIUM = 8.px
    private val PADDING_LARGE = 12.px

    private var avatarCurveRadius = DEFAULT_AVATAR_CURVE_RADIUS
    private var bubbleCurveRadius = DEFAULT_BUBBLE_CURVE_RADIUS
    private var chatBubbleColor = DEFAULT_BUBBLE_COLOR

    private var configFile: File? = null
    private var lastConfigMtime = -1L
    private var lastConfigCheck = 0L

    override fun onContext(context: Context) {
        // The ChatBubbles core plugin writes files/pyoncord/bubbles.json. Track the file and
        // re-read it live (see maybeReloadConfig) so settings apply on a JS reload, without a
        // full app restart which the native process would otherwise need.
        configFile = File(context.filesDir, "pyoncord/bubbles.json")
        reloadConfigFromDisk()

        BridgeModule.registerMethod("bubbles.hook") {
            hookBubbles()
            hooksEnabled
        }

        BridgeModule.registerMethod("bubbles.unhook") {
            unhookBubbles()
            hooksEnabled
        }

        BridgeModule.registerMethod("bubbles.configure") {
            val avatarRadius = it.getOrNull(0) as? Number
            val bubbleRadius = it.getOrNull(1) as? Number
            val bubbleColor = it.getOrNull(2) as? Number
            configure(avatarRadius?.toFloat(), bubbleRadius?.toFloat(), bubbleColor?.toInt())
            currentBubbleState()
        }
    }

    override fun onLoad(param: XC_LoadPackage.LoadPackageParam) {
        if (hooked) return

        val messageViewClassName = "com.discord.chat.presentation.message.MessageView"
        val messageViewClass = XposedHelpers.findClassIfExists(messageViewClassName, param.classLoader)

        if (messageViewClass == null) {
            XposedBridge.log("[BubbleModule] MessageView class not found")
            findAlternativeMessageClasses(param.classLoader)
            return
        }

        XposedBridge.log("[BubbleModule] Found MessageView: $messageViewClass")

        try {
            val methods = messageViewClass.declaredMethods

            // Discord 352 obfuscated MessageView's methods (configureAuthor is now `b`/`f`), but the
            // bind methods still take the unobfuscated com.discord.chat.bridge.Message first, so find
            // them by that. The old names stay as a fallback for earlier builds.
            val bindMethods = methods.filter { it.parameterTypes.firstOrNull()?.name == "com.discord.chat.bridge.Message" }
                .ifEmpty { methods.filter { it.name == "configureAuthor" || it.name == "configureAccessoriesMargin" } }

            if (bindMethods.isEmpty()) {
                Log.e("BubbleModule: no bind methods found on MessageView")
                findAlternativeMessageClasses(param.classLoader)
                return
            }

            for (method in bindMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        maybeReloadConfig()
                        if (!hooksEnabled) return
                        val view = param.thisObject as? ViewGroup ?: return
                        runCatching {
                            // Idempotent, so it doesn't matter that more than one bind method fires per bind.
                            findAccessoriesView(view)?.let { adjustMarginsForAccessories(it) }
                            applyRoundedSquareProfilePicture(view)
                            applyBubbleChat(view)
                        }.onFailure { Log.e("BubbleModule: applying bubbles failed", it) }
                    }
                })
            }

            Log.i("BubbleModule: hooked ${bindMethods.size} MessageView method(s): ${bindMethods.joinToString { it.name }}")
            hooked = true
        } catch (e: Throwable) {
            Log.e("BubbleModule: failed to hook MessageView", e)
        }
    }

    private fun findAccessoriesView(view: ViewGroup): ViewGroup? =
        view.children.firstOrNull { it.javaClass.simpleName == "MessageAccessoriesView" } as? ViewGroup

    // Original top padding of each accessories view, so repeated binds don't keep adding to it.
    private val accessoriesBasePaddingTop = WeakHashMap<View, Int>()

    private fun adjustMarginsForAccessories(view: ViewGroup) {
        val marginLayoutParams = view.layoutParams as? MarginLayoutParams ?: return
        val topMargin = marginLayoutParams.topMargin
        val basePadding = accessoriesBasePaddingTop.getOrPut(view) { view.paddingTop }

        marginLayoutParams.setMargins(marginLayoutParams.leftMargin, 0, marginLayoutParams.rightMargin, marginLayoutParams.bottomMargin)
        view.layoutParams = marginLayoutParams

        view.setPadding(view.paddingLeft, topMargin + basePadding, view.paddingRight, view.paddingBottom)
    }

    // Depth-first search for the first ImageView. rain assumed the avatar was a direct child
    // ImageView of the message view, but on newer Discord builds it's nested deeper, so a
    // direct-children scan finds nothing and the avatar never gets rounded.
    private fun findFirstImageView(root: ViewGroup): ImageView? {
        for (child in root.children) {
            if (child is ImageView) return child
            if (child is ViewGroup) findFirstImageView(child)?.let { return it }
        }
        return null
    }

    // Resolved once from Discord's resources: the id of author_avatar_decoration, the separate
    // SimpleDraweeView that Discord anchors to the avatar's layout box (see decompiled
    // message_view.xml). Translating the avatar alone leaves it behind, so we move it too.
    private var avatarDecorationId = 0

    private fun findAvatarDecoration(root: View): View? {
        if (avatarDecorationId == 0) {
            avatarDecorationId = root.resources.getIdentifier("author_avatar_decoration", "id", root.context.packageName)
        }
        return if (avatarDecorationId != 0) root.findViewById(avatarDecorationId) else null
    }

    private fun applyRoundedSquareProfilePicture(viewGroup: ViewGroup) {
        val avatarId = viewGroup.resources.getIdentifier("author_avatar", "id", viewGroup.context.packageName)
        val imageView = (if (avatarId != 0) viewGroup.findViewById<View>(avatarId) as? ImageView else null)
            ?: viewGroup.children.filterIsInstance<ImageView>().firstOrNull()
            ?: findFirstImageView(viewGroup)
            ?: return
        imageView.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View?, outline: Outline?) {
                    val v = view ?: return
                    // avatarCurveRadius is a percentage (0 = square, 50 = full circle) so the
                    // corner looks the same regardless of the avatar's pixel size / screen density.
                    val r = (avatarCurveRadius.coerceIn(0f, 50f) / 100f) * minOf(v.width, v.height)
                    outline?.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
            invalidateOutline()
            // Nudge the avatar down so it sits lower in the bubble.
            translationY = AVATAR_NUDGE
        }
        // The decoration is anchored to the avatar's layout box, not its translation, so push it
        // down by the same amount. Both move equally, so they stay concentric and aligned.
        findAvatarDecoration(viewGroup)?.translationY = AVATAR_NUDGE
    }

    private fun applyBubbleChat(viewGroup: ViewGroup) {
        val linearLayout = viewGroup.children.filterIsInstance<LinearLayout>()
            .firstOrNull { v -> v.children.any { c -> c.javaClass.simpleName == "ConstraintLayout" } } ?: return

        applyBubbleBackground(viewGroup, linearLayout)
    }

    private fun applyBubbleBackground(viewGroup: ViewGroup, linearLayout: ViewGroup) {
        val messageHeader = linearLayout.children.firstOrNull { c -> c.javaClass.simpleName == "ConstraintLayout" }

        val headerVisible = messageHeader != null &&
            (messageHeader as? ViewGroup)?.children?.any { it.visibility == View.VISIBLE } == true

        val hasAccessories = viewGroup.children.any { it.javaClass.simpleName == "MessageAccessoriesView" }

        if (headerVisible) {
            linearLayout.setBubbleBackground(0, start = true, end = !hasAccessories)
            linearLayout.setPadding(PADDING_LARGE, PADDING_MEDIUM, 0, if (!hasAccessories) PADDING_MEDIUM else 0)
            linearLayout.translationX = -PADDING_SMALL.toFloat()
        } else {
            linearLayout.background = null
            linearLayout.setPadding(0, 0, 0, 0)
            linearLayout.translationX = 0f
        }

        viewGroup.children.firstOrNull { it.javaClass.simpleName == "MessageAccessoriesView" }?.let { accessoriesView ->
            setAccessoryBubbleBackground(accessoriesView as ViewGroup, !headerVisible)
        }
    }

    private fun setAccessoryBubbleBackground(accessoriesView: ViewGroup, start: Boolean) {
        try {
            val leftMarginPx = findLeftMarginPx(accessoriesView) ?: return

            accessoriesView.setBubbleBackground(leftMarginPx, start, true)
            accessoriesView.setPadding(PADDING_LARGE, if (start) PADDING_MEDIUM else 0, PADDING_SMALL, PADDING_MEDIUM)
            accessoriesView.translationX = -PADDING_SMALL.toFloat()
        } catch (e: Throwable) {
            Log.e("BubbleModule: setAccessoryBubbleBackground failed", e)
        }
    }

    private val marginsRegex = Regex("leftMarginPx=(-?\\d+)")

    // The accessories view keeps a MessageMargins value, but its field name is obfuscated. The data
    // class's toString is a constant ("MessageMargins(leftMarginPx=NN, ...)"), so find it by that.
    private fun findLeftMarginPx(accessoriesView: ViewGroup): Int? {
        var cls: Class<*>? = accessoriesView.javaClass
        while (cls != null && cls != ViewGroup::class.java) {
            for (field in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val value = runCatching { field.apply { isAccessible = true }.get(accessoriesView) }.getOrNull() ?: continue
                val text = runCatching { value.toString() }.getOrNull() ?: continue
                if (text.startsWith("MessageMargins(")) {
                    return marginsRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun ViewGroup.setBubbleBackground(leftMargin: Int, start: Boolean, end: Boolean) {
        val bubble = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(chatBubbleColor)
            cornerRadii = FloatArray(8) { i ->
                when {
                    start && end -> bubbleCurveRadius
                    start && i < 4 -> bubbleCurveRadius
                    !start && i >= 4 -> bubbleCurveRadius
                    else -> 0f
                }
            }
        }
        background = InsetDrawable(bubble, leftMargin, 0, PADDING_SMALL, 0)
    }

    fun hookBubbles() {
        hooksEnabled = true
    }

    fun unhookBubbles() {
        hooksEnabled = false
    }

    fun configure(avatarRadius: Float? = null, bubbleRadius: Float? = null, bubbleColor: Int? = null) {
        avatarRadius?.let { avatarCurveRadius = it }
        bubbleRadius?.let { bubbleCurveRadius = it }
        bubbleColor?.let { chatBubbleColor = it }
    }

    // What the bubble bridge methods hand back to JS, so a caller sees the applied state
    // instead of null. Floats are widened to Double for React Native serialization.
    private fun currentBubbleState(): Map<String, Any?> = mapOf(
        "enabled" to hooksEnabled,
        "avatarRadius" to avatarCurveRadius.toDouble(),
        "bubbleRadius" to bubbleCurveRadius.toDouble(),
        "bubbleColor" to chatBubbleColor,
    )

    // Cheaply re-read bubbles.json when it changes (throttled), so ChatBubbles settings
    // apply after a JS reload without needing a full app restart.
    private fun maybeReloadConfig() {
        val now = System.currentTimeMillis()
        if (now - lastConfigCheck < 500L) return
        lastConfigCheck = now
        val f = configFile ?: return
        val mtime = if (f.exists()) f.lastModified() else -1L
        if (mtime != lastConfigMtime) {
            lastConfigMtime = mtime
            reloadConfigFromDisk()
        }
    }

    // Reads files/pyoncord/bubbles.json written by the ChatBubbles core plugin.
    // Shape: { "enabled": Boolean, "avatarRadius": Number, "bubbleRadius": Number, "bubbleColor": "#rrggbb" }
    // Radii are treated as dp and scaled to px so they're visible at the same scale as the defaults.
    private fun reloadConfigFromDisk() {
        val f = configFile ?: return
        try {
            if (!f.exists()) return
            val json = JSONObject(f.readText())
            hooksEnabled = json.optBoolean("enabled", hooksEnabled)
            if (json.has("avatarRadius")) avatarCurveRadius = json.getDouble("avatarRadius").toFloat()
            if (json.has("bubbleRadius")) bubbleCurveRadius = json.getDouble("bubbleRadius").toFloat()
            val color = json.optString("bubbleColor", "")
            chatBubbleColor = if (color.isNotEmpty())
                runCatching { Color.parseColor(color) }.getOrDefault(DEFAULT_BUBBLE_COLOR)
            else
                DEFAULT_BUBBLE_COLOR
            XposedBridge.log("[BubbleModule] config: enabled=$hooksEnabled avatar=$avatarCurveRadius bubble=$bubbleCurveRadius")
        } catch (e: Throwable) {
            XposedBridge.log("[BubbleModule] config reload failed: ${e.message}")
        }
    }

    private fun isChatBubblesSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.put("isChatBubblesSupported", JsonPrimitive(isChatBubblesSupported()))
    }

    private fun findAlternativeMessageClasses(classLoader: ClassLoader) {
        val potentialClasses = listOf(
            "com.discord.chat.presentation.message.",
            "com.discord.chat.presentation.view.",
            "com.discord.chat.view.",
            "com.discord.presentation.",
        )
        val suffixes = listOf("MessageView", "ChatMessageView", "MessageItemView", "MessageRowView")

        for (pkg in potentialClasses) {
            for (suffix in suffixes) {
                val className = "$pkg$suffix"
                val clazz = XposedHelpers.findClassIfExists(className, classLoader)
                if (clazz != null) {
                    XposedBridge.log("[BubbleModule] Found class: $className")
                    val methods = clazz.declaredMethods.map { it.name }.distinct()
                    XposedBridge.log("[BubbleModule]   Methods: $methods")
                }
            }
        }
    }
}
