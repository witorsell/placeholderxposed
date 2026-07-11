package placeholderxposed.xposed.modules.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import kotlinx.coroutines.isActive
import placeholderxposed.xposed.Module
import placeholderxposed.xposed.Utils.Log
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

object VirtualCameraModule : Module() {
    private var feedPath: String? = null
    private var isEnabled = false
    private var spoofJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var currentObserver: Any? = null
    private var videoFrameClass: Class<*>? = null
    private var javaI420BufferClass: Class<*>? = null
    private var videoFrameBufferClass: Class<*>? = null

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        with(packageParam) {
            BridgeModule.registerMethod("camera.setMedia") { args ->
            val path = args.getOrNull(0) as? String
            if (path != null) {
                feedPath = path
                isEnabled = true
                Log.i("VirtualCamera: Feed set to $path")
            } else {
                isEnabled = false
                Log.i("VirtualCamera: Disabled")
            }
            null
        }

        videoFrameClass = classLoader.loadClass("org.webrtc.VideoFrame")
        javaI420BufferClass = classLoader.loadClass("org.webrtc.JavaI420Buffer")
        videoFrameBufferClass = classLoader.loadClass("org.webrtc.VideoFrame\$Buffer")
        val observerClass = classLoader.loadClass("org.webrtc.CapturerObserver")
        val surfaceTextureHelperClass = classLoader.loadClass("org.webrtc.SurfaceTextureHelper")
        val contextClass = Context::class.java

        val hookInitialize = { capturerClass: Class<*> ->
            capturerClass.hookMethod("initialize", surfaceTextureHelperClass, contextClass, observerClass) {
                before {
                    val realObserver = args[2]
                    currentObserver = realObserver
                    Log.i("VirtualCamera: Intercepted initialize.")

                    args[2] = Proxy.newProxyInstance(classLoader, arrayOf(observerClass)) { _, method, methodArgs ->
                        if (isEnabled) {
                            when (method.name) {
                                "onCapturerStarted", "onCapturerStopped", "onFrameCaptured" -> return@newProxyInstance null
                            }
                        }
                        method.invoke(realObserver, *(methodArgs ?: emptyArray()))
                    }
                }
            }

            capturerClass.hookMethod("startCapture", Int::class.java, Int::class.java, Int::class.java) {
                before {
                    if (isEnabled) {
                        val width = args[0] as Int
                        val height = args[1] as Int
                        val fps = args[2] as Int
                        Log.i("VirtualCamera: Intercepting startCapture($width, $height, $fps)")
                        result = null // Block real camera

                        currentObserver?.let { obs ->
                            obs.javaClass.getMethod("onCapturerStarted", Boolean::class.java).invoke(obs, true)
                        }

                        spoofJob?.cancel()
                        spoofJob = scope.launch {
                            pumpFrames(width, height, fps)
                        }
                    }
                }
            }

            capturerClass.hookMethod("stopCapture") {
                before {
                    if (isEnabled) {
                        Log.i("VirtualCamera: Intercepting stopCapture()")
                        result = null
                        spoofJob?.cancel()
                        currentObserver?.let { obs ->
                            obs.javaClass.getMethod("onCapturerStopped").invoke(obs)
                        }
                    }
                }
            }
        }

        hookInitialize(classLoader.loadClass("org.webrtc.Camera1Capturer"))
        hookInitialize(classLoader.loadClass("org.webrtc.Camera2Capturer"))
        }
    }

    private suspend fun pumpFrames(targetWidth: Int, targetHeight: Int, fps: Int) {
        val path = feedPath ?: return
        try {
            val bitmap = BitmapFactory.decodeFile(path) ?: return
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            val buffer = bitmapToI420(scaled) ?: return
            
            val frameDelay = 1000L / (if (fps > 0) fps else 30)
            val constructor = videoFrameClass!!.getConstructor(videoFrameBufferClass, Int::class.java, Long::class.java)
            val retainMethod = videoFrameBufferClass!!.getMethod("retain")
            val releaseMethod = videoFrameClass!!.getMethod("release")
            val onFrameCaptured = currentObserver!!.javaClass.getMethod("onFrameCaptured", videoFrameClass)

            while (currentCoroutineContext().isActive) {
                retainMethod.invoke(buffer)
                val frame = constructor.newInstance(buffer, 0, System.nanoTime())
                onFrameCaptured.invoke(currentObserver, frame)
                releaseMethod.invoke(frame)
                delay(frameDelay)
            }
        } catch (e: Exception) {
            Log.e("VirtualCamera: Error pumping frames", e)
        }
    }

    private fun bitmapToI420(bitmap: Bitmap): Any? {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val allocateMethod = javaI420BufferClass!!.getMethod("allocate", Int::class.java, Int::class.java)
            val buffer = allocateMethod.invoke(null, width, height)
            
            val getDataY = javaI420BufferClass!!.getMethod("getDataY")
            val getDataU = javaI420BufferClass!!.getMethod("getDataU")
            val getDataV = javaI420BufferClass!!.getMethod("getDataV")
            val getStrideY = javaI420BufferClass!!.getMethod("getStrideY")
            val getStrideU = javaI420BufferClass!!.getMethod("getStrideU")
            val getStrideV = javaI420BufferClass!!.getMethod("getStrideV")
            
            val yBuffer = getDataY.invoke(buffer) as ByteBuffer
            val uBuffer = getDataU.invoke(buffer) as ByteBuffer
            val vBuffer = getDataV.invoke(buffer) as ByteBuffer
            
            val yStride = getStrideY.invoke(buffer) as Int
            val uStride = getStrideU.invoke(buffer) as Int
            val vStride = getStrideV.invoke(buffer) as Int
            
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val argb = pixels[j * width + i]
                    val r = (argb shr 16) and 0xff
                    val g = (argb shr 8) and 0xff
                    val b = argb and 0xff
                    
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuffer.put(j * yStride + i, y.toByte())
                    
                    if (j % 2 == 0 && i % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        uBuffer.put((j / 2) * uStride + (i / 2), u.toByte())
                        vBuffer.put((j / 2) * vStride + (i / 2), v.toByte())
                    }
                }
            }
            return buffer
        } catch (e: Exception) {
            Log.e("VirtualCamera: Error converting bitmap", e)
            return null
        }
    }
}
