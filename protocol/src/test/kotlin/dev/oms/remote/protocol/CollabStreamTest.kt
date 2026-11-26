package dev.oms.remote.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollabStreamTest {
    private val baseline = Vectors.streamLine("baseline")
    private val patch = requireNotNull(Vectors.streamLine("frame").obj("patch"))

    @Test
    fun appliesVectorPatch() {
        assertEquals(Vectors.streamLine("result"), CollabStream.apply(baseline, patch))
    }

    /** Offsets count UTF-16 code units: an emoji is two units, as in JavaScript. */
    @Test
    fun appendOffsetsAreUtf16Units() {
        val previous = Fixtures.json("""{"role":"assistant","content":[{"type":"text","text":"hi 👋"}]}""")
        val append = Fixtures.json(
            """{"metadata":{"role":"assistant"},"contentLength":1,"changes":[{"index":0,"kind":"append","field":"text","offset":5,"text":"!","properties":{"type":"text"}}]}""",
        )
        assertEquals("hi 👋!", CollabStream.apply(previous, append)?.arr("content")?.get(0)?.asObject?.str("text"))
        val codePointOffset = Fixtures.json(append.toString().replace("\"offset\":5", "\"offset\":4"))
        assertNull(CollabStream.apply(previous, codePointOffset))
    }

    @Test
    fun invalidPatchesReturnNull() {
        fun mutated(from: String, to: String) = Fixtures.json(patch.toString().replace(from, to))
        // Append offset does not match the baseline text length.
        assertNull(CollabStream.apply(baseline, mutated("\"offset\":3", "\"offset\":2")))
        // Change index outside contentLength.
        assertNull(CollabStream.apply(baseline, mutated("\"index\":2", "\"index\":3")))
        // contentLength grows without a replacement for the new slot.
        assertNull(CollabStream.apply(baseline, mutated("\"contentLength\":3", "\"contentLength\":4")))
        // Unknown change kind.
        assertNull(CollabStream.apply(baseline, mutated("\"kind\":\"replace\"", "\"kind\":\"splice\"")))
    }
}
