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

internal sealed interface PlayerProfileDeletePersistenceResult {
    data object Applied : PlayerProfileDeletePersistenceResult
    data object AlreadyApplied : PlayerProfileDeletePersistenceResult
    data class Rejected(val detail: String) : PlayerProfileDeletePersistenceResult
    data object PersistenceFailed : PlayerProfileDeletePersistenceResult
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
        val persistedProfile = persist(profile) ?: return@withLock false
        onPersisted(persistedProfile)
        true
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
        val persistedProfile = persist(updatedProfile) ?: return@withLock false
        onPersisted(persistedProfile)
        true
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
            } else {
                val persistedProfile = persist(profile)
                if (persistedProfile != null) {
                    onPersisted(persistedProfile)
                    persistedProfiles += persistedProfile
                } else {
                    failedProfiles += profile
                }
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
        // "Has been shown the explanation" only ever turns on, wherever it was recorded. A copy
        // from the cloud never clears it here, and a cloud copy that has it sets it here even
        // when nothing else about the profile is newer.
        val introducedLocally = localProfiles
            .filter { it.pairedMachineQueueingIntroduced }
            .mapTo(mutableSetOf(), PlayerProfile::id)
        // The same for the explanation of letting others pass.
        val passingIntroducedLocally = localProfiles
            .filter { it.passingIntroduced }
            .mapTo(mutableSetOf(), PlayerProfile::id)
        @Suppress("NAME_SHADOWING")
        val cloudProfiles = cloudProfiles.map { cloudProfile ->
            val paired = cloudProfile.pairedMachineQueueingIntroduced || cloudProfile.id in introducedLocally
            val passing = cloudProfile.passingIntroduced || cloudProfile.id in passingIntroducedLocally
            if (
                paired != cloudProfile.pairedMachineQueueingIntroduced ||
                passing != cloudProfile.passingIntroduced
            ) {
                cloudProfile.copy(pairedMachineQueueingIntroduced = paired, passingIntroduced = passing)
            } else {
                cloudProfile
            }
        }
        val introducedInCloud = cloudProfiles
            .filter { it.pairedMachineQueueingIntroduced }
            .mapTo(mutableSetOf(), PlayerProfile::id)
        val passingIntroducedInCloud = cloudProfiles
            .filter { it.passingIntroduced }
            .mapTo(mutableSetOf(), PlayerProfile::id)
        val cloudById = cloudProfiles.associateBy(PlayerProfile::id)
        val validAliases = profileAliases.filter { (sourceId, targetId) ->
            sourceId != targetId && targetId in cloudById
        }
        val aliasSources = validAliases.keys
        val workingProfiles = localProfiles
            .filterNot { it.id in aliasSources }
            .map { localProfile ->
                val paired = localProfile.pairedMachineQueueingIntroduced || localProfile.id in introducedInCloud
                val passing = localProfile.passingIntroduced || localProfile.id in passingIntroducedInCloud
                if (
                    paired != localProfile.pairedMachineQueueingIntroduced ||
                    passing != localProfile.passingIntroduced
                ) {
                    localProfile.copy(pairedMachineQueueingIntroduced = paired, passingIntroduced = passing)
                } else {
                    localProfile
                }
            }
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
        val persistedProfiles = if (profilesChanged) {
            replaceAll(orderedProfiles)
                ?: return@withLock CloudPlayerProfilePersistenceResult.PersistenceFailed
        } else {
            orderedProfiles
        }
        val persistedById = persistedProfiles.associateBy(PlayerProfile::id)
        CloudPlayerProfilePersistenceResult.Success(
            profiles = persistedProfiles,
            appliedProfiles = appliedProfiles.mapNotNull { persistedById[it.id] },
            appliedAliases = appliedAliases,
            profilesChanged = persistedProfiles != localProfiles
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
                val persistedProfile = persist(decision.profile)
                if (persistedProfile != null) {
                    onPersisted(persistedProfile)
                    PlayerProfileCommandPersistenceResult.Applied(persistedProfile)
                } else {
                    PlayerProfileCommandPersistenceResult.PersistenceFailed
                }
            }

            PlayerProfileCommandDecision.AlreadyApplied -> {
                val currentProfile = currentProfiles().firstOrNull { it.id == command.profileId }
                val persistedProfile = currentProfile?.let { persist(it) }
                if (persistedProfile != null) {
                    if (persistedProfile != currentProfile) onPersisted(persistedProfile)
                    PlayerProfileCommandPersistenceResult.AlreadyApplied
                } else {
                    PlayerProfileCommandPersistenceResult.PersistenceFailed
                }
            }

            is PlayerProfileCommandDecision.Reject ->
                PlayerProfileCommandPersistenceResult.Rejected(decision.detail)
        }
    }

    suspend fun deleteProfile(
        profileId: String,
        currentProfiles: () -> List<PlayerProfile>
    ): PlayerProfileDeletePersistenceResult = writeMutex.withLock {
        if (currentProfiles().none { it.id == profileId }) {
            return@withLock PlayerProfileDeletePersistenceResult.AlreadyApplied
        }
        if (!repository.deleteProfile(profileId)) {
            return@withLock PlayerProfileDeletePersistenceResult.PersistenceFailed
        }
        PlayerProfileDeletePersistenceResult.Applied
    }

    private suspend fun persist(profile: PlayerProfile): PlayerProfile? = try {
        repository.upsertProfile(profile)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun replaceAll(profiles: List<PlayerProfile>): List<PlayerProfile>? = try {
        repository.replaceProfiles(profiles)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
