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
 * While navigation plays, `DspService` holds the DSP at a fixed 20 (−10 dB) and the music stream's
 * software volume at a fixed 9 of 30, whatever the media level, so music during a prompt has one
 * loudness for every media level: barely lowered at media 7-8, much lowered at 14 and above. Once the
 * car has switched, [CarAudioSourceApi.keepMusicLevel] sets the music stream so music is the chosen
 * number of dB below its level before the prompt ([GuangqiGuidanceMusic], default 6 dB, which is
 * 12/30 at media level 10, the level the driver tuned by ear). [musicIndexForDuck] does the sum with
 * `DspService`'s DSP volume table and the speaker music curve of this ROM's
 * `audio_policy.default.so` (AOSP 4.4, unchanged). The car's six-step fade back sets the stream to
 * the maximum again.
 *
 * A prompt is held back until the car has switched ([awaitGate], at most [GATE_TIMEOUT_MILLIS]):
 * before that the car plays guidance in its media mode, about 17 dB quieter at media 10 and guidance
 * 15, and the first words were hard to hear. The iPhone's packets wait in the renderer's queue
 * meanwhile, so the prompt plays whole, about 0.7 s late with music playing and 0.15 s without.
 *
 * The source is held from the first audible guidance PCM write until the last audible one has played:
 * the guidance track's own output latency, less [EARLY_MILLIS] for the car's handling of the release.
 * It must not be earlier: switching back, `DspService` first sets the guidance stream to 0 and turns
 * the DSP's guidance channel off, so guidance still in the track would be cut, not just mixed with
 * louder music. The car's own fade then brings music back. Silent frames (below about −40 dBFS)
 * neither start nor extend the hold, so the iPhone keeping the stream open or a silent tail does not
 * keep music down; pauses inside a prompt shorter than the hold do not switch the car back.
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
        /** Sets the music stream [duckDb] below the media level during guidance; returns what it did. */
        fun keepMusicLevel(duckDb: Int): String? = null
        /** The DSP policy now in force, null when it cannot be read. */
        fun dspPolicy(): Int? = null
    }

    /** The audio sink's side of the switch: its music, and how long its output buffer is. */
    interface SwitchGuard {
        /** True when music has played in the last moment, so a switch would be heard. */
        fun mediaPlaying(): Boolean
        /** Lowers DiPlay's own music for the switch, or puts it back. */
        fun duckForSwitch(on: Boolean)
        /** How long a volume change takes to reach the speakers. */
        fun outputLatencyMillis(): Long
        /** How long guidance written now takes to be heard. */
        fun navigationLatencyMillis(): Long = DEFAULT_GUIDANCE_LATENCY_MILLIS
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
    /** How long after the last audible write the source is given back. */
    private var releaseMillis = DEFAULT_RELEASE_MILLIS
    /** Closed from a prompt's first audible write until the car has switched; guidance waits. */
    @Volatile private var gateOpen = true
    private val gateLock = Object()

    /** True while guidance may play. */
    val guidanceGateOpen: Boolean get() = gateOpen

    /**
     * Blocks the guidance writer until the car has switched to its guidance mode, at most
     * [timeoutMillis]; returns whether it has. Called before each guidance write.
     */
    fun awaitGate(timeoutMillis: Long): Boolean {
        if (gateOpen) return true
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(gateLock) {
            while (!gateOpen) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                gateLock.wait(left)
            }
        }
        return gateOpen
    }

    private fun openGate(reason: String) {
        if (gateOpen) return
        synchronized(gateLock) {
            gateOpen = true
            gateLock.notifyAll()
        }
        // The hold counts from the guidance that now plays, not from before the wait.
        lastPcmNanos = nowNanos()
        log("guidance source $SOURCE_CARPLAY_NAVI: guidance plays ($reason)")
    }

    /** Called on every guidance PCM write; [audible] is false for silence. */
    fun onNavigationPcm(report: (String) -> Unit, guard: SwitchGuard? = null, audible: Boolean = true) {
        if (!audible) return
        lastPcmNanos = nowNanos()
        if (api == null || wanted) return
        wanted = true
        gateOpen = false
        handler.post { acquire(report, guard) }
    }

    /** The audio sink closed: give the source back now. */
    fun releaseNow() {
        if (api == null) return
        handler.post { release("audio closed") }
    }

    private fun acquire(report: (String) -> Unit, guard: SwitchGuard?) {
        this.report = report
        releaseMillis = guard?.let { runCatching { it.navigationLatencyMillis() }.getOrNull() }
            ?.let { (it - EARLY_MILLIS).coerceIn(MIN_RELEASE_MILLIS, MAX_RELEASE_MILLIS) }
            ?: DEFAULT_RELEASE_MILLIS
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
        handler.postDelayed(check, releaseMillis)
    }

    private fun request() {
        val car = api ?: return
        val result = runCatching { car.request(SOURCE_CARPLAY_NAVI) { action -> onChange(action) } }
        val granted = result.getOrNull()?.let { it and ASR_REQUEST_GRANTED != 0 } == true
        held = granted
        if (granted) prefs?.edit()?.putBoolean(KEY_HELD, true)?.apply()
        // Refused or failed: play at once rather than keep the prompt back.
        if (granted) handler.postDelayed({ openGate("car switched") }, GATE_SETTLE_MILLIS)
        else openGate("request refused")
        log("guidance source $SOURCE_CARPLAY_NAVI requested: " +
            (result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" }
                ?: "result=0x${Integer.toHexString(result.getOrDefault(0))} held=$granted"))
    }

    /** After the car's switch: music the chosen dB below its level instead of the car's fixed 9. */
    private fun keepMusicLevel() {
        if (!held) return
        val duckDb = prefs?.let(GuangqiGuidanceMusic::duckDb) ?: GuangqiGuidanceMusic.DEFAULT_DUCK_DB
        val result = runCatching { api?.keepMusicLevel(duckDb) }
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
        if (!gateOpen) {
            // The prompt is still waiting for the switch; nothing has played yet.
            handler.postDelayed(check, releaseMillis)
            return
        }
        val idleMillis = (nowNanos() - lastPcmNanos) / 1_000_000L
        if (idleMillis < releaseMillis) {
            handler.postDelayed(check, releaseMillis - idleMillis + RECHECK_SLACK_MILLIS)
        } else {
            release("${idleMillis}ms after the last audible guidance, hold ${releaseMillis}ms")
        }
    }

    private fun release(reason: String) {
        handler.removeCallbacks(check)
        pendingRequest?.let(handler::removeCallbacks)
        pendingRequest = null
        openGate(reason)
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
        /** Guidance track latency when it cannot be read: a 512 ms track buffer plus the mixer. */
        const val DEFAULT_GUIDANCE_LATENCY_MILLIS = 550L
        /** After the request, before guidance plays: the car's switch and its guidance channel opening. */
        const val GATE_SETTLE_MILLIS = 150L
        /** The longest a prompt waits for the switch; it plays anyway after that. */
        const val GATE_TIMEOUT_MILLIS = 1_200L
        /** Released this much before the last audible guidance has played, for the car's handling. */
        const val EARLY_MILLIS = 50L
        const val DEFAULT_RELEASE_MILLIS = DEFAULT_GUIDANCE_LATENCY_MILLIS - EARLY_MILLIS
        private const val MIN_RELEASE_MILLIS = 250L
        private const val MAX_RELEASE_MILLIS = 1_500L
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

        /** `DspService.DSP_VOLUME_TABLE`: dB for DSP volume steps 0-30. */
        private val DSP_VOLUME_DB = intArrayOf(
            -100, -70, -55, -45, -40, -37, -35, -33, -31, -29, -27, -25, -23, -21, -19, -17,
            -15, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0,
        )

        /** The DSP volume `DspService` uses while navigation plays. */
        private const val DSP_GUIDANCE_LEVEL = 20

        /**
         * `sSpeakerMediaVolumeCurve` of AOSP 4.4 (volume position 0-100 to dB). The ROM's
         * `audio_policy.default.so` carries the unchanged AOSP curves, and `audio_policy.conf` makes
         * the speaker the output, so the music stream follows this one.
         */
        private val SPEAKER_MUSIC_CURVE = intArrayOf(1, 20, 60, 100) to floatArrayOf(-56f, -34f, -11f, 0f)

        /** dB of music stream index [index] of [maxIndex], as `AudioPolicyManagerBase.volIndexToAmpl`. */
        fun speakerMusicDb(index: Int, maxIndex: Int): Float {
            val (points, db) = SPEAKER_MUSIC_CURVE
            val position = (1 + points.last() - points.first()) * index / maxIndex
            if (position < points.first()) return Float.NEGATIVE_INFINITY
            val segment = when {
                position < points[1] -> 0
                position < points[2] -> 1
                else -> 2
            }
            return db[segment] + (position - points[segment]) * (db[segment + 1] - db[segment]) /
                (points[segment + 1] - points[segment])
        }

        /**
         * The music stream index that puts music [duckDb] below its level before the prompt, at the
         * DSP's guidance volume: the largest index that is at least that far down. Above about media
         * level 21 even the full stream is lower than asked, because the DSP itself drops to 20.
         */
        fun musicIndexForDuck(mediaLevel: Int, duckDb: Int, maxIndex: Int): Int {
            val before = DSP_VOLUME_DB[mediaLevel.coerceIn(0, DSP_VOLUME_DB.lastIndex)]
            val wanted = before - duckDb - DSP_VOLUME_DB[DSP_GUIDANCE_LEVEL]
            var best = 1
            for (index in 1..maxIndex) if (speakerMusicDb(index, maxIndex) <= wanted + ROUNDING_DB) best = index
            return best
        }

        /** How far below its level before the prompt music plays at [index], in dB. */
        fun musicDuckDb(mediaLevel: Int, index: Int, maxIndex: Int): Float =
            DSP_VOLUME_DB[mediaLevel.coerceIn(0, DSP_VOLUME_DB.lastIndex)] -
                (DSP_VOLUME_DB[DSP_GUIDANCE_LEVEL] + speakerMusicDb(index, maxIndex))

        /** Lets an index land within half a decibel louder than asked rather than a step quieter. */
        private const val ROUNDING_DB = 0.5f

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

        internal fun reflectedApi(app: Context): CarAudioSourceApi? = runCatching {
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

                override fun dspPolicy(): Int? = dsp?.let {
                    it.javaClass.getMethod("getAudioStrategy").invoke(it) as? Int
                }

                override fun keepMusicLevel(duckDb: Int): String? {
                    val dspManager = dsp ?: return null
                    // DspService's own media level (policy 0) and its music index scale.
                    val level = dspManager.javaClass.getMethod("getVolumeByPolicy", Int::class.javaPrimitiveType)
                        .invoke(dspManager, 0) as Int
                    val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val target = musicIndexForDuck(level, duckDb, max)
                    // The call DspService itself uses: the mixer index only, not the volume setting.
                    audio.javaClass.getMethod("setVolumeByStreamType", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(audio, android.media.AudioManager.STREAM_MUSIC, target)
                    return "set to $target/$max for media level $level, ${duckDb} dB below " +
                        "(${"%.1f".format(musicDuckDb(level, target, max))} dB; car: ${GuangqiGuidanceMusic.CAR_INDEX})"
                }
            }
        }.getOrNull()
    }
}
