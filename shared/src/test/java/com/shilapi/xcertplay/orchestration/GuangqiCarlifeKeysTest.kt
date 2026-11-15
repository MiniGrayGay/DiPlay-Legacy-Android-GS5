package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.orchestration.GuangqiCarlifeKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class GuangqiCarlifeKeysTest {
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "A")
    private val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "A", 1L)
    private val dialing = CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "A")

    @Test fun callKeyAnswersOnlyARingingCall() {
        assertEquals(Action.ANSWER, GuangqiCarlifeKeys.action(137, ringing))
        assertEquals(Action.NONE, GuangqiCarlifeKeys.action(137, active))
        assertEquals(Action.NONE, GuangqiCarlifeKeys.action(137, null))
    }

    @Test fun hangUpKeyEndsOrDeclinesAnyCall() {
        assertEquals(Action.END, GuangqiCarlifeKeys.action(138, ringing))
        assertEquals(Action.END, GuangqiCarlifeKeys.action(138, active))
        assertEquals(Action.END, GuangqiCarlifeKeys.action(138, dialing))
        assertEquals(Action.NONE, GuangqiCarlifeKeys.action(138, null))
    }

    @Test fun voiceKeyLongPressOpensSiri() {
        assertEquals(Action.SIRI, GuangqiCarlifeKeys.action(57, null))
        assertEquals(Action.SIRI, GuangqiCarlifeKeys.action(57, active))
    }

    @Test fun otherKeysDoNothing() {
        assertEquals(Action.NONE, GuangqiCarlifeKeys.action(133, ringing))
        assertEquals(Action.NONE, GuangqiCarlifeKeys.action(87, ringing))
    }
}
