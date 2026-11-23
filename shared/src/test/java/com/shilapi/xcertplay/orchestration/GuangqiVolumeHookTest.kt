package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GuangqiVolumeHookTest {
    private val pass = GuangqiVolumeHook.KIND_PASS
    private val media = GuangqiVolumeHook.KIND_MEDIA
    private val navi = GuangqiVolumeHook.KIND_NAVI

    private fun decide(
        on: Boolean = true, alive: Boolean = true, rvc: Int = 0, naviState: Int = 0, policy: Int = 0,
        now: Long = 1_000, until: Long = 0,
    ) = GuangqiVolumeHook.decide(on, alive, rvc, naviState, policy, now, until)

    @Test fun mediaKeysAreTakenInEveryMediaPolicy() {
        for (policy in listOf(0, 2, 4)) assertEquals("policy $policy", media, decide(policy = policy))
    }

    @Test fun duringGuidanceTheKeysSetTheNavigationVolume() {
        assertEquals(navi, decide(now = 1_000, until = 1_001))
        assertEquals("the window has ended", media, decide(now = 1_001, until = 1_001))
    }

    @Test fun theCarKeepsItsOwnCases() {
        assertEquals("setting off", pass, decide(on = false))
        assertEquals("DiPlay gone", pass, decide(alive = false))
        assertEquals("reversing", pass, decide(rvc = 2))
        assertEquals("car in its navigation mode", pass, decide(naviState = 1, until = 5_000))
        // -1 is the DSP's "none yet": setVolumeByFlag would only store the value there.
        for (policy in listOf(-1, 1, 3, 5, 6, 7, 8)) {
            assertEquals("policy $policy", pass, decide(policy = policy))
            assertEquals("a call outranks guidance, policy $policy", pass, decide(policy = policy, until = 5_000))
        }
    }

    @Test fun passReasonsNameTheCase() {
        assertEquals("setting off", GuangqiVolumeHook.passReason(false, true, 0, 0, 0))
        assertEquals("reversing (rvc 2)", GuangqiVolumeHook.passReason(true, true, 2, 0, 0))
        assertEquals("car policy 3 has its own volume", GuangqiVolumeHook.passReason(true, true, 0, 0, 3))
    }

    @Test fun theVolumeStaysInTheCarRange() {
        assertEquals(12, GuangqiVolumeHook.nextVolume(10, 2))
        assertEquals(30, GuangqiVolumeHook.nextVolume(29, 5))
        assertEquals(0, GuangqiVolumeHook.nextVolume(1, -3))
    }

    @Test fun aMissingOrForeignProcessIsNotDiPlay() {
        assertFalse(GuangqiVolumeHook.diplayAlive("com.shihab.diplay", ""))
        assertFalse(GuangqiVolumeHook.diplayAlive("com.shihab.diplay", "0"))
        assertFalse(GuangqiVolumeHook.diplayAlive("com.shihab.diplay", "not a pid"))
        // This JVM is alive but is not DiPlay.
        assertFalse(GuangqiVolumeHook.diplayAlive("com.shihab.diplay", android.os.Process.myPid().toString()))
    }

    @Test fun outsideSystemServerNothingIsInstalled() {
        assertFalse(GuangqiVolumeHook.inSystemServer())
    }

    @Test fun everyMessageTheHookDoesNotTakeReachesTheCarHandler() {
        val (policy, targets) = fakeTargets()
        val original = policy.mHandler as RecordingHandler
        GuangqiVolumeHook.swap(app, targets, "test")
        val hooked = policy.mHandler
        assertNotSame(original, hooked)

        // The setting is off in this process, so even a volume message goes to the car.
        hooked.sendMessage(Message.obtain(hooked, GuangqiVolumeHook.MSG_VOLUME_SET_DELAY, 2, -1))
        hooked.sendMessage(Message.obtain(hooked, 7, 1, 0))
        var ran = false
        hooked.post { ran = true }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("19:2", "7:1"), original.seen)
        assertTrue(ran)
        assertEquals("nothing set on the DSP", emptyList<String>(), (targets.dsp as FakeDsp).calls)
    }

    @Test fun aSecondInstallKeepsTheFirstHook() {
        val (policy, targets) = fakeTargets()
        val original = policy.mHandler
        GuangqiVolumeHook.swap(app, targets, "first")
        val hooked = policy.mHandler
        GuangqiVolumeHook.swap(app, targets, "second")
        assertSame(hooked, policy.mHandler)
        assertSame(original, (hooked as GuangqiVolumeHook.VolumeHandler).original)
    }

    @Test fun aTakenStepUnmutesSetsTheVolumeAndTellsDiPlay() {
        val (_, targets) = fakeTargets()
        targets.apply(GuangqiVolumeHook.KIND_NAVI, GuangqiVolumeHook.DSP_FLAG_NAVI, 2, "guidance")
        assertEquals(listOf("unmute", "get 2", "set 12 2"), (targets.dsp as FakeDsp).calls)
        val shadow = shadowOf(app as android.app.Application)
        val sent = (shadow.broadcastIntents + shadow.getBroadcastIntentsForUser(android.os.Process.myUserHandle()))
            .last { it.action == GuangqiVolumeHook.ACTION_CAR_VOLUME }
        assertEquals(app.packageName, sent.`package`)
        assertEquals(GuangqiVolumeHook.KIND_NAVI, sent.getIntExtra(GuangqiVolumeHook.EXTRA_KIND, -1))
        assertEquals(12, sent.getIntExtra(GuangqiVolumeHook.EXTRA_VALUE, -1))
    }

    @Test fun theEndOfTheRangeStillUnmutesAndShowsTheBar() {
        val (_, targets) = fakeTargets()
        targets.apply(GuangqiVolumeHook.KIND_MEDIA, GuangqiVolumeHook.DSP_FLAG_MEDIA, 25, "media policy 0")
        assertEquals(listOf("unmute", "get 0", "set 30 0"), (targets.dsp as FakeDsp).calls)
    }

    private val app: Context get() = RuntimeEnvironment.getApplication()

    class RecordingHandler : Handler(Looper.getMainLooper()) {
        val seen = mutableListOf<String>()
        override fun handleMessage(msg: Message) {
            seen += "${msg.what}:${msg.arg1}"
        }
    }

    class FakeRvc { @JvmField var state = 0 }

    class FakePolicy {
        @JvmField var mHandler: Handler = RecordingHandler()
        @JvmField var mRvcAvmState = FakeRvc()
    }

    class FakeDsp {
        @JvmField var mPolicy = 0
        @JvmField var mNaviState = 0
        val calls = mutableListOf<String>()
        fun getVolumeByFlag(flag: Int): Int = 10.also { calls += "get $flag" }
        fun setVolumeByFlag(vol: Int, flag: Int): Int = 0.also { calls += "set $vol $flag" }
        fun setUnmute(): Int = 0.also { calls += "unmute" }
    }

    private fun fakeTargets(): Pair<FakePolicy, GuangqiVolumeHook.Targets> {
        val policy = FakePolicy()
        val dsp = FakeDsp()
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val constructor = GuangqiVolumeHook.Targets::class.java.declaredConstructors.single().apply { isAccessible = true }
        val targets = constructor.newInstance(
            app, policy, field(FakePolicy::class.java, "mHandler"), field(FakePolicy::class.java, "mRvcAvmState"),
            dsp, field(FakeDsp::class.java, "mPolicy"), field(FakeDsp::class.java, "mNaviState"),
            FakeDsp::class.java.getMethod("getVolumeByFlag", Int::class.javaPrimitiveType),
            FakeDsp::class.java.getMethod("setVolumeByFlag", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            FakeDsp::class.java.getMethod("setUnmute"),
        ) as GuangqiVolumeHook.Targets
        return policy to targets
    }
}
