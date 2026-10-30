package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalLinkMatcherTest {
    @Test fun matchesOriginalGhosttyExpressionAndOnigurumaOffsets() {
        for (encoded in terminalLinkMatcherFixtures()) {
            val fixture = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val input = fixture.getValue("input").jsonPrimitive.content
            val expected = fixture.getValue("ranges").jsonArray.map { pair ->
                val range = pair.jsonArray
                TerminalLinkMatch(range[0].jsonPrimitive.int, range[1].jsonPrimitive.int)
            }
            assertEquals(expected, terminalLinkMatches(input), "input=${fixture["input"]}")
        }
    }

    @Test fun longNonMatchingPathsAndAdjacentLinksRemainBounded() {
        assertTrue(terminalLinkMatches("dir/".repeat(20_000) + "file").isEmpty())
        val links = terminalLinkMatches("https://example.org ".repeat(10_000))
        assertEquals(10_000, links.size)
        assertEquals("https://example.org".length, links.first().end)
    }
}
