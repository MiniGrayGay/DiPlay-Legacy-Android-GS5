package com.shilapi.xcertplay

/** Splits `self-test.log`, which holds several runs, oldest first. */
internal object SelfTestReport {
    const val HEADER = "DiPlay self test "

    fun runs(text: String): List<String> {
        val runs = mutableListOf<StringBuilder>()
        for (line in text.lineSequence()) {
            if (line.startsWith(HEADER)) runs += StringBuilder()
            if (line.isNotEmpty()) runs.lastOrNull()?.append(line)?.append('\n')
        }
        return runs.map(StringBuilder::toString)
    }
}
