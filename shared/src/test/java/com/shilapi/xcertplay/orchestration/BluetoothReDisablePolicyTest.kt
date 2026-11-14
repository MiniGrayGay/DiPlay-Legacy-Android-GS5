package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.orchestration.BluetoothReDisablePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothReDisablePolicyTest {
    @Test fun givesUpAfterTheTotalLimitWhenTheCarReEnablesSlowly() {
        val policy = BluetoothReDisablePolicy(maxTotal = 3)
        var now = 10_000L
        repeat(3) {
            assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(now))
            now += 5_000L
        }
        assertEquals(Decision.GIVE_UP_TOTAL, policy.onReEnabled(now))
    }

    @Test fun givesUpOnATightLoop() {
        val policy = BluetoothReDisablePolicy(maxRapid = 3)
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(10_000L))
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(10_500L))
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(11_000L))
        assertEquals(Decision.GIVE_UP_RAPID, policy.onReEnabled(11_500L))
    }

    @Test fun aSlowReEnableClearsTheRapidCount() {
        val policy = BluetoothReDisablePolicy(maxRapid = 2)
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(10_000L))
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(10_500L))
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(15_500L))
        assertEquals(0, policy.rapid)
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(16_000L))
    }

    @Test fun resetStartsTheCountsAgain() {
        val policy = BluetoothReDisablePolicy(maxTotal = 1)
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(10_000L))
        assertEquals(Decision.GIVE_UP_TOTAL, policy.onReEnabled(15_000L))
        policy.reset()
        assertEquals(Decision.DISABLE_AGAIN, policy.onReEnabled(20_000L))
    }
}
