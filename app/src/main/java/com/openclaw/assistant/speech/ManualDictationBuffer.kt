package com.openclaw.assistant.speech

/**
 * Accumulates speech-recognition segments until the user explicitly submits them.
 *
 * Android's segmented recognition may finalize text whenever it detects a pause.
 * Manual dictation retains those segments until the user sends or cancels them.
 */
class ManualDictationBuffer {
    private val finalizedSegments = mutableListOf<String>()
    private var partialSegment: String = ""

    fun updatePartial(text: String) {
        partialSegment = text.trim()
    }

    fun commitFinal(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) {
            finalizedSegments += normalized
        }
        partialSegment = ""
    }

    fun commitPartial() {
        commitFinal(partialSegment)
    }

    fun text(): String = buildList {
        addAll(finalizedSegments)
        if (partialSegment.isNotEmpty()) {
            add(partialSegment)
        }
    }.joinToString(" ")

    fun clear() {
        finalizedSegments.clear()
        partialSegment = ""
    }
}
