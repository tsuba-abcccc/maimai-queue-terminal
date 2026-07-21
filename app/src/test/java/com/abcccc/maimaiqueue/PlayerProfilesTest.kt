package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProfilesTest {
    private fun profile(
        id: String,
        nickname: String,
        usageCount: Int = 0,
        lastUsedAtMillis: Long? = null,
        preference: ProfilePlayPreference = ProfilePlayPreference.OPEN_TO_JOIN
    ) = PlayerProfile(
        id = id,
        nickname = nickname,
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = preference,
        usageCount = usageCount,
        lastUsedAtMillis = lastUsedAtMillis,
        createdAtMillis = 100L,
        updatedAtMillis = 100L
    )

    @Test
    fun recommendedSortUsesFrequencyThenMostRecentUsage() {
        val profiles = listOf(
            profile("1", "陈一", usageCount = 2, lastUsedAtMillis = 500L),
            profile("2", "陈二", usageCount = 5, lastUsedAtMillis = 300L),
            profile("3", "陈三", usageCount = 5, lastUsedAtMillis = 900L)
        )

        val sorted = filterAndSortPlayerProfiles(profiles, "", ProfileSortMode.RECOMMENDED)

        assertEquals(listOf("3", "2", "1"), sorted.map { it.id })
    }

    @Test
    fun alphabeticalSortUsesChineseLocalizedOrder() {
        val profiles = listOf(
            profile("3", "张三"),
            profile("1", "阿青"),
            profile("2", "李雷")
        )

        val sorted = filterAndSortPlayerProfiles(profiles, "", ProfileSortMode.ALPHABETICAL)

        assertEquals(listOf("阿青", "李雷", "张三"), sorted.map { it.nickname })
    }

    @Test
    fun searchIgnoresSurroundingWhitespaceAndLatinCase() {
        val profiles = listOf(profile("1", "Rin"), profile("2", "Mika"))

        val result = filterAndSortPlayerProfiles(profiles, "  RI  ", ProfileSortMode.RECOMMENDED)

        assertEquals(listOf("Rin"), result.map { it.nickname })
    }

    @Test
    fun recordingUsageIncrementsFrequencyAndUpdatesTimestamps() {
        val updated = profile("1", "小雨", usageCount = 4, lastUsedAtMillis = 200L).recordUsage(800L)

        assertEquals(5, updated.usageCount)
        assertEquals(800L, updated.lastUsedAtMillis)
        assertEquals(800L, updated.updatedAtMillis)
    }

    @Test
    fun chosenPreferenceCanReplaceAskEveryTimeWhenUsageIsRecorded() {
        val updated = profile(
            id = "1",
            nickname = "小雨",
            preference = ProfilePlayPreference.ASK_EVERY_TIME
        ).recordUsage(
            atMillis = 800L,
            preferenceToRemember = PlayPreference.SOLO
        )

        assertEquals(ProfilePlayPreference.SOLO, updated.defaultPreference)
        assertEquals(1, updated.usageCount)
    }

    @Test
    fun newProfilesTrimNicknamesAndUseStableUniqueIdentifiers() {
        val first = createPlayerProfile(
            nickname = "  小雨  ",
            gender = PlayerGender.FEMALE,
            defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
            qqNumber = "  12345678  ",
            phoneNumber = "  +86 138-0000-0000  ",
            createdAtMillis = 700L
        )
        val second = createPlayerProfile(
            nickname = "小雨",
            gender = PlayerGender.FEMALE,
            defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
            createdAtMillis = 700L
        )

        assertEquals("小雨", first.nickname)
        assertEquals("12345678", first.qqNumber)
        assertEquals("+86 138-0000-0000", first.phoneNumber)
        assertTrue(first.hasValidContact)
        assertNotEquals(first.id, second.id)
        assertEquals(700L, first.updatedAtMillis)
    }

    @Test
    fun legacyProfilesNeedAtLeastOneValidContactMethod() {
        val legacyProfile = profile("1", "旧资料")

        assertFalse(legacyProfile.hasValidContact)
        assertTrue(legacyProfile.copy(qqNumber = "12345").hasValidContact)
        assertTrue(legacyProfile.copy(phoneNumber = "010-12345678").hasValidContact)
        assertFalse(legacyProfile.copy(qqNumber = "1234").hasValidContact)
        assertFalse(legacyProfile.copy(phoneNumber = "123456").hasValidContact)
    }

    @Test
    fun contactValidationPreservesPhoneFormattingButRejectsInvalidCharacters() {
        assertTrue(isValidQqNumber("123456789012"))
        assertFalse(isValidQqNumber("1234567890123"))
        assertTrue(isValidPhoneNumber("+86 (10) 1234-5678"))
        assertFalse(isValidPhoneNumber("13800000000 ext 2"))
    }

    @Test
    fun askEveryTimeDoesNotResolveToAQueuePreference() {
        assertNull(ProfilePlayPreference.ASK_EVERY_TIME.toPlayPreferenceOrNull())
        assertEquals(PlayPreference.SOLO, ProfilePlayPreference.SOLO.toPlayPreferenceOrNull())
        assertEquals(
            PlayPreference.OPEN_TO_JOIN,
            ProfilePlayPreference.OPEN_TO_JOIN.toPlayPreferenceOrNull()
        )
    }

    @Test
    fun registrationKeepsProfileIdentityAndGender() {
        val registration = Registration(
            key = 1,
            displayId = "小雨",
            preference = PlayPreference.OPEN_TO_JOIN,
            isTemporary = false,
            gender = PlayerGender.FEMALE,
            playerProfileId = "profile-1"
        )

        assertEquals(PlayerGender.FEMALE, registration.gender)
        assertEquals("profile-1", registration.playerProfileId)
        assertTrue(!registration.isTemporary)
    }
}
