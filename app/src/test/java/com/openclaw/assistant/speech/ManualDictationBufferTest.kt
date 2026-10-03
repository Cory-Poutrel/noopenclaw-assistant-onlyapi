package com.openclaw.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualDictationBufferTest {
    @Test
    fun `partial text is replaced while recognition is in progress`() {
        val buffer = ManualDictationBuffer()

        buffer.updatePartial("first")
        buffer.updatePartial("first draft")

        assertEquals("first draft", buffer.text())
    }

    @Test
    fun `final segments survive pauses and are joined in order`() {
        val buffer = ManualDictationBuffer()

        buffer.commitFinal("I need time")
        buffer.commitFinal("to think about this")
        buffer.updatePartial("before sending")

        assertEquals("I need time to think about this before sending", buffer.text())
    }

    @Test
    fun `committing a final result replaces its partial version`() {
        val buffer = ManualDictationBuffer()

        buffer.updatePartial("hello wor")
        buffer.commitFinal("hello world")

        assertEquals("hello world", buffer.text())
    }

    @Test
    fun `partial text can be preserved before restarting recognition`() {
        val buffer = ManualDictationBuffer()

        buffer.updatePartial("a long first segment")
        buffer.commitPartial()
        buffer.updatePartial("continued after restart")

        assertEquals("a long first segment continued after restart", buffer.text())
    }

    @Test
    fun `clear discards finalized and partial text`() {
        val buffer = ManualDictationBuffer()

        buffer.commitFinal("discard this")
        buffer.updatePartial("and this")
        buffer.clear()

        assertEquals("", buffer.text())
    }
}
