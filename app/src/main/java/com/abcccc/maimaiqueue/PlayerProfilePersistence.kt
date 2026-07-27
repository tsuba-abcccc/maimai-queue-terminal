package com.abcccc.maimaiqueue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlayerProfilePersistenceResult(
    val persistedProfiles: List<PlayerProfile>,
    val failedProfiles: List<PlayerProfile>,
    val skippedProfiles: List<PlayerProfile>
)

internal sealed interface PlayerProfileCommandPersistenceResult {
    data class Applied(val profile: PlayerProfile) : PlayerProfileCommandPersistenceResult
    data object AlreadyApplied : PlayerProfileCommandPersistenceResult
    data class Rejected(val detail: String) : PlayerProfileCommandPersistenceResult
    data object PersistenceFailed : PlayerProfileCommandPersistenceResult
}

internal sealed interface CloudPlayerProfilePersistenceResult {
    data class Success(
        val profiles: List<PlayerProfile>,
        val appliedProfiles: List<PlayerProfile>,
        val appliedAliases: Map<String, String>,
        val profilesChanged: Boolean
    ) : CloudPlayerProfilePersistenceResult

    data object PersistenceFailed : CloudPlayerProfilePersistenceResult
}

internal class PlayerProfilePersistenceCoordinator(
    private val repository: PlayerProfileRepository
) {
    private val writeMutex = Mutex()

    suspend fun persistAndApply(
        profile: PlayerProfile,
        onPersisted: (PlayerProfile) -> Unit = {}
    ): Boolean = writeMutex.withLock {
        val persisted = persist(profile)
        if (persisted) onPersisted(profile)
        persisted
    }

    suspend fun mutateAndApply(
        profileId: String,
        currentProfiles: () -> List<PlayerProfile>,
        mutation: (PlayerProfile) -> PlayerProfile,
        onPersisted: (PlayerProfile) -> Unit = {}
    ): Boolean = writeMutex.withLock {
        val currentProfile = currentProfiles().firstOrNull { it.id == profileId }
            ?: return@withLock false
        val updatedProfile = mutation(currentProfile)
        val persisted = persist(updatedProfile)
        if (persisted) onPersisted(updatedProfile)
        persisted
    }

    suspend fun persistIndividually(
        profiles: List<PlayerProfile>,
        shouldPersist: (PlayerProfile) -> Boolean = { true },
        onPersisted: (PlayerProfile) -> Unit = {}
    ): PlayerProfilePersistenceResult = writeMutex.withLock {
        val persistedProfiles = mutableListOf<PlayerProfile>()
        val failedProfiles = mutableListOf<PlayerProfile>()
        val skippedProfiles = mutableListOf<PlayerProfile>()
        profiles.forEach { profile ->
            if (!shouldPersist(profile)) {
                skippedProfiles += profile
            } else if (persist(profile)) {
                onPersisted(profile)
                persistedProfiles += profile
            } else {
                failedProfiles += profile
            }
        }
        PlayerProfilePersistenceResult(
            persistedProfiles = persistedProfiles,
            failedProfiles = failedProfiles,
            skippedProfiles = skippedProfiles
        )
    }

    suspend fun reconcileCloudProfiles(
        cloudProfiles: List<PlayerProfile>,
        profileAliases: Map<String, String>,
        currentProfiles: () -> List<PlayerProfile>,
        nicknameConflictsWithQueue: (
            nickname: String,
            profileId: String,
            equivalentProfileIds: Set<String>
        ) -> Boolean
    ): CloudPlayerProfilePersistenceResult = writeMutex.withLock {
        val localProfiles = currentProfiles()
        val cloudById = cloudProfiles.associateBy(PlayerProfile::id)
        val validAliases = profileAliases.filter { (sourceId, targetId) ->
            sourceId != targetId && targetId in cloudById
        }
        val aliasSources = validAliases.keys
        val workingProfiles = localProfiles
            .filterNot { it.id in aliasSources }
            .toMutableList()
        val appliedProfiles = mutableListOf<PlayerProfile>()

        cloudProfiles.forEach { cloudProfile ->
            val equivalentIds = validAliases
                .filterValues { it == cloudProfile.id }
                .keys
            if (shouldApplyCloudPlayerProfile(
                    cloudProfile = cloudProfile,
                    localProfiles = workingProfiles,
                    nicknameConflictsWithQueue = { nickname, profileId ->
                        nicknameConflictsWithQueue(nickname, profileId, equivalentIds)
                    }
                )
            ) {
                val existingIndex = workingProfiles.indexOfFirst { it.id == cloudProfile.id }
                if (existingIndex >= 0) {
                    workingProfiles[existingIndex] = cloudProfile
                } else {
                    workingProfiles += cloudProfile
                }
                appliedProfiles += cloudProfile
            }
        }

        val persistedIds = workingProfiles.mapTo(mutableSetOf(), PlayerProfile::id)
        val appliedAliases = validAliases.filterValues { it in persistedIds }
        val unappliedAliasSources = aliasSources - appliedAliases.keys
        val workingById = workingProfiles.associateBy(PlayerProfile::id).toMutableMap()
        localProfiles
            .filter { it.id in unappliedAliasSources }
            .forEach { workingById[it.id] = it }
        val orderedProfiles = buildList {
            localProfiles.forEach { localProfile ->
                workingById.remove(localProfile.id)?.let(::add)
            }
            workingProfiles.forEach { profile ->
                workingById.remove(profile.id)?.let(::add)
            }
        }
        val profilesChanged = orderedProfiles != localProfiles
        if (profilesChanged && !replaceAll(orderedProfiles)) {
            return@withLock CloudPlayerProfilePersistenceResult.PersistenceFailed
        }
        CloudPlayerProfilePersistenceResult.Success(
            profiles = orderedProfiles,
            appliedProfiles = appliedProfiles,
            appliedAliases = appliedAliases,
            profilesChanged = profilesChanged
        )
    }

    suspend fun processCommand(
        command: PlayerProfileUpdateCommand,
        currentProfiles: () -> List<PlayerProfile>,
        nicknameConflictsWithQueue: (nickname: String, profileId: String) -> Boolean,
        onPersisted: (PlayerProfile) -> Unit
    ): PlayerProfileCommandPersistenceResult = writeMutex.withLock {
        when (val decision = decidePlayerProfileUpdate(
            command = command,
            profiles = currentProfiles(),
            nicknameConflictsWithQueue = nicknameConflictsWithQueue
        )) {
            is PlayerProfileCommandDecision.Apply -> {
                if (persist(decision.profile)) {
                    onPersisted(decision.profile)
                    PlayerProfileCommandPersistenceResult.Applied(decision.profile)
                } else {
                    PlayerProfileCommandPersistenceResult.PersistenceFailed
                }
            }

            PlayerProfileCommandDecision.AlreadyApplied -> {
                val currentProfile = currentProfiles().firstOrNull { it.id == command.profileId }
                if (currentProfile != null && persist(currentProfile)) {
                    PlayerProfileCommandPersistenceResult.AlreadyApplied
                } else {
                    PlayerProfileCommandPersistenceResult.PersistenceFailed
                }
            }

            is PlayerProfileCommandDecision.Reject ->
                PlayerProfileCommandPersistenceResult.Rejected(decision.detail)
        }
    }

    private suspend fun persist(profile: PlayerProfile): Boolean = try {
        repository.upsertProfile(profile)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun replaceAll(profiles: List<PlayerProfile>): Boolean = try {
        repository.replaceProfiles(profiles)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
