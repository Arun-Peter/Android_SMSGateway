package com.r2r.smsgateway

import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test fun messageStateFollowsAllParts() {
        assertEquals(State.QUEUED, Logic.state(3, sentMask = 0b011, deliveredMask = 0, failed = false))
        assertEquals(State.SENT, Logic.state(3, sentMask = 0b111, deliveredMask = 0b001, failed = false))
        assertEquals(State.DELIVERED, Logic.state(3, sentMask = 0b111, deliveredMask = 0b111, failed = false))
        assertEquals(State.FAILED, Logic.state(3, sentMask = 0b111, deliveredMask = 0b111, failed = true))
        assertEquals(State.SENT, Logic.state(1, sentMask = 1, deliveredMask = 0, failed = false))
    }

    @Test fun gsmDeliveryStatusRanges() {
        assertEquals(Delivery.DELIVERED, Logic.delivery("3gpp", 0x00))
        assertEquals(Delivery.DELIVERED, Logic.delivery("3gpp", 0x02))
        assertEquals(Delivery.PENDING, Logic.delivery("3gpp", 0x20))
        assertEquals(Delivery.PENDING, Logic.delivery("3gpp", 0x3F))
        assertEquals(Delivery.FAILED, Logic.delivery("3gpp", 0x40))
        assertEquals(Delivery.FAILED, Logic.delivery("3gpp", 0x62))
        assertEquals(Delivery.DELIVERED, Logic.delivery("3gpp2", 0))
        assertEquals(Delivery.FAILED, Logic.delivery("3gpp2", 0x30000))
    }

    @Test fun sendErrorsAreReadable() {
        assertEquals("no mobile service", Logic.sendError(SmsManager.RESULT_ERROR_NO_SERVICE, 0))
        assertEquals("generic failure (error 38)", Logic.sendError(SmsManager.RESULT_ERROR_GENERIC_FAILURE, 38))
        assertEquals("send error 99", Logic.sendError(99, 0))
    }

    @Test fun phoneNumbersAreCleaned() {
        assertEquals("+910000000000", Logic.cleanNumber(" +91 00000-00000 "))
        assertEquals("0000000000", Logic.cleanNumber("(000) 000.0000"))
        assertNull(Logic.cleanNumber("hello"))
        assertNull(Logic.cleanNumber("12"))
        assertNull(Logic.cleanNumber(null))
    }

    @Test fun idsAndCommands() {
        assertTrue(Logic.validId("MSG-001_a.b"))
        assertFalse(Logic.validId("bad id"))
        assertFalse(Logic.validId("x".repeat(65)))
        assertEquals("send", Logic.command("com.r2r.smsgateway.SEND"))
        assertEquals("status", Logic.command("Status"))
        assertNull(Logic.command(null))
    }
}

class ClearPasswordTest {
    // The correct password is deliberately not written here (only its hash is in the app).
    @Test fun wrongPasswordsDoNotClear() {
        assertFalse(Logic.isClearPassword(""))
        assertFalse(Logic.isClearPassword("password"))
        assertFalse(Logic.isClearPassword("GgnuqUYgUJKU8NRvc7Szk4e7"))
    }
}
