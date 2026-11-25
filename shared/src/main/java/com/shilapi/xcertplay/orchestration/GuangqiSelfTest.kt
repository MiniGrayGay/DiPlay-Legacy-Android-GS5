// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.WiredUdpUplink
import com.shilapi.xcertplay.media.GuangqiAssistantSource
import com.shilapi.xcertplay.media.GuangqiNavigationSource
import com.shilapi.xcertplay.media.StandingMicrophone
import com.shilapi.xcertplay.network.GuangqiVpnSubsystem
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The checks a driver would otherwise do by hand after installing a build on the Guangqi head unit:
 * microphone, stock assistant, volume hook, VPN, Siri over the radio. It drives the same classes a
 * CarPlay session uses. Steps that need a session, or need there to be none, say so and are skipped.
 * It switches the car to the radio, presses volume up and down once and, with no session, ends
 * whichever process holds the microphone. Guangqi only; a debugging tool, so Chinese only.
 */
class GuangqiSelfTest(context: Context, private val host: Host) {
    enum class Outcome { PASS, FAIL, UNSURE, SKIPPED }

    class Result(val name: String, val outcome: Outcome, val detail: String)

    interface Host {
        /** Presses Siri on the iPhone; null without a CarPlay session that is up. */
        fun siri(): (() -> Boolean)?
        /** A connection being made, which is not a session yet. */
        fun connecting(): Boolean
        /** The session is over the cable. */
        fun wired(): Boolean
        /** Pauses the iPhone's music. */
        fun pauseMusic()
        fun started(step: String)
        fun finished(result: Result)
        fun log(line: String)
        /** Null when the driver did not answer. */
        fun ask(question: String): Boolean?
        /** Brings the test's own screen back to the front. */
        fun show()
    }

    private val app = context.applicationContext
    @Volatile private var cancelled = false
    private val audio by lazy { GuangqiNavigationSource.reflectedApi(app) }

    fun cancel() {
        cancelled = true
    }

    /** Blocks until done or cancelled; on a worker thread. */
    fun run() {
        val listener: (String) -> Unit = { heard += it }
        tap = listener
        try {
            step("车机状态") { environment() }
            step("麦克风：占用并收音") { microphone() }
            step("麦克风：102 无效时结束占用者") { eviction() }
            step("原车语音：放开后恢复") { assistantBack() }
            step("音量钩子") { volumeHook() }
            step("有线 TUN：系统 VPN 授权") { tun() }
            step("有线：发往 iPhone 的通道") { wiredUplink() }
            step("提示音：当前音源") { tone() }
            step("Siri：当前音源") { siri(radio = false) }
            step("收音机：音源 25") { radio() }
            step("Siri：收音机") { siri(radio = true) }
        } catch (_: CancellationException) {
            host.log("已停止")
        } finally {
            if (tap === listener) tap = null
            host.show()
        }
    }

    private fun step(name: String, body: () -> Result0) {
        pause(0)
        host.started(name)
        host.log("== $name")
        val result = try {
            body()
        } catch (stop: CancellationException) {
            throw stop
        } catch (error: Throwable) {
            Result0(Outcome.FAIL, "出错：${error.cause ?: error}")
        }
        host.log("   ${result.outcome} ${result.detail}")
        host.finished(Result(name, result.outcome, result.detail))
    }

    private class Result0(val outcome: Outcome, val detail: String)

    private fun pass(detail: String) = Result0(Outcome.PASS, detail)
    private fun fail(detail: String) = Result0(Outcome.FAIL, detail)
    private fun unsure(detail: String) = Result0(Outcome.UNSURE, detail)
    private fun skip(detail: String) = Result0(Outcome.SKIPPED, detail)

    // ---- steps

