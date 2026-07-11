package placeholderxposed.xposed.modules

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
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
import placeholderxposed.xposed.modules.bridge.BridgeModule
import placeholderxposed.xposed.px

/**
 * Draws rounded chat bubbles behind messages and rounds avatars by hooking Discord's
 * native message view (com.discord.chat.presentation.message.MessageView).
 *
 * Ported from rainXposed. Drawing happens natively because the chat surface is rendered
 * by native Kotlin, not React, so there is no JS message tree to wrap.
 *
 * Bubbles default to enabled. The bridge methods below let the JS layer toggle and
 * restyle them at runtime.
 */
object BubbleModule : Module() {
    private var configureAccessoriesMarginHook: XC_MethodHook.Unhook? = null
    private var configureAuthorHook: XC_MethodHook.Unhook? = null

    // Whether the after-hooks actually draw. On by default so a fresh install shows
    // bubbles without needing the JS side wired up first.
    private var hooksEnabled = true

    // Guards one-time installation of the method hooks.
    private var hooked = false

    private val DEFAULT_AVATAR_CURVE_RADIUS = 12.px.toFloat()
    private val DEFAULT_BUBBLE_CURVE_RADIUS = 12.px.toFloat()
    private val DEFAULT_BUBBLE_COLOR = 0x66000000.toInt()
    private val PADDING_SMALL = 6.px
    private val PADDING_MEDIUM = 8.px
    private val PADDING_LARGE = 12.px

    private var avatarCurveRadius = DEFAULT_AVATAR_CURVE_RADIUS
    private var bubbleCurveRadius = DEFAULT_BUBBLE_CURVE_RADIUS
    private var chatBubbleColor = DEFAULT_BUBBLE_COLOR

    override fun onContext(context: Context) {
        BridgeModule.registerMethod("bubbles.hook") {
            hookBubbles()
            null
        }

        BridgeModule.registerMethod("bubbles.unhook") {
            unhookBubbles()
            null
        }

        BridgeModule.registerMethod("bubbles.configure") {
            val avatarRadius = it.getOrNull(0) as? Number
            val bubbleRadius = it.getOrNull(1) as? Number
            val bubbleColor = it.getOrNull(2) as? Number
            configure(avatarRadius?.toFloat(), bubbleRadius?.toFloat(), bubbleColor?.toInt())
            null
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
            val configureAccessoriesMarginMethod = methods.find { it.name == "configureAccessoriesMargin" }
            val configureAuthorMethod = methods.find { it.name == "configureAuthor" }

            if (configureAccessoriesMarginMethod != null) {
                configureAccessoriesMarginHook = XposedBridge.hookMethod(configureAccessoriesMarginMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (!hooksEnabled) return
                        val binding = XposedHelpers.getObjectField(param.thisObject, "binding")
                        val accessoriesView = binding?.javaClass?.getField("accessoriesView")?.get(binding) as? ViewGroup
                        accessoriesView?.let { adjustMarginsForAccessories(it) }
                    }
                })
            } else {
                XposedBridge.log("[BubbleModule] configureAccessoriesMargin method not found")
            }

            if (configureAuthorMethod != null) {
                configureAuthorHook = XposedBridge.hookMethod(configureAuthorMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (!hooksEnabled) return
                        val view = param.thisObject as ViewGroup
                        applyRoundedSquareProfilePicture(view)
                        applyBubbleChat(view)
                    }
                })
            } else {
                XposedBridge.log("[BubbleModule] configureAuthor method not found")
            }

            hooked = true
        } catch (e: Throwable) {
            XposedBridge.log("[BubbleModule] Failed to hook methods: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun adjustMarginsForAccessories(view: ViewGroup) {
        val marginLayoutParams = view.layoutParams as MarginLayoutParams
        val topMargin = marginLayoutParams.topMargin

        marginLayoutParams.setMargins(marginLayoutParams.leftMargin, 0, marginLayoutParams.rightMargin, marginLayoutParams.bottomMargin)
        view.layoutParams = marginLayoutParams

        view.setPadding(view.paddingLeft, topMargin + view.paddingTop, view.paddingRight, view.paddingBottom)
    }

    private fun applyRoundedSquareProfilePicture(viewGroup: ViewGroup) {
        val imageView = viewGroup.children.filterIsInstance<ImageView>().firstOrNull() ?: return
        imageView.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View?, outline: Outline?) {
                    outline?.setRoundRect(0, 0, view!!.width, view.height, avatarCurveRadius)
                }
            }
            translationY = 4.px.toFloat()
        }
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
            val messageAccessoriesDecoration = accessoriesView.javaClass
                .getDeclaredField("messageAccessoriesDecoration").apply { isAccessible = true }.get(accessoriesView)
            val leftMarginPx = try {
                messageAccessoriesDecoration.javaClass.getDeclaredField("leftMarginPx").apply { isAccessible = true }.get(messageAccessoriesDecoration) as? Int
            } catch (e: NoSuchFieldException) {
                try {
                    messageAccessoriesDecoration.javaClass.getDeclaredField("leftMargin").apply { isAccessible = true }.get(messageAccessoriesDecoration) as? Int
                } catch (e: NoSuchFieldException) {
                    try {
                        messageAccessoriesDecoration.javaClass.getDeclaredField("startMargin").apply { isAccessible = true }.get(messageAccessoriesDecoration) as? Int
                    } catch (e: NoSuchFieldException) {
                        try {
                            val messageMargins = messageAccessoriesDecoration.javaClass.getDeclaredField("margins").apply { isAccessible = true }.get(messageAccessoriesDecoration)
                            messageMargins.javaClass.getDeclaredField("leftMarginPx").apply { isAccessible = true }.get(messageMargins) as? Int
                        } catch (e: NoSuchFieldException) {
                            return
                        }
                    }
                }
            } ?: return

            accessoriesView.setBubbleBackground(leftMarginPx, start, true)
            accessoriesView.setPadding(PADDING_LARGE, if (start) PADDING_MEDIUM else 0, PADDING_SMALL, PADDING_MEDIUM)
            accessoriesView.translationX = -PADDING_SMALL.toFloat()
        } catch (e: Throwable) {
            XposedBridge.log("[BubbleModule] setAccessoryBubbleBackground failed: ${e.message}")
        }
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
