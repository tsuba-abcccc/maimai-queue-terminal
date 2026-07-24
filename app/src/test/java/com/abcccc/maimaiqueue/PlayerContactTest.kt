package com.abcccc.maimaiqueue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerContactTest {
    @Test
    fun qqNumberIsTrimmedWhenProfileIsCanonicalized() {
        val profile = profile(qqNumber = " 12345678 ")

        val canonical = profile.withCanonicalContact()

        assertTrue(canonical.qqNumber == "12345678")
        assertTrue(canonical.hasValidContact)
    }

    @Test
    fun profileWithoutQqMustBeCompletedBeforeUse() {
        val canonical = profile().withCanonicalContact()

        assertFalse(canonical.hasValidContact)
    }

    @Test
    fun qqValidationRejectsNonDigitsAndOutOfRangeLengths() {
        assertTrue(isValidQqNumber("12345"))
        assertTrue(isValidQqNumber("123456789012"))
        assertFalse(isValidQqNumber("1234"))
        assertFalse(isValidQqNumber("1234567890123"))
        assertFalse(isValidQqNumber("12345a"))
    }

    @Test
    fun duplicateLegacyQqBindingsAreClearedFromEveryAmbiguousProfile() {
        val migrated = clearAmbiguousQqBindings(
            listOf(
                profile(id = "profile-1", qqNumber = "12345678"),
                profile(id = "profile-2", qqNumber = " 12345678 "),
                profile(id = "profile-3", qqNumber = "87654321")
            ),
            migratedAtMillis = 900L
        )

        assertNull(migrated[0].qqNumber)
        assertNull(migrated[1].qqNumber)
        assertTrue(migrated[0].updatedAtMillis == 900L)
        assertTrue(migrated[1].updatedAtMillis == 900L)
        assertTrue(migrated[2].qqNumber == "87654321")
    }

    private fun profile(id: String = "profile-1", qqNumber: String? = null) = PlayerProfile(
        id = id,
        nickname = "测试玩家",
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
        qqNumber = qqNumber,
        createdAtMillis = 100L,
        updatedAtMillis = 100L
    )
}