    private fun environment(): Result0 {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val permission = app.checkPermission(Manifest.permission.RECORD_AUDIO, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
        val detail = "DiPlay ${info.versionName}，uid ${android.os.Process.myUid()}，DSP 策略 ${policy()}，" +
            "CarPlay ${if (host.siri() != null) "已连接" else if (host.connecting()) "正在连接" else "未连接"}，常开麦克风 ${onOff(GuangqiStandingMicrophone.enabled(app))}，" +
            "正在录音 ${holders().ifEmpty { listOf("无") }.joinToString()}"
        return when {
            audio == null -> fail("$detail；找不到广汽音源管理器")
            !permission -> fail("$detail；没有麦克风权限")
            host.siri() == null && host.connecting() -> unsure("$detail；正在连接，等 CarPlay 画面出来再跑，或回首页断开后再跑")
            else -> pass(detail)
        }
    }

    private fun microphone(): Result0 = openMicrophone().use { mic ->
        val began = System.nanoTime()
        val first = mic.record(100, FIRST_AUDIO_MILLIS)
        if (first.isEmpty()) return fail("${FIRST_AUDIO_MILLIS / 1000} 秒内没有录到；占用者 ${holders()}")
        val waited = (System.nanoTime() - began) / 1_000_000
        val processor = Cpu()
        val heard = mic.record(CPU_MILLIS)
        val detail = "${if (mic.shared) "CarPlay 会话的常开麦克风" else "${waited} ms 拿到"}，" +
            "音量 ${db(SelfTestTone.levelDb(heard))}FS，CPU ${processor.report()}"
        when {
            heard.size < StandingMicrophone.SAMPLE_RATE * 2 * CPU_MILLIS / 1_000 / 2 -> fail("$detail；数据断续，只有 ${heard.size} 字节")
            heard.all { it.toInt() == 0 } -> fail("$detail；全是静音")
            else -> pass(detail)
        }
    }

    private fun eviction(): Result0 {
        if (host.siri() != null) return skip("断开 CarPlay 后再跑才能测")
        if (!waitFor(HOLDER_BACK_MILLIS) { holders().isNotEmpty() }) return skip("没有程序在占用麦克风（原车唤醒词没开？）")
        val before = holders()
        val notes = CopyOnWriteArrayList<String>()
        // No makeRoom: the holder is not asked, so only ending it can free the microphone.
        return Microphone(StandingMicrophone(
            evict = { GuangqiMicrophoneHolder.evict(app) { notes += it; host.log("   $it") } },
            log = { host.log("   $it") },
        ).start(), shared = false) {}.use { mic ->
            val began = System.nanoTime()
            val got = mic.record(200, EVICTION_MILLIS).isNotEmpty()
            val waited = (System.nanoTime() - began) / 1_000_000
            val detail = "占用者 $before，结束 ${notes.size} 次"
            when {
                got && notes.isNotEmpty() -> pass("$detail，${waited} ms 后拿到")
                got -> unsure("$detail，${waited} ms 就拿到了，占用者自己放开了")
                else -> fail("$detail，${EVICTION_MILLIS / 1000} 秒还拿不到；现在 ${holders()}")
            }
        }
    }

    private fun assistantBack(): Result0 {
        if (host.siri() != null) return skip("CarPlay 连着，原车语音本来就停着")
        val began = System.nanoTime()
        return if (waitFor(HOLDER_BACK_MILLIS) { holders().any { ASSISTANT in it } }) {
            pass("${(System.nanoTime() - began) / 1_000_000} ms 后重新开始监听")
        } else {
            unsure("${HOLDER_BACK_MILLIS / 1000} 秒内没有重新监听；请喊一声唤醒词确认")
        }
    }

    private fun volumeHook(): Result0 {
        if (!GuangqiVolumeTakeover.supported()) return skip("不是广汽车机")
        val states = LinkedBlockingQueue<Intent>()
        val keys = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == GuangqiVolumeHook.ACTION_HOOK_STATE) states += intent else keys += intent
            }
        }
        app.registerReceiver(receiver, IntentFilter(GuangqiVolumeHook.ACTION_HOOK_STATE).apply {
            addAction(GuangqiVolumeHook.ACTION_CAR_VOLUME)
        })
        try {
            if (!GuangqiVolumeTakeover.enabled(app)) return skip("「音量键由 DiPlay 接管」没开")
            GuangqiVolumeTakeover.start(app)
            val state = states.poll(STATE_MILLIS, TimeUnit.MILLISECONDS) ?: return fail("system 进程没有回应")
            val version = state.getIntExtra(GuangqiVolumeHook.EXTRA_VERSION, 0)
            if (!state.getBooleanExtra(GuangqiVolumeHook.EXTRA_INSTALLED, false)) {
                return fail("没装上：${state.getStringExtra(GuangqiVolumeHook.EXTRA_DETAIL)}")
            }
            if (version != GuangqiVolumeHook.VERSION) return fail("装着 v$version，需要 v${GuangqiVolumeHook.VERSION}：重启车机")
            val before = mediaVolume()
            // Back to where it was afterwards; never above the top.
            val order = if (before != null && before >= GuangqiVolumeHook.MAX_VOLUME) listOf(KEY_DOWN, KEY_UP) else listOf(KEY_UP, KEY_DOWN)
            val taken = order.map { key ->
                keys.clear()
                shell("input", "keyevent", key)
                keys.poll(STATE_MILLIS, TimeUnit.MILLISECONDS)
            }
            val after = mediaVolume()
            val text = taken.joinToString { key ->
                key?.let { "${kind(it)} ${it.getIntExtra(GuangqiVolumeHook.EXTRA_VALUE, -1)}" } ?: "没反应"
            }
            val detail = "v$version；按两下：$text；媒体音量 $before → $after"
            return when {
                taken.any { it == null } -> fail(detail)
                taken.any { it!!.getIntExtra(GuangqiVolumeHook.EXTRA_KIND, 0) == GuangqiVolumeHook.KIND_PASS } ->
                    unsure("$detail（交回原车：${taken.mapNotNull { it?.getStringExtra(GuangqiVolumeHook.EXTRA_REASON) }}）")
                before != after -> fail("$detail；没有回到原来的音量")
                else -> pass(detail)
            }
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    private fun kind(intent: Intent) = when (intent.getIntExtra(GuangqiVolumeHook.EXTRA_KIND, 0)) {
        GuangqiVolumeHook.KIND_MEDIA -> "媒体"
        GuangqiVolumeHook.KIND_NAVI -> "导航"
        else -> "原车"
    }

    /** Only the grant: a tunnel set up here could not be told apart from a session's own. */
    private fun tun(): Result0 {
        if (!GuangqiVpnSubsystem.applies()) return skip("不是广汽车机，或不是 system uid")
        if (host.siri() != null) return skip("断开 CarPlay 后再跑才能测")
        val notes = mutableListOf<String>()
        val granted = GuangqiVpnSubsystem.prepare(app) { notes += it; host.log("   $it") }
        val detail = notes.lastOrNull().orEmpty()
        return if (granted) pass("$detail。真正连接要把「有线网络」选成 TUN 再插线") else fail(detail)
    }

    private fun wiredUplink(): Result0 = when {
        host.siri() == null -> skip("没有 CarPlay 会话")
        !host.wired() -> skip("无线连接")
        WiredUdpUplink.current() != null -> pass("有线，走内置网络栈（lwIP）；能不能发出去看 Siri 那一步")
        else -> pass("有线，走内核网络（TUN）；能不能发出去看 Siri 那一步")
    }

    private fun tone(): Result0 {
        val policy = policy()
        if (policy in RADIO_POLICIES) return skip("车机正在放收音机或蓝牙音乐（策略 $policy），看下面收音机那一步")
        val rise = openMicrophone().use(::playAndListen)
        return heardOrAsk(rise, "策略 $policy", "刚才有没有听到一声「嘟」？")
    }

    private fun radio(): Result0 {
        val car = audio ?: return skip("找不到广汽音源管理器")
        if (host.siri() != null) {
            // Music starting again takes the car's source back from the radio.
            host.pauseMusic()
            pause(AFTER_SIRI_MILLIS)
        }
        if (policy() !in RADIO_POLICIES) {
            val launch = app.packageManager.getLaunchIntentForPackage(RADIO_PACKAGE) ?: return skip("没有收音机应用")
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val switched = waitFor(RADIO_MILLIS) { policy() in RADIO_POLICIES }
            host.show()
            if (!switched) return skip("没切到收音机（策略 ${policy()}）；请手动打开收音机再跑一次")
        }
        pause(SETTLE_MILLIS)
        val before = policy()
        if (before !in RADIO_POLICIES) return unsure("切到收音机后又被抢走（策略 $before）；暂停音乐、打开收音机再跑一次")
        val source = GuangqiAssistantSource.get(app)
        val token = Any()
        return openMicrophone().use { mic ->
            val without = playAndListen(mic)
            source.started(token) { host.log("   $it") }
            val during: Int?
            val with: Double
            try {
                pause(SWITCH_MILLIS)
                during = policy()
                with = playAndListen(mic)
            } finally {
                source.ended(token)
            }
            waitFor(RESTORE_MILLIS) { policy() == before }
            val after = policy()
            val detail = "策略 $before → $during → $after；不申请时 ${db(without)}，申请后 ${db(with)}"
            when {
                car.dspPolicy() == null -> fail("$detail；读不到 DSP 策略")
                during != VOICE_POLICY -> fail("$detail；没切到语音策略 $VOICE_POLICY")
                after != before -> fail("$detail；收音机没有恢复")
                else -> heardOrAsk(with, detail, "收音机刚才停了一下：停的时候有没有听到一声「嘟」？")
            }
        }
    }

    private fun siri(radio: Boolean): Result0 {
        val press = host.siri() ?: return skip("没有 CarPlay 会话")
        val before = policy()
        if (radio && before !in RADIO_POLICIES) return skip("车机不在收音机（策略 $before）")
        heard.clear()
        if (!press()) return fail("没能唤起 Siri")
        val during = LinkedHashSet<Int?>()
        waitFor(SIRI_MILLIS) {
            policy().let { if (it != before) during += it }
            SelfTestSiri(heard.toList()).ended
        }
        if (before in RADIO_POLICIES) waitFor(RESTORE_MILLIS) { policy() == before }
        val after = policy()
        val siri = SelfTestSiri(heard.toList())
        val detail = "$siri；策略 $before" + (if (during.isEmpty()) "" else " → ${during.joinToString(" → ")}") + " → $after"
        return when {
            siri.sent == 0L && !siri.ended -> fail("$detail；${SIRI_MILLIS / 1000} 秒内没有 Siri 的麦克风")
            !siri.microphoneWorks -> fail("$detail；麦克风不正常")
            before in RADIO_POLICIES && !siri.sourceHeld ->
                fail("$detail；音源 25 ${if (siri.sourceRefused) "被拒" else "没有申请"}")
            before in RADIO_POLICIES && after != before -> fail("$detail；收音机没有恢复")
            siri.broken -> fail("$detail；麦克风中断")
            !siri.spoken -> unsure("$detail；没听到说话，Siri 弹出时请说「现在几点」")
            !siri.downlink -> unsure(detail)
            else -> pass(detail)
        }
    }

    // ---- tools

    /** One source of 48 kHz mono audio for a step: the running session's recorder, or its own. */
    private inner class Microphone(
        private val standing: StandingMicrophone,
        val shared: Boolean,
        private val released: () -> Unit,
    ) : Closeable {
        private val reader = standing.subscribe()

        /** [millis] of audio, or what came within [limit]; empty when nothing did. */
        fun record(millis: Int, limit: Int = millis * 3): ByteArray {
            val out = ByteArrayOutputStream()
            val wanted = StandingMicrophone.SAMPLE_RATE * 2 / 1_000 * millis
            val buffer = ByteArray(StandingMicrophone.CHUNK_BYTES)
            val deadline = System.nanoTime() + limit * 1_000_000L
            while (out.size() < wanted && System.nanoTime() < deadline) {
                if (cancelled) throw CancellationException()
                val count = reader?.read(buffer) ?: return ByteArray(0)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }

        override fun close() {
            reader?.close()
            if (!shared) standing.close()
            released()
        }
    }

    private fun openMicrophone(): Microphone {
        GuangqiStandingMicrophone.current()?.let { return Microphone(it, shared = true) {} }
        GuangqiStockAssistant.acquire(app, REASON)
        return Microphone(StandingMicrophone(
            makeRoom = { GuangqiStockAssistant.reassert(app) },
            evict = { GuangqiMicrophoneHolder.evict(app) { host.log("   $it") } },
            log = { host.log("   $it") },
        ).start(), shared = false) { GuangqiStockAssistant.release(app, REASON) }
    }

    /** Plays the tone as Siri's stream is played; how much of it the microphone heard, in dB. */
    private fun playAndListen(mic: Microphone): Double {
        mic.record(QUIET_MILLIS / 2, FIRST_AUDIO_MILLIS)
        val before = mic.record(QUIET_MILLIS)
        val rate = StandingMicrophone.SAMPLE_RATE
        val pcm = SelfTestTone.pcm(rate, TONE_MILLIS)
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_VOICE_CALL, rate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM)
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val during = mic.record(TONE_MILLIS + TONE_TAIL_MILLIS)
            return SelfTestTone.riseDb(before, during, rate).also { host.log("   提示音 ${db(it)}") }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** The car's echo cancelling may take the tone out of the microphone: then the driver decides. */
    private fun heardOrAsk(rise: Double, detail: String, question: String): Result0 {
        if (rise >= SelfTestTone.HEARD_DB) return pass("$detail；麦克风听到了提示音（${db(rise)}）")
        return when (host.ask(question)) {
            true -> pass("$detail；你听到了提示音（麦克风 ${db(rise)}）")
            false -> fail("$detail；没有声音（麦克风 ${db(rise)}）")
            null -> unsure("$detail；麦克风没听到（${db(rise)}），你没有回答")
        }
    }

    private fun policy(): Int? = runCatching { audio?.dspPolicy() }.getOrNull()

    private fun mediaVolume(): Int? = runCatching {
        val dsp = app.getSystemService("dsp") ?: return null
        dsp.javaClass.getMethod("getVolumeByPolicy", Int::class.javaPrimitiveType).invoke(dsp, 0) as Int
    }.getOrNull()

    private fun holders(): List<String> = GuangqiMicrophoneHolder.holders()
        .map { (pid, process) -> "$process($pid)" }

    private fun shell(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (output.isNotEmpty()) host.log("   ${command.joinToString(" ")}: $output")
    }

    private fun waitFor(millis: Int, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (true) {
            if (condition()) return true
            if (System.nanoTime() >= deadline) return false
            pause(POLL_MILLIS)
        }
    }

    private fun pause(millis: Int) {
        val deadline = System.nanoTime() + millis * 1_000_000L
        do {
            if (cancelled) throw CancellationException()
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left > 0) Thread.sleep(minOf(left, POLL_MILLIS.toLong()))
        } while (System.nanoTime() < deadline)
    }

    private fun onOff(on: Boolean) = if (on) "开" else "关"
    private fun db(value: Double) = if (value.isNaN()) "无数据" else String.format(Locale.US, "%.0f dB", value)

    /** CPU time of mediaserver and of DiPlay since it was made, as a share of all cores. */
    private class Cpu {
        private val media = File("/proc").listFiles().orEmpty().firstOrNull {
            it.name.all(Char::isDigit) && runCatching { File(it, "cmdline").readText() }.getOrDefault("")
                .substringBefore('\u0000') == "/system/bin/mediaserver"
        }?.name
        private val total = all()
        private val before = listOf(media, "self").map(::ticks)

        fun report(): String {
            val spent = (all() - total).coerceAtLeast(1)
            val (server, own) = listOf(media, "self").map(::ticks).zip(before)
                .map { (now, then) -> if (now < 0 || then < 0) "?" else "${(now - then) * 100 / spent}%" }
            return "mediaserver $server、DiPlay $own"
        }

        private fun all(): Long = runCatching {
            File("/proc/stat").useLines { it.first() }.trim().split(Regex("\\s+")).drop(1).sumOf(String::toLong)
        }.getOrDefault(0L)

        private fun ticks(pid: String?): Long = runCatching {
            val fields = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
            fields[11].toLong() + fields[12].toLong()
        }.getOrDefault(-1L)
    }

    companion object {
        /** DiPlay's log lines while a test runs; set by it, fed by the app's own log. */
        @Volatile private var tap: ((String) -> Unit)? = null
        private val heard = CopyOnWriteArrayList<String>()

        fun onLogLine(line: String) {
            tap?.invoke(line)
        }

        /** On head units this test knows. */
        fun supported(): Boolean = GuangqiStandingMicrophone.supported()

        private const val REASON = "self test"
        private const val ASSISTANT = "wecarspeech"
        private const val RADIO_PACKAGE = "gaei.radio"
        private val RADIO_POLICIES = setOf<Int?>(2, 4)
        private const val VOICE_POLICY = 3
        private const val KEY_UP = "24"
        private const val KEY_DOWN = "25"
        private const val FIRST_AUDIO_MILLIS = 8_000
        private const val CPU_MILLIS = 4_000
        private const val EVICTION_MILLIS = 8_000
        private const val HOLDER_BACK_MILLIS = 15_000
        private const val STATE_MILLIS = 4_000L
        private const val RADIO_MILLIS = 10_000
        private const val SETTLE_MILLIS = 2_000
        private const val AFTER_SIRI_MILLIS = 3_000
        private const val SWITCH_MILLIS = 1_500
        private const val RESTORE_MILLIS = 5_000
        private const val SIRI_MILLIS = 25_000
        private const val QUIET_MILLIS = 1_000
        private const val TONE_MILLIS = 1_200
        private const val TONE_TAIL_MILLIS = 800
        private const val POLL_MILLIS = 250
    }
}
