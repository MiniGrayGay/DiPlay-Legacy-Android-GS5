package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfTestReportTest {
    @Test fun splitsRunsAndDropsWhatComesBefore() {
        val text = "noise\n${SelfTestReport.HEADER}1\na\nb\n${SelfTestReport.HEADER}2\nc\n"
        assertEquals(listOf("${SelfTestReport.HEADER}1\na\nb\n", "${SelfTestReport.HEADER}2\nc\n"), SelfTestReport.runs(text))
        assertEquals(emptyList<String>(), SelfTestReport.runs(""))
    }
}
