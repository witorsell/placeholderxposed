package placeholderxposed.xposed.modules.bridge

import android.os.Looper

/**
 * Samples the Java stacks of the UI thread and React's worker threads while the Hermes profiler runs.
 *
 * The JS profile only shows what the JS thread does. When most of that time is a native call, the
 * answer is on the other side: view creation, measure, layout and draw on the main thread, or the
 * JS thread waiting on it. Thread.getStackTrace is coarse but needs no native code and is enough to
 * see which Java methods dominate.
 */
class ThreadSampler(private val intervalMs: Long = 10) : Thread("placeholder-sampler") {
    @Volatile private var running = true

    private class Stats {
        var samples = 0
        var idle = 0
        val self = HashMap<String, Int>()
        val inclusive = HashMap<String, Int>()
    }

    private val stats = LinkedHashMap<String, Stats>()
    private val targets = LinkedHashMap<String, Thread>()
    private var lastRefresh = 0L

    init { isDaemon = true }

    fun finish(): String {
        running = false
        join(2000)
        return report()
    }

    override fun run() {
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastRefresh > 1000) {
                refreshTargets()
                lastRefresh = now
            }

            for ((name, thread) in targets) {
                val trace = runCatching { thread.stackTrace }.getOrNull() ?: continue
                if (trace.isEmpty()) continue

                val s = stats.getOrPut(name) { Stats() }
                s.samples++

                val top = trace[0]
                if (top.methodName == "nativePollOnce" || (top.className == "java.lang.Object" && top.methodName == "wait")) {
                    s.idle++
                    continue
                }

                s.self.merge(label(top), 1, Int::plus)
                val seen = HashSet<String>()
                for (frame in trace) if (seen.add(label(frame))) s.inclusive.merge(label(frame), 1, Int::plus)
            }

            runCatching { sleep(intervalMs) }
        }
    }

    private fun label(e: StackTraceElement) = "${e.className.substringAfterLast('.')}.${e.methodName}"

    private fun refreshTargets() {
        val main = Looper.getMainLooper().thread
        targets["main"] = main

        var group = currentThread().threadGroup
        while (group?.parent != null) group = group.parent
        val all = arrayOfNulls<Thread>((group?.activeCount() ?: 0) * 2 + 8)
        val n = group?.enumerate(all, true) ?: 0
        for (i in 0 until n) {
            val t = all[i] ?: continue
            if (t.name.startsWith("mqt_") && t.isAlive) targets[t.name] = t
        }
    }

    private fun report(): String = buildString {
        for ((name, s) in stats) {
            val busy = s.samples - s.idle
            appendLine("== thread $name: samples=${s.samples} busy=${if (s.samples > 0) busy * 100 / s.samples else 0}%")
            if (busy == 0) continue
            appendLine("-- top self --")
            s.self.entries.sortedByDescending { it.value }.take(15).forEach {
                appendLine("${it.value * 100 / s.samples}% ${it.value}  ${it.key}")
            }
            appendLine("-- top inclusive --")
            s.inclusive.entries.sortedByDescending { it.value }.take(25).forEach {
                appendLine("${it.value * 100 / s.samples}% ${it.value}  ${it.key}")
            }
        }
    }
}
