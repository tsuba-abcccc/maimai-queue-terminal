package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerContactTest {
    @Test
    fun oldProfileWithTwoContactsKeepsQqAndDropsPhoneNumber() {
        val profile = profile(qqNumber = "12345678", phoneNumber = "13800138000")

        val canonical = profile.withCanonicalContact()

        assertEquals("12345678", canonical.qqNumber)
        assertNull(canonical.phoneNumber)
        assertTrue(canonical.hasValidContact)
    }

    @Test
    fun profileWithoutQqKeepsMainlandChinaMobileNumber() {
        val canonical = profile(phoneNumber = "13800138000").withCanonicalContact()

        assertNull(canonical.qqNumber)
        assertEquals("13800138000", canonical.phoneNumber)
        assertTrue(canonical.hasValidContact)
    }

    @Test
    fun flexibleInputRecognizesMainlandChinaMobileNumber() {
        val contact = playerContactFromInput("13800138000", allowPhoneNumber = true)

        assertNull(contact.qqNumber)
        assertEquals("13800138000", contact.phoneNumber)
        assertTrue(contact.isValid)
    }

    @Test
    fun defaultInputTreatsElevenDigitsAsQq() {
        val contact = playerContactFromInput("13800138000", allowPhoneNumber = false)

        assertEquals("13800138000", contact.qqNumber)
        assertNull(contact.phoneNumber)
        assertTrue(contact.isValid)
    }

    @Test
    fun phoneNumberRejectsInternationalAndLandlineFormats() {
        assertFalse(isValidPhoneNumber("+8613800138000"))
        assertFalse(isValidPhoneNumber("01012345678"))
        assertFalse(isValidPhoneNumber("12800138000"))
        assertTrue(isValidPhoneNumber("19912345678"))
    }

    private fun profile(
        qqNumber: String? = null,
        phoneNumber: String? = null
    ) = PlayerProfile(
        id = "profile-1",
        nickname = "测试玩家",
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
        qqNumber = qqNumber,
        phoneNumber = phoneNumber
    )
}
