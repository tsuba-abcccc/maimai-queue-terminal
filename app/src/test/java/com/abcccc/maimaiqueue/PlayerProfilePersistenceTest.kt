package com.abcccc.maimaiqueue

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProfilePersistenceTest {
    @Test
    fun successfulWriteCompletesBeforeProfileIsApplied() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakePlayerProfileRepository(events = events)
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val profile = profile("profile-1")

        val persisted = coordinator.persistAndApply(profile) {
            events += "apply:${it.id}"
        }

        assertTrue(persisted)
        assertEquals(listOf("persist:profile-1", "apply:profile-1"), events)
    }

    @Test
    fun failedWriteDoesNotApplyProfile() = runBlocking {
        val repository = FakePlayerProfileRepository(failedIds = setOf("profile-1"))
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val applied = mutableListOf<PlayerProfile>()

        val persisted = coordinator.persistAndApply(profile("profile-1"), applied::add)

        assertFalse(persisted)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun thrownWriteDoesNotApplyProfile() = runBlocking {
        val repository = FakePlayerProfileRepository(throwingIds = setOf("profile-1"))
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val applied = mutableListOf<PlayerProfile>()

        val persisted = coordinator.persistAndApply(profile("profile-1"), applied::add)

        assertFalse(persisted)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun mutationUsesLatestProfileInsteadOfStaleScreenSnapshot() = runBlocking {
        val repository = FakePlayerProfileRepository()
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val staleProfile = profile("profile-1")
        var currentProfile = staleProfile.copy(
            nickname = "QQ 修改后的昵称",
            gender = PlayerGender.FEMALE,
            defaultPreference = ProfilePlayPreference.SOLO,
            updatedAtMillis = 200L
        )

        val persisted = coordinator.mutateAndApply(
            profileId = staleProfile.id,
            currentProfiles = { listOf(currentProfile) },
            mutation = { latest -> latest.recordUsage(atMillis = 300L) },
            onPersisted = { currentProfile = it }
        )

        assertTrue(persisted)
        assertEquals("QQ 修改后的昵称", currentProfile.nickname)
        assertEquals(PlayerGender.FEMALE, currentProfile.gender)
        assertEquals(ProfilePlayPreference.SOLO, currentProfile.defaultPreference)
        assertEquals(1, currentProfile.usageCount)
        assertEquals(300L, currentProfile.lastUsedAtMillis)
        assertEquals(currentProfile, repository.writtenProfiles.single())
    }

    @Test
    fun cloudRestoreAppliesOnlyIndividuallyPersistedProfiles() = runBlocking {
        val repository = FakePlayerProfileRepository(failedIds = setOf("profile-2"))
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val profiles = listOf(profile("profile-1"), profile("profile-2"), profile("profile-3"))
        val applied = mutableListOf<PlayerProfile>()

        val result = coordinator.persistIndividually(
            profiles = profiles,
            onPersisted = applied::add
        )

        assertEquals(listOf("profile-1", "profile-3"), result.persistedProfiles.map { it.id })
        assertEquals(listOf("profile-2"), result.failedProfiles.map { it.id })
        assertTrue(result.skippedProfiles.isEmpty())
        assertEquals(listOf("profile-1", "profile-3"), applied.map { it.id })
        assertEquals(listOf("profile-1", "profile-2", "profile-3"), repository.writtenIds)
    }

    @Test
    fun staleCloudCandidateIsRecheckedInsidePersistenceLockAndSkipped() = runBlocking {
        val repository = FakePlayerProfileRepository()
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val cloudProfile = profile("cloud-profile")
        val currentProfiles = mutableListOf(
            profile("local-profile").copy(nickname = cloudProfile.nickname)
        )
        val applied = mutableListOf<PlayerProfile>()

        val result = coordinator.persistIndividually(
            profiles = listOf(cloudProfile),
            shouldPersist = { candidate ->
                currentProfiles.none {
                    it.nickname.equals(candidate.nickname, ignoreCase = true)
                }
            },
            onPersisted = applied::add
        )

        assertTrue(result.persistedProfiles.isEmpty())
        assertTrue(result.failedProfiles.isEmpty())
        assertEquals(listOf("cloud-profile"), result.skippedProfiles.map { it.id })
        assertTrue(repository.writtenIds.isEmpty())
        assertTrue(applied.isEmpty())
    }

    @Test
    fun alreadyAppliedCommandIsNotReadyForAcknowledgementWhenDurableWriteFails() = runBlocking {
        val repository = FakePlayerProfileRepository(failedIds = setOf("profile-1"))
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val profile = profile("profile-1")

        val result = coordinator.processCommand(
            command = matchingCommand(profile),
            currentProfiles = { listOf(profile) },
            nicknameConflictsWithQueue = { _, _ -> false },
            onPersisted = {}
        )

        assertEquals(PlayerProfileCommandPersistenceResult.PersistenceFailed, result)
        assertEquals(listOf("profile-1"), repository.writtenIds)
    }

    @Test
    fun alreadyAppliedCommandIsReadyOnlyAfterDurableWriteSucceeds() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakePlayerProfileRepository(events = events)
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val profile = profile("profile-1")

        val result = coordinator.processCommand(
            command = matchingCommand(profile),
            currentProfiles = { listOf(profile) },
            nicknameConflictsWithQueue = { _, _ -> false },
            onPersisted = { events += "apply:${it.id}" }
        )

        assertEquals(PlayerProfileCommandPersistenceResult.AlreadyApplied, result)
        assertEquals(listOf("persist:profile-1"), events)
    }

    @Test
    fun cloudAliasRemovesOldCopyWithoutMovingItsQqToTheCanonicalProfile() = runBlocking {
        val repository = FakePlayerProfileRepository()
        val coordinator = PlayerProfilePersistenceCoordinator(repository)
        val oldId = "00000000-0000-0000-0000-000000000901"
        val currentId = "00000000-0000-0000-0000-000000000902"
        val oldProfile = profile(oldId).copy(
            nickname = "同一玩家",
            qqNumber = "12345678",
            setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION
        )
        val currentProfile = profile(currentId).copy(
            nickname = "同一玩家",
            qqNumber = null,
            setupVersion = 0
        )

        val result = coordinator.reconcileCloudProfiles(
            cloudProfiles = listOf(currentProfile),
            profileAliases = mapOf(oldId to currentId),
            currentProfiles = { listOf(oldProfile, currentProfile) },
            nicknameConflictsWithQueue = { _, _, _ -> false }
        ) as CloudPlayerProfilePersistenceResult.Success

        assertEquals(listOf(currentId), result.profiles.map(PlayerProfile::id))
        assertEquals(null, result.profiles.single().qqNumber)
        assertEquals(mapOf(oldId to currentId), result.appliedAliases)
        assertEquals(result.profiles, repository.replacedProfiles)
    }

    private fun profile(id: String) = PlayerProfile(
        id = id,
        nickname = "玩家$id",
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
        qqNumber = "12345",
        createdAtMillis = 100L,
        updatedAtMillis = 100L
    )

    private fun matchingCommand(profile: PlayerProfile) = PlayerProfileUpdateCommand(
        commandId = "command-1",
        profileId = profile.id,
        qqNumber = profile.qqNumber.orEmpty(),
        expectedUpdatedAtMillis = profile.updatedAtMillis,
        nickname = profile.nickname,
        gender = profile.gender,
        defaultPreference = profile.defaultPreference
    )

    private class FakePlayerProfileRepository(
        private val failedIds: Set<String> = emptySet(),
        private val throwingIds: Set<String> = emptySet(),
        private val events: MutableList<String>? = null
    ) : PlayerProfileRepository {
        val writtenIds = mutableListOf<String>()
        val writtenProfiles = mutableListOf<PlayerProfile>()
        var replacedProfiles: List<PlayerProfile>? = null

        override suspend fun getProfiles(): List<PlayerProfile> = emptyList()

        override suspend fun upsertProfile(profile: PlayerProfile): PlayerProfile? {
            writtenIds += profile.id
            writtenProfiles += profile
            events?.add("persist:${profile.id}")
            if (profile.id in throwingIds) error("simulated write failure")
            return profile.takeIf { it.id !in failedIds }
        }

        override suspend fun replaceProfiles(profiles: List<PlayerProfile>): List<PlayerProfile>? {
            replacedProfiles = profiles
            return profiles
        }
    }
}
