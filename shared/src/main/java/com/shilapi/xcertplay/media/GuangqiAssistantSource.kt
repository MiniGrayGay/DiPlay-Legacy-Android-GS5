package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

/**
 * Guangqi head unit: Siri and CarPlay calls heard over the radio and over Bluetooth music.
 *
 * The DSP plays one input per policy. Music from any Android app is policy 0 (the head unit's own
 * output); the radio is 2 and Bluetooth music 4, and with those the DSP takes the tuner or the
 * Bluetooth module and Android's output is not heard at all. CarPlay music gets the DSP back through
 * audio focus and guidance through source 27 ([GuangqiNavigationSource]), but Siri and
 * calls asked for nothing, so over the radio they listened and answered in silence.
 *
 * While a Siri or call stream is open and the policy is one of those two, this holds `[CarLife_VR]` 25 of
 * `/system/etc/sources_conf.xml`: DSP policy 3, which is the car's own voice assistant's, at the
 * voice volume. The arbitration table pauses the radio and Bluetooth music for it, and abandoning it
 * starts them again. The service checks the table, not the package, as with 27. Not
 * `[CarPlay_PHONE]` 28: its policy 5 is the DSP's Bluetooth call input. With any other policy
 * Android is heard already and nothing is asked for, so Siri over CarPlay music is as before.
 *
 * The policy is read again every [CHECK_MILLIS]: guidance over the radio is audible policy 1 until
 * the prompt ends. A process that dies holding the source gives it back at the next [recover].
 */
internal class GuangqiAssistantSource(
    private val api: GuangqiNavigationSource.CarAudioSourceApi?,
    private val handler: Handler,
    private val prefs: android.content.SharedPreferences?,
) {
    val available: Boolean get() = api != null

    // Handler thread only.
    private val streams = LinkedHashSet<Any>()
    private var held = false
    private var refusals = 0
    private var lastPolicy: Int? = null
    private var report: (String) -> Unit = {}
    private var kind = "Siri"
    private val check = Runnable { check() }

    /** A stream of [kind], Siri or a call, opened; [stream] identifies it. */
    fun started(stream: Any, kind: String, report: (String) -> Unit) {
        if (api == null) return
        handler.post {
            this.report = report
            if (!streams.add(stream)) return@post
            this.kind = kind
            refusals = 0
            handler.removeCallbacks(check)
            check()
        }
    }

    fun ended(stream: Any) {
        if (api == null) return
        handler.post { if (streams.remove(stream) && streams.isEmpty()) release("$kind ended") }
    }

    /** The audio sink with these [streams] closed. */
    fun endedAll(streams: Collection<Any>) = streams.forEach(::ended)

    private fun check() {
        val car = api ?: return
        if (streams.isEmpty() || held) return
        val policy = runCatching { car.dspPolicy() }.getOrNull()
        if (policy in DEAF_POLICIES) {
            val result = runCatching { car.request(SOURCE_CARLIFE_VR) { action -> onChange(action) } }
            held = result.getOrNull()?.let { it and GuangqiNavigationSource.ASR_REQUEST_GRANTED != 0 } == true
            if (held) prefs?.edit()?.putBoolean(KEY_HELD, true)?.apply()
            log("$kind source $SOURCE_CARLIFE_VR requested over DSP policy $policy: " +
                (result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" }
                    ?: "result=0x${Integer.toHexString(result.getOrDefault(0))} held=$held"))
            if (!held && ++refusals >= MAX_REFUSALS) return
        } else if (refusals == 0 && policy != lastPolicy) {
            log("$kind source: not needed, DSP policy $policy")
        }
        lastPolicy = policy
        handler.postDelayed(check, CHECK_MILLIS)
    }

    private fun release(reason: String) {
        handler.removeCallbacks(check)
        lastPolicy = null
        if (!held) return
        held = false
        val result = runCatching { api?.abandon(SOURCE_CARLIFE_VR) }
        prefs?.edit()?.putBoolean(KEY_HELD, false)?.apply()
        log("$kind source $SOURCE_CARLIFE_VR released ($reason) " +
            (result.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" } ?: "result=${result.getOrNull()}"))
    }

    private fun onChange(action: Int) {
        // Something stronger, such as a call: the car gives the DSP back itself. Kept until the stream ends.
        log("$kind source $SOURCE_CARLIFE_VR change action=0x${Integer.toHexString(action)}")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runCatching { report("Audio: $message") }
    }

    companion object {
        private const val TAG = "DiPlay-GQ-SiriSource"
        const val SOURCE_CARLIFE_VR = 25
        /** Radio and Bluetooth music: the DSP does not play Android's output. */
        private val DEAF_POLICIES = setOf(2, 4)
        const val CHECK_MILLIS = 1_000L
        private const val MAX_REFUSALS = 3
        private const val PREFS = "guangqi_assistant_source"
        private const val KEY_HELD = "held"

        @Volatile private var shared: GuangqiAssistantSource? = null

        /** The process-wide instance; inert where the car's source manager is missing. */
        fun get(context: Context): GuangqiAssistantSource = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val api = GuangqiNavigationSource.reflectedApi(app)
                val looper = if (api != null) HandlerThread("diplay-gq-siri-source").apply { start() }.looper
                    else Looper.getMainLooper()
                GuangqiAssistantSource(api, Handler(looper), prefs(app)).also { shared = it }
            }
        }

        /** Gives back a source a previous process held and never released. */
        fun recover(context: Context, log: (String) -> Unit) {
            val app = context.applicationContext
            val prefs = prefs(app)
            if (!prefs.getBoolean(KEY_HELD, false)) return
            val result = runCatching { GuangqiNavigationSource.reflectedApi(app)?.abandon(SOURCE_CARLIFE_VR) }
            prefs.edit().putBoolean(KEY_HELD, false).apply()
            log("Audio: Siri source $SOURCE_CARLIFE_VR released after an abandoned hold result=${result.getOrNull()}")
        }

        private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
