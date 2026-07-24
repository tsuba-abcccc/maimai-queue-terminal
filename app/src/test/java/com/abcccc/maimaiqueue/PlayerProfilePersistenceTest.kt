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

        override suspend fun getProfiles(): List<PlayerProfile> = emptyList()

        override suspend fun upsertProfile(profile: PlayerProfile): Boolean {
            writtenIds += profile.id
            events?.add("persist:${profile.id}")
            if (profile.id in throwingIds) error("simulated write failure")
            return profile.id !in failedIds
        }
    }
}
