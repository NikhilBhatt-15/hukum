package com.nikhilbhatt.hukum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalParserTest {
    private fun simple(s: String) = LocalParser.parseSimple(s)?.actions

    @Test fun openAndChain() {
        assertEquals(listOf(Action.OpenApp("youtube")), simple("Open YouTube."))
        assertEquals(listOf(Action.OpenApp("instagram"), Action.Scroll("down", 3)), simple("open instagram and scroll down 3 times"))
        assertEquals(listOf(Action.OpenApp("camera")), simple("hey hukum please open the camera app"))
        assertEquals(listOf(Action.OpenApp("youtube")), simple("youtube kholo"))
    }

    @Test fun closeScrollNav() {
        assertEquals(listOf(Action.CloseApp(null)), simple("close this app"))
        assertEquals(listOf(Action.CloseApp("youtube")), simple("close youtube"))
        assertEquals(listOf(Action.Scroll("up", 2)), simple("scroll up twice"))
        assertEquals(listOf(Action.Scroll("down", 1)), simple("scroll"))
        assertEquals(listOf(Action.Back, Action.Home), simple("go back then go home"))
    }

    @Test fun complexGoesToLlm() {
        assertNull(simple("send hi to rahul on whatsapp"))
        assertNull(simple("open whatsapp and message mom"))
        assertNull(simple("what's the weather"))
    }

    @Test fun whatsAppFallback() {
        assertEquals(listOf(Action.WhatsApp("Rahul", "hi there")), LocalParser.parseFallback("Send hi there to Rahul on WhatsApp")?.actions)
        assertEquals(listOf(Action.WhatsApp("Mom", "I'll be late")), LocalParser.parseFallback("WhatsApp Mom saying I'll be late")?.actions)
        assertEquals(listOf(Action.WhatsApp("Rahul", "on my way")), LocalParser.parseFallback("send a message to Rahul on whatsapp saying on my way")?.actions)
    }

    @Test fun fuzzyAndNumbers() {
        assertTrue(Fuzzy.score("rahul", "Rahul Sharma") > Fuzzy.score("rahul", "Raghav"))
        assertTrue(Fuzzy.score("you tube", "YouTube") > 0.9)
        assertEquals("919876543210", ContactResolver.normalize("098765 43210", "91"))
        assertEquals("919876543210", ContactResolver.normalize("+91 98765-43210", "91"))
        assertEquals("447700900123", ContactResolver.normalize("0044 7700 900123", "91"))
    }
}
