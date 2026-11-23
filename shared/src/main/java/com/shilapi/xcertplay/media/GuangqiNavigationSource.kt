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
 * Switching from media to navigation, the car's `DspService` (fade style NORMAL_MEDIA_NAVI) sets
 * the music stream's software volume to 9, switches the DSP, waits 50 ms and raises the DSP volume
 * from the media level to a fixed 20 (about +19 dB at media level 9). Music already mixed at full
 * software volume is still in the output buffer then, which is longer than 50 ms, so it plays at
 * the raised level for a moment: a loud burst at the start of each prompt. When music is playing,
 * the [SwitchGuard] therefore lowers DiPlay's own music by 20 dB for the switch: down first, the
 * request after one output latency, back up once the car's own duck is in place. The way back is a
 * six-step fade in `DspService` and needs no help.
 *
 * While navigation plays, `DspService` holds the music stream's software volume at a fixed 9 of 30
 * and the DSP at 20. Its comments and its low-volume branch treat 9/30 as 30 % (`max × level / 20`
 * keeps music at its level), but Android's volume curve makes 9/30 about −34 dB: at media level 9
 * music drops about 15 dB, and with the iPhone's own duck it is nearly inaudible. Once the car has
 * switched, [CarAudioSourceApi.keepMusicLevel] sets the music stream to the index chosen in
 * [GuangqiGuidanceMusic] (8-14, default 11). At media level 6 or below the car already keeps music
 * at its level and is left alone. The car's six-step fade back sets the stream to the maximum again.
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
        /** Sets the music stream to [index] during guidance; returns what it did, for the log. */
        fun keepMusicLevel(index: Int): String? = null
    }

    /** The audio sink's side of the switch: its music, and how long its output buffer is. */
    interface SwitchGuard {
        /** True when music has played in the last moment, so a switch would be heard. */
        fun mediaPlaying(): Boolean
        /** Lowers DiPlay's own music for the switch, or puts it back. */
        fun duckForSwitch(on: Boolean)
        /** How long a volume change takes to reach the speakers. */
        fun outputLatencyMillis(): Long
    }

    val available: Boolean get() = api != null

    @Volatile private var lastPcmNanos = 0L
    /** Set by the audio threads; a request or a release check is pending or running. */
    @Volatile private var wanted = false
    // Handler thread only below.
    private var held = false
    private var report: (String) -> Unit = {}
    private val check = Runnable { checkIdle() }
    /** The guard whose music is lowered for a switch in progress. */
    private var ducked: SwitchGuard? = null
    private var pendingRequest: Runnable? = null
    private var pendingRestore: Runnable? = null

    /** Called on every guidance PCM write. */
    fun onNavigationPcm(report: (String) -> Unit, guard: SwitchGuard? = null) {
        lastPcmNanos = nowNanos()
        if (api == null || wanted) return
        wanted = true
        handler.post { acquire(report, guard) }
    }

    /** The audio sink closed: give the source back now. */
    fun releaseNow() {
        if (api == null) return
        handler.post { release("audio closed") }
    }

    private fun acquire(report: (String) -> Unit, guard: SwitchGuard?) {
        this.report = report
        if (!held && pendingRequest == null) {
            val music = guard?.let { runCatching { it.mediaPlaying() }.getOrDefault(false) } == true
            if (guard != null && music) {
                val latency = runCatching { guard.outputLatencyMillis() }.getOrDefault(DEFAULT_LATENCY_MILLIS)
                    .coerceIn(MIN_LATENCY_MILLIS, MAX_LATENCY_MILLIS)
                guard.duckForSwitch(true)
                ducked = guard
                log("guidance source $SOURCE_CARPLAY_NAVI: music lowered for the switch, output latency ${latency}ms")
                val request = Runnable {
                    pendingRequest = null
                    request()
                    val restore = Runnable {
                        keepMusicLevel()
                        restoreMusic("switch done")
                    }
                    pendingRestore = restore
                    handler.postDelayed(restore, latency + DSP_SWITCH_MILLIS)
                }
                pendingRequest = request
                handler.postDelayed(request, latency + SWITCH_SLACK_MILLIS)
            } else {
                request()
                if (held) {
                    val keep = Runnable {
                        pendingRestore = null
                        keepMusicLevel()
                    }
                    pendingRestore = keep
                    handler.postDelayed(keep, DSP_SWITCH_MILLIS)
                }
            }
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, RELEASE_MILLIS)
    }

    private fun request() {
        val car = api ?: return
        val result = runCatching { car.request(SOURCE_CARPLAY_NAVI) { action -> onChange(action) } }
        val granted = result.getOrNull()?.let { it and ASR_REQUEST_GRANTED != 0 } == true
        held = granted
        if (granted) prefs?.edit()?.putBoolean(KEY_HELD, true)?.apply()
        log("guidance source $SOURCE_CARPLAY_NAVI requested: " +
            (result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" }
                ?: "result=0x${Integer.toHexString(result.getOrDefault(0))} held=$granted"))
    }

    /** After the car's switch: music at the chosen index instead of the car's 9. */
    private fun keepMusicLevel() {
        if (!held) return
        val index = prefs?.let(GuangqiGuidanceMusic::musicIndex) ?: GuangqiGuidanceMusic.DEFAULT_INDEX
        val result = runCatching { api?.keepMusicLevel(index) }
        val done = result.getOrNull() ?: result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" } ?: return
        log("guidance source $SOURCE_CARPLAY_NAVI: music stream $done")
    }

    private fun restoreMusic(reason: String) {
        pendingRestore?.let(handler::removeCallbacks)
        pendingRestore = null
        val guard = ducked ?: return
        ducked = null
        runCatching { guard.duckForSwitch(false) }
        log("guidance source $SOURCE_CARPLAY_NAVI: music restored ($reason)")
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
        pendingRequest?.let(handler::removeCallbacks)
        pendingRequest = null
        restoreMusic(reason)
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
        /** Used when the output latency cannot be read. */
        const val DEFAULT_LATENCY_MILLIS = 200L
        private const val MIN_LATENCY_MILLIS = 40L
        private const val MAX_LATENCY_MILLIS = 500L
        /** After the lowered music has reached the speakers, before the request. */
        const val SWITCH_SLACK_MILLIS = 30L
        /** After the request: the car's fade, its 50 ms wait and its own duck reaching the speakers. */
        const val DSP_SWITCH_MILLIS = 300L
        private const val PREFS = GuangqiGuidanceMusic.PREFS
        private const val KEY_HELD = "held"
        private const val MANAGER_CLASS = "android.media.AudioSourceManager"
        private const val LISTENER_CLASS = "android.media.AudioSourceManager\$OnAudioSourceChangeListener"

        @Volatile private var shared: GuangqiNavigationSource? = null

        /**
         * The music stream index to set during guidance, or null to leave the car's own value: at
         * media level 6 or below `DspService` already keeps music at its level (`max × level / 20`),
         * and a fixed index could make music louder during a prompt than before it.
         */
        fun musicIndexDuringGuidance(mediaLevel: Int, chosenIndex: Int, maxIndex: Int): Int? =
            if (mediaLevel <= CAR_LOW_VOLUME_LEVEL) null else chosenIndex.coerceIn(1, maxIndex)

        /** At or below this media level `DspService` scales music instead of using 9. */
        private const val CAR_LOW_VOLUME_LEVEL = 6

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
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val dsp = app.getSystemService("dsp")
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

                override fun keepMusicLevel(index: Int): String? {
                    val dspManager = dsp ?: return null
                    // DspService's own media level (policy 0) and its music index scale.
                    val level = dspManager.javaClass.getMethod("getVolumeByPolicy", Int::class.javaPrimitiveType)
                        .invoke(dspManager, 0) as Int
                    val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val target = musicIndexDuringGuidance(level, index, max)
                        ?: return "left to the car at media level $level (its low-volume rule keeps music level)"
                    // The call DspService itself uses: the mixer index only, not the volume setting.
                    audio.javaClass.getMethod("setVolumeByStreamType", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(audio, android.media.AudioManager.STREAM_MUSIC, target)
                    return "set to $target/$max for media level $level (car: ${GuangqiGuidanceMusic.CAR_INDEX})"
                }
            }
        }.getOrNull()
    }
}
