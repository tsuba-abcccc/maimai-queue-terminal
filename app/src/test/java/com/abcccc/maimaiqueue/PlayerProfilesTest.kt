package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProfilesTest {
    @Test
    fun avatarReferencesAcceptOnlyBoundedHttpUrls() {
        assertEquals(
            "https://queue.example.test/api/player-avatars/avatar.webp",
            normalizePlayerAvatarReference(
                " https://queue.example.test/api/player-avatars/avatar.webp "
            )
        )
        assertEquals(
            "http://192.0.2.1:8080/api/player-avatars/avatar.webp",
            normalizePlayerAvatarReference(
                "http://192.0.2.1:8080/api/player-avatars/avatar.webp"
            )
        )
        assertEquals(null, normalizePlayerAvatarReference("file:///tmp/avatar.webp"))
        assertEquals(null, normalizePlayerAvatarReference("https://user@example.test/avatar.webp"))
        assertEquals(null, normalizePlayerAvatarReference("https://example.test/avatar.webp#fragment"))
        assertEquals(null, normalizePlayerAvatarReference("https://${"a".repeat(520)}.test/avatar"))
    }

    private fun profile(
        id: String,
        nickname: String,
        usageCount: Int = 0,
        lastUsedAtMillis: Long? = null,
        recentUsageAtMillis: List<Long> = emptyList(),
        createdAtMillis: Long = 100L,
        preference: ProfilePlayPreference = ProfilePlayPreference.OPEN_TO_JOIN,
        qqNumber: String? = null
    ) = PlayerProfile(
        id = id,
        nickname = nickname,
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = preference,
        qqNumber = qqNumber,
        usageCount = usageCount,
        lastUsedAtMillis = lastUsedAtMillis,
        recentUsageAtMillis = recentUsageAtMillis,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = 100L
    )

    @Test
    fun terminalEditingIsLockedOnlyForBoundProfilesThatDisableIt() {
        val profile = profile("1", "测试玩家")

        assertTrue(profile.copy(webAccountBound = false, terminalEditingAllowed = false).canEditOnTerminal)
        assertTrue(profile.copy(webAccountBound = true, terminalEditingAllowed = true).canEditOnTerminal)
        assertFalse(profile.copy(webAccountBound = true, terminalEditingAllowed = false).canEditOnTerminal)
    }

    @Test
    fun notificationDefaultsEnableOnlyTheRequestedCategories() {
        val preferences = QueueNotificationPreferences()

        assertTrue(preferences.enabled)
        assertTrue(preferences.queueChanges)
        assertFalse(preferences.playingPosition)
        assertTrue(preferences.onlineCheckIn)
        assertTrue(preferences.absence)
        assertFalse(preferences.machineStatus)
    }

    @Test
    fun notificationDefaultsDoNotMakeALegacyProfileComplete() {
        val legacyProfile = profile("1", "旧资料")

        assertEquals(QueueNotificationPreferences(), legacyProfile.notificationPreferences)
        assertFalse(legacyProfile.hasCompleteRequiredDetails)
        assertTrue(
            legacyProfile.copy(setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION)
                .hasCompleteRequiredDetails
        )
    }

    @Test
    fun cloudProfilesOnlyReplaceAnOlderMatchingLocalRevision() {
        val local = profile("1", "小雨").copy(revision = 4L)
        val newer = local.copy(nickname = "新小雨", revision = 5L)
        val sameRevision = local.copy(nickname = "冲突内容", revision = 4L)

        assertTrue(shouldApplyCloudPlayerProfile(newer, listOf(local)) { _, _ -> false })
        assertFalse(
            shouldApplyCloudPlayerProfile(sameRevision, listOf(local)) { _, _ -> false }
        )
    }

    @Test
    fun cloudProfilesDoNotIntroduceNicknameOrQqConflicts() {
        val local = profile("1", "小雨", qqNumber = "12345678")
        val duplicateNickname = profile("2", "小雨", qqNumber = "87654321")
            .copy(revision = 2L)
        val duplicateQq = profile("3", "青空", qqNumber = "12345678")
            .copy(revision = 2L)

        assertFalse(
            shouldApplyCloudPlayerProfile(duplicateNickname, listOf(local)) { _, _ -> false }
        )
        assertFalse(
            shouldApplyCloudPlayerProfile(duplicateQq, listOf(local)) { _, _ -> false }
        )
        assertFalse(
            shouldApplyCloudPlayerProfile(
                profile("4", "队列昵称").copy(revision = 2L),
                listOf(local)
            ) { _, _ -> true }
        )
    }

    @Test
    fun recentFrequencyOutranksLargeHistoricalUsageCount() {
        val now = 200L * DAY
        val profiles = listOf(
            profile(
                "historical",
                "旧常客",
                usageCount = 2_000,
                lastUsedAtMillis = now - 180L * DAY,
                recentUsageAtMillis = listOf(now - 180L * DAY)
            ),
            profile(
                "active",
                "近期玩家",
                usageCount = 8,
                lastUsedAtMillis = now - DAY,
                recentUsageAtMillis = listOf(
                    now - 8L * DAY,
                    now - 4L * DAY,
                    now - DAY
                )
            )
        )

        val sorted = filterAndSortPlayerProfiles(
            profiles,
            "",
            ProfileSortMode.RECOMMENDED,
            nowMillis = now
        )

        assertEquals(listOf("active", "historical"), sorted.map { it.id })
    }

    @Test
    fun repeatedRecentUsageOutranksOneEquallyRecentVisit() {
        val now = 200L * DAY
        val repeated = profile(
            "repeated",
            "近期常来",
            usageCount = 4,
            lastUsedAtMillis = now - DAY,
            recentUsageAtMillis = listOf(now - 6L * DAY, now - 3L * DAY, now - DAY)
        )
        val oneVisit = profile(
            "single",
            "只来一次",
            usageCount = 30,
            lastUsedAtMillis = now - DAY,
            recentUsageAtMillis = listOf(now - DAY)
        )

        val sorted = filterAndSortPlayerProfiles(
            listOf(oneVisit, repeated),
            "",
            ProfileSortMode.RECOMMENDED,
            nowMillis = now
        )

        assertEquals(listOf("repeated", "single"), sorted.map { it.id })
    }

    @Test
    fun newlyCreatedProfileGetsTemporaryDiscoveryBoost() {
        val now = 200L * DAY
        val established = profile(
            "established",
            "已有玩家",
            usageCount = 2,
            lastUsedAtMillis = now - 20L * DAY,
            recentUsageAtMillis = listOf(now - 20L * DAY),
            createdAtMillis = now - 100L * DAY
        )
        val newProfile = profile(
            "new",
            "新玩家",
            createdAtMillis = now - DAY
        )

        val whileNew = filterAndSortPlayerProfiles(
            listOf(established, newProfile),
            "",
            ProfileSortMode.RECOMMENDED,
            nowMillis = now
        )
        val afterBoostExpires = filterAndSortPlayerProfiles(
            listOf(established, newProfile),
            "",
            ProfileSortMode.RECOMMENDED,
            nowMillis = now + 20L * DAY
        )

        assertEquals("new", whileNew.first().id)
        assertEquals("established", afterBoostExpires.first().id)
    }

    @Test
    fun exactSearchMatchStaysAheadOfHigherRecommendationScore() {
        val now = 200L * DAY
        val exact = profile("exact", "Rin", createdAtMillis = now - 100L * DAY)
        val popularSubstring = profile(
            "popular",
            "RinRin",
            usageCount = 100,
            lastUsedAtMillis = now,
            recentUsageAtMillis = List(10) { now - it * DAY },
            createdAtMillis = now - 100L * DAY
        )

        val result = filterAndSortPlayerProfiles(
            listOf(popularSubstring, exact),
            "rin",
            ProfileSortMode.RECOMMENDED,
            nowMillis = now
        )

        assertEquals(listOf("exact", "popular"), result.map { it.id })
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
    fun searchMatchesPlayerQqNumber() {
        val profiles = listOf(
            profile("1", "小雨", qqNumber = "12345678"),
            profile("2", "青空", qqNumber = "87654321")
        )

        val result = filterAndSortPlayerProfiles(profiles, " 3456 ", ProfileSortMode.RECOMMENDED)

        assertEquals(listOf("小雨"), result.map { it.nickname })
    }

    @Test
    fun recordingUsageIncrementsFrequencyAndUpdatesTimestamps() {
        val updated = profile("1", "小雨", usageCount = 4, lastUsedAtMillis = 200L).recordUsage(800L)

        assertEquals(5, updated.usageCount)
        assertEquals(800L, updated.lastUsedAtMillis)
        assertEquals(listOf(200L, 800L), updated.recentUsageAtMillis)
        assertEquals(800L, updated.updatedAtMillis)
    }

    @Test
    fun recordingUsageKeepsOnlyTheNewestBoundedHistory() {
        val original = profile(
            "1",
            "小雨",
            recentUsageAtMillis = (1L..MAX_RECENT_PLAYER_USAGE_EVENTS.toLong()).toList(),
            lastUsedAtMillis = MAX_RECENT_PLAYER_USAGE_EVENTS.toLong()
        )

        val updated = original.recordUsage(MAX_RECENT_PLAYER_USAGE_EVENTS + 1L)

        assertEquals(MAX_RECENT_PLAYER_USAGE_EVENTS, updated.recentUsageAtMillis.size)
        assertEquals(2L, updated.recentUsageAtMillis.first())
        assertEquals(MAX_RECENT_PLAYER_USAGE_EVENTS + 1L, updated.recentUsageAtMillis.last())
    }

    @Test
    fun futureTimestampsAreClampedInsteadOfReceivingUnboundedScores() {
        val now = 200L * DAY
        val current = profile(
            "current",
            "当前时间",
            usageCount = 1,
            lastUsedAtMillis = now,
            recentUsageAtMillis = listOf(now),
            createdAtMillis = now
        )
        val future = current.copy(
            id = "future",
            nickname = "未来时间",
            lastUsedAtMillis = Long.MAX_VALUE,
            recentUsageAtMillis = listOf(Long.MAX_VALUE),
            createdAtMillis = Long.MAX_VALUE
        )

        assertEquals(
            playerProfileRecommendationScore(current, now),
            playerProfileRecommendationScore(future, now),
            0.0
        )
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
        assertTrue(first.hasValidContact)
        assertEquals(QqVisibility.PUBLIC_WEBSITE, first.qqVisibility)
        assertNotEquals(first.id, second.id)
        assertEquals(700L, first.updatedAtMillis)
    }

    @Test
    fun legacyProfilesNeedAValidQqNumber() {
        val legacyProfile = profile("1", "旧资料")

        assertFalse(legacyProfile.hasValidContact)
        assertTrue(legacyProfile.copy(qqNumber = "12345").hasValidContact)
        assertFalse(legacyProfile.copy(qqNumber = "1234").hasValidContact)
    }

    @Test
    fun contactValidationAcceptsOnlyFiveToTwelveDigitQqNumbers() {
        assertTrue(isValidQqNumber("12345"))
        assertTrue(isValidQqNumber("123456789012"))
        assertFalse(isValidQqNumber("1234"))
        assertFalse(isValidQqNumber("1234567890123"))
        assertFalse(isValidQqNumber("12345a"))
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

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
    }

    @Test
    fun aNewProfileCannotTakeTheNicknameOfATemporaryRegistration() {
        val temporary = Registration(key = 1, displayId = "小雨", preference = PlayPreference.OPEN_TO_JOIN)
        // Neither has a profile id; they are still two players.
        assertTrue(nicknameConflictsWithRegistrations(" 小雨 ", listOf(temporary), profileId = null))
        assertTrue(nicknameConflictsWithRegistrations("小雨", listOf(temporary), profileId = "p1"))
        assertFalse(nicknameConflictsWithRegistrations("青空", listOf(temporary), profileId = null))
        assertFalse(nicknameConflictsWithRegistrations("  ", listOf(temporary), profileId = null))
        // The temporary registration that is being claimed is the player's own.
        assertFalse(
            nicknameConflictsWithRegistrations(
                "小雨",
                listOf(temporary),
                profileId = null,
                exceptRegistrationKey = 1
            )
        )
    }

    @Test
    fun aProfileKeepsItsNicknameWhileItsOwnRegistrationWaits() {
        val own = Registration(
            key = 2,
            displayId = "小雨",
            preference = PlayPreference.SOLO,
            isTemporary = false,
            playerProfileId = "p1"
        )
        assertFalse(nicknameConflictsWithRegistrations("小雨", listOf(own), profileId = "p1"))
        assertFalse(
            nicknameConflictsWithRegistrations(
                "小雨",
                listOf(own),
                profileId = "p9",
                equivalentProfileIds = setOf("p1")
            )
        )
        assertTrue(nicknameConflictsWithRegistrations("小雨", listOf(own), profileId = "p2"))
        assertTrue(nicknameConflictsWithRegistrations("小雨", listOf(own), profileId = null))
    }

    @Test
    fun theLibraryFindsTheRegistrationOfAProfileAndSaysWhereItQueues() {
        val player = profile("p1", "小雨")
        val byProfile = Registration(
            key = 3,
            displayId = "改过的昵称",
            preference = PlayPreference.SOLO,
            isTemporary = false,
            playerProfileId = "p1"
        )
        val byNickname = Registration(key = 4, displayId = "小雨", preference = PlayPreference.SOLO)
        val other = Registration(key = 5, displayId = "青空", preference = PlayPreference.SOLO)
        assertEquals(byProfile, registrationOfPlayerProfile(player, listOf(other, byProfile)))
        assertEquals(byNickname, registrationOfPlayerProfile(player, listOf(other, byNickname)))
        assertNull(registrationOfPlayerProfile(player, listOf(other)))
        assertNull(registrationOfPlayerProfile(player, listOf(byNickname), exceptKey = 4))
        assertEquals("排队中·位置 A7", queuedPlayerProfileLine("位置 A7"))
        assertEquals("排队中·游玩位置 A", queuedPlayerProfileLine("游玩位置 A"))
        assertEquals("排队中", queuedPlayerProfileLine(null))
    }
}
