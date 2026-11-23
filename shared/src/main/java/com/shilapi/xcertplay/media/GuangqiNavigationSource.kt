package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Guangqi head unit: CarPlay guidance at the car's navigation volume.
 *
 * The head unit's DSP keeps one volume per mode, not per Android stream. Sound from an app that has
 * not asked the car's `AudioSourceManager` for a source plays in the media mode at the media volume,
 * whichever stream it uses. The stock CarLife asks for its navigation source (23) around each
 * prompt. `/system/etc/sources_conf.xml` also has one for CarPlay, `[CarPlay_NAVI]` 27: DSP policy 1
 * (navigation), stream 5, foreground, no package. While it is held, the DSP mixes guidance at the
 * navigation volume and ducks media. A playing media source gets table value 7, which the native
 * arbitration answers with `0x202` (granted, ASR_FOREGROUND_DUCK), never a pause or a stop, so
 * CarPlay music keeps playing.
 *
 * The source is held from the first guidance PCM write until [RELEASE_MILLIS] after the last one.
 * CarPlay may keep the guidance track open between prompts, so the track's lifetime is not the
 * signal. A process that dies holding it gives it back at the next [recover]. On other head units
 * the framework class is missing and this does nothing.
 */
internal class GuangqiNavigationSource(
    private val api: CarAudioSourceApi?,
    private val handler: Handler,
    private val prefs: android.content.SharedPreferences?,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    /** The car's `android.media.AudioSourceManager`, reduced to the two calls used here. */
    interface CarAudioSourceApi {
        /** Returns the source manager's result bits; [ASR_REQUEST_GRANTED] means held. */
        fun request(sourceId: Int, onChange: (Int) -> Unit): Int
        fun abandon(sourceId: Int): Int
    }

    @Volatile private var lastPcmNanos = 0L
    /** Set by the audio threads; a request or a release check is pending or running. */
    @Volatile private var wanted = false
    // Handler thread only below.
    private var held = false
    private var report: (String) -> Unit = {}
    private val check = Runnable { checkIdle() }

    /** Called on every guidance PCM write. */
    fun onNavigationPcm(report: (String) -> Unit) {
        lastPcmNanos = nowNanos()
        if (api == null || wanted) return
        wanted = true
        handler.post { acquire(report) }
    }

    /** The audio sink closed: give the source back now. */
    fun releaseNow() {
        if (api == null) return
        handler.post { release("audio closed") }
    }

    private fun acquire(report: (String) -> Unit) {
        this.report = report
        val car = api ?: return
        if (!held) {
            val result = runCatching { car.request(SOURCE_CARPLAY_NAVI) { action -> onChange(action) } }
            val granted = result.getOrNull()?.let { it and ASR_REQUEST_GRANTED != 0 } == true
            held = granted
            if (granted) prefs?.edit()?.putBoolean(KEY_HELD, true)?.apply()
            log("guidance source $SOURCE_CARPLAY_NAVI requested: " +
                (result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" }
                    ?: "result=0x${Integer.toHexString(result.getOrDefault(0))} held=$granted"))
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, RELEASE_MILLIS)
    }

    private fun checkIdle() {
        val idleMillis = (nowNanos() - lastPcmNanos) / 1_000_000L
        if (idleMillis < RELEASE_MILLIS) {
            handler.postDelayed(check, RELEASE_MILLIS - idleMillis + RECHECK_SLACK_MILLIS)
        } else {
            release("${idleMillis}ms without guidance")
        }
    }

    private fun release(reason: String) {
        handler.removeCallbacks(check)
        if (held) {
            held = false
            val result = runCatching { api?.abandon(SOURCE_CARPLAY_NAVI) }
            prefs?.edit()?.putBoolean(KEY_HELD, false)?.apply()
            log("guidance source $SOURCE_CARPLAY_NAVI released ($reason) " +
                (result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" } ?: "result=${result.getOrNull()}"))
        }
        wanted = false
    }

    private fun onChange(action: Int) {
        log("guidance source $SOURCE_CARPLAY_NAVI change action=0x${Integer.toHexString(action)}")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runCatching { report("Audio: $message") }
    }

    companion object {
        private const val TAG = "DiPlay-GQ-NaviSource"
        const val SOURCE_CARPLAY_NAVI = 27
        const val ASR_REQUEST_GRANTED = 2
        const val RELEASE_MILLIS = 1_500L
        private const val RECHECK_SLACK_MILLIS = 50L
        private const val PREFS = "guangqi_navigation_source"
        private const val KEY_HELD = "held"
        private const val MANAGER_CLASS = "android.media.AudioSourceManager"
        private const val LISTENER_CLASS = "android.media.AudioSourceManager\$OnAudioSourceChangeListener"

        @Volatile private var shared: GuangqiNavigationSource? = null

        /** The process-wide instance; inert where the car's source manager is missing. */
        fun get(context: Context): GuangqiNavigationSource = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val api = reflectedApi(app)
                // No thread on head units without the source manager; nothing is ever posted there.
                val looper = if (api != null) HandlerThread("diplay-gq-navi-source").apply { start() }.looper
                    else Looper.getMainLooper()
                GuangqiNavigationSource(api, Handler(looper), prefs(app)).also { shared = it }
            }
        }

        /** Gives back a source a previous process held and never released. */
        fun recover(context: Context, log: (String) -> Unit) {
            val app = context.applicationContext
            val prefs = prefs(app)
            if (!prefs.getBoolean(KEY_HELD, false)) return
            val result = runCatching { reflectedApi(app)?.abandon(SOURCE_CARPLAY_NAVI) }
            prefs.edit().putBoolean(KEY_HELD, false).apply()
            log("Audio: guidance source $SOURCE_CARPLAY_NAVI released after an abandoned hold result=${result.getOrNull()}")
        }

        private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun reflectedApi(app: Context): CarAudioSourceApi? = runCatching {
            val managerClass = Class.forName(MANAGER_CLASS)
            val listenerClass = Class.forName(LISTENER_CLASS)
            val manager = managerClass.getConstructor(Context::class.java).newInstance(app)
            val request = managerClass.getMethod("requestAudioSource", listenerClass, Int::class.javaPrimitiveType)
            val abandon = managerClass.getMethod("abandonAudioSource", Int::class.javaPrimitiveType)
            object : CarAudioSourceApi {
                override fun request(sourceId: Int, onChange: (Int) -> Unit): Int {
                    val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                        when (method.name) {
                            "onAudioSourceChange" -> { (args?.getOrNull(1) as? Int)?.let(onChange); null }
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.firstOrNull()
                            "toString" -> "DiPlayGuidanceSource"
                            else -> null
                        }
                    }
                    return request.invoke(manager, listener, sourceId) as? Int ?: 0
                }

                override fun abandon(sourceId: Int): Int = abandon.invoke(manager, sourceId) as? Int ?: 0
            }
        }.getOrNull()
    }
}
