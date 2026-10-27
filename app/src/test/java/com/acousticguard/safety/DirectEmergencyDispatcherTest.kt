package com.acousticguard.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectEmergencyDispatcherTest {

    @Test
    fun defaultSosConfigHasSensibleDefaults() {
        val config = SosConfig()
        assertEquals("", config.primaryCallNumber)
        assertFalse(config.autoCallEnabled)
        assertEquals("", config.emergencySmsNumbers)
        assertFalse(config.autoSmsEnabled)
        assertTrue(config.sirenEnabled)
        assertEquals("", config.customSmsMessage)
    }

    @Test
    fun parseContactNumbersHandlesVariousDelimitersAndWhitespace() {
        val input = " +15551234 , 9876543210;\n+44123456789 , \n"
        val numbers = DirectEmergencyDispatcher.parseContactNumbers(input)
        assertEquals(3, numbers.size)
        assertEquals("+15551234", numbers[0])
        assertEquals("9876543210", numbers[1])
        assertEquals("+44123456789", numbers[2])
    }

    @Test
    fun parseContactNumbersIgnoresEmptyEntries() {
        val input = "  , ; \n  "
        val numbers = DirectEmergencyDispatcher.parseContactNumbers(input)
        assertTrue(numbers.isEmpty())
    }

    @Test
    fun sanitizePhoneNumberStripsFormattingCharacters() {
        assertEquals("+15551234567", DirectEmergencyDispatcher.sanitizePhoneNumber("+1 (555) 123-4567"))
        assertEquals("911", DirectEmergencyDispatcher.sanitizePhoneNumber(" 911 "))
        assertEquals("112", DirectEmergencyDispatcher.sanitizePhoneNumber("112-SOS"))
        assertEquals("+919876543210", DirectEmergencyDispatcher.sanitizePhoneNumber("+91 98765-43210"))
    }

    @Test
    fun buildSmsMessageUsesDefaultWhenCustomIsBlank() {
        val message = DirectEmergencyDispatcher.buildSmsMessage("", "Scream", "12:34:56")
        assertTrue(message.contains("AcousticGuard detected severe distress (Scream)"))
        assertTrue(message.contains("12:34:56"))
        assertTrue(message.contains("Please send help!"))
    }

    @Test
    fun buildSmsMessageAppendsDiagnosticTagWhenCustomIsProvided() {
        val custom = "I am in danger, please contact authorities immediately."
        val message = DirectEmergencyDispatcher.buildSmsMessage(custom, "Vocal Distress", "14:15:16")
        assertTrue(message.startsWith(custom))
        assertTrue(message.contains("[AcousticGuard SOS: Vocal Distress at 14:15:16]"))
    }
}
