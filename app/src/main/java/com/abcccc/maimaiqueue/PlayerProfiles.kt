package com.abcccc.maimaiqueue

import java.text.Collator
import java.util.Locale
import java.util.UUID

private val chineseNicknameCollator: Collator = Collator.getInstance(Locale.CHINA).apply {
    strength = Collator.PRIMARY
}

enum class ProfilePlayPreference {
    SOLO,
    OPEN_TO_JOIN,
    ASK_EVERY_TIME
}

enum class ProfileSortMode {
    RECOMMENDED,
    ALPHABETICAL
}

enum class QqVisibility {
    TERMINAL_ONLY,
    PUBLIC_WEBSITE
}

data class QueueNotificationPreferences(
    val enabled: Boolean = true,
    val queueChanges: Boolean = true,
    val playingPosition: Boolean = false,
    val onlineCheckIn: Boolean = true,
    val absence: Boolean = true,
    val machineStatus: Boolean = false
)

const val CURRENT_PLAYER_PROFILE_SETUP_VERSION = 1

data class PlayerProfile(
    val id: String,
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference,
    val qqNumber: String? = null,
    val avatarReference: String? = null,
    val usageCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val qqVisibility: QqVisibility = QqVisibility.TERMINAL_ONLY,
    val notificationPreferences: QueueNotificationPreferences =
        QueueNotificationPreferences(),
    val setupVersion: Int = 0,
    val revision: Long = 1L,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis
) {
    val hasValidContact: Boolean
        get() = normalizedQqNumber() != null && isValidQqNumber(normalizedQqNumber())

    val hasCompleteRequiredDetails: Boolean
        get() = setupVersion >= CURRENT_PLAYER_PROFILE_SETUP_VERSION

    fun normalizedQqNumber(): String? = normalizeOptionalContact(qqNumber)

    fun withCanonicalContact(): PlayerProfile {
        val normalizedQqNumber = normalizedQqNumber()
        return if (qqNumber == normalizedQqNumber) {
            this
        } else {
            copy(qqNumber = normalizedQqNumber)
        }
    }

    fun recordUsage(
        atMillis: Long = System.currentTimeMillis(),
        preferenceToRemember: PlayPreference? = null
    ): PlayerProfile = copy(
        defaultPreference = preferenceToRemember?.toProfilePlayPreference() ?: defaultPreference,
        usageCount = usageCount + 1,
        lastUsedAtMillis = atMillis,
        revision = revision + 1L,
        updatedAtMillis = atMillis
    )
}

fun createPlayerProfile(
    nickname: String,
    gender: PlayerGender,
    defaultPreference: ProfilePlayPreference,
    qqNumber: String? = null,
    qqVisibility: QqVisibility = QqVisibility.TERMINAL_ONLY,
    notificationPreferences: QueueNotificationPreferences = QueueNotificationPreferences(),
    setupVersion: Int = CURRENT_PLAYER_PROFILE_SETUP_VERSION,
    createdAtMillis: Long = System.currentTimeMillis()
): PlayerProfile = PlayerProfile(
    id = UUID.randomUUID().toString(),
    nickname = nickname.trim(),
    gender = gender,
    defaultPreference = defaultPreference,
    qqNumber = qqNumber,
    qqVisibility = qqVisibility,
    notificationPreferences = notificationPreferences,
    setupVersion = setupVersion,
    revision = 1L,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = createdAtMillis
).withCanonicalContact()

fun isValidQqNumber(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.isEmpty() ||
        (normalized.length in QQ_NUMBER_LENGTH_RANGE && normalized.all { it in '0'..'9' })
}

fun normalizeOptionalContact(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

internal fun clearAmbiguousQqBindings(
    profiles: List<PlayerProfile>,
    migratedAtMillis: Long = System.currentTimeMillis()
): List<PlayerProfile> {
    val duplicateQqNumbers = profiles.asSequence()
        .mapNotNull(PlayerProfile::normalizedQqNumber)
        .groupingBy { it }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    if (duplicateQqNumbers.isEmpty()) return profiles
    return profiles.map { profile ->
        if (profile.normalizedQqNumber() in duplicateQqNumbers) {
            profile.copy(
                qqNumber = null,
                revision = profile.revision + 1L,
                updatedAtMillis = maxOf(profile.updatedAtMillis, migratedAtMillis)
            )
        } else {
            profile
        }
    }
}

internal fun shouldApplyCloudPlayerProfile(
    cloudProfile: PlayerProfile,
    localProfiles: List<PlayerProfile>,
    nicknameConflictsWithQueue: (nickname: String, profileId: String) -> Boolean
): Boolean {
    val localSameId = localProfiles.firstOrNull { it.id == cloudProfile.id }
    if (localSameId != null && cloudProfile.revision <= localSameId.revision) return false
    val cloudQq = cloudProfile.normalizedQqNumber()
    if (localProfiles.any { local ->
            local.id != cloudProfile.id && (
                local.nickname.equals(cloudProfile.nickname, ignoreCase = true) ||
                    (cloudQq != null && local.normalizedQqNumber() == cloudQq)
                )
        }
    ) return false
    return !nicknameConflictsWithQueue(cloudProfile.nickname, cloudProfile.id)
}

internal fun PlayerProfile.isContactlessLegacyAliasOf(canonical: PlayerProfile): Boolean =
    id != canonical.id &&
        !hasValidContact &&
        !hasCompleteRequiredDetails &&
        canonical.hasValidContact &&
        nickname.equals(canonical.nickname, ignoreCase = true) &&
        gender == canonical.gender &&
        defaultPreference == canonical.defaultPreference

const val MAX_QQ_NUMBER_LENGTH = 12
private val QQ_NUMBER_LENGTH_RANGE = 5..MAX_QQ_NUMBER_LENGTH

fun filterAndSortPlayerProfiles(
    profiles: List<PlayerProfile>,
    query: String,
    sortMode: ProfileSortMode
): List<PlayerProfile> {
    val normalizedQuery = query.trim()
    val filtered = if (normalizedQuery.isEmpty()) {
        profiles
    } else {
        profiles.filter { profile ->
            profile.nickname.contains(normalizedQuery, ignoreCase = true) ||
                profile.normalizedQqNumber()?.contains(normalizedQuery) == true
        }
    }
    val nicknameComparator = Comparator<PlayerProfile> { first, second ->
        chineseNicknameCollator.compare(first.nickname, second.nickname)
    }
    return when (sortMode) {
        ProfileSortMode.RECOMMENDED -> filtered.sortedWith(
            compareByDescending<PlayerProfile> { it.usageCount }
                .thenByDescending { it.lastUsedAtMillis ?: Long.MIN_VALUE }
                .then(nicknameComparator)
        )
        ProfileSortMode.ALPHABETICAL -> filtered.sortedWith(nicknameComparator)
    }
}

fun ProfilePlayPreference.toPlayPreferenceOrNull(): PlayPreference? = when (this) {
    ProfilePlayPreference.SOLO -> PlayPreference.SOLO
    ProfilePlayPreference.OPEN_TO_JOIN -> PlayPreference.OPEN_TO_JOIN
    ProfilePlayPreference.ASK_EVERY_TIME -> null
}

fun PlayPreference.toProfilePlayPreference(): ProfilePlayPreference = when (this) {
    PlayPreference.SOLO -> ProfilePlayPreference.SOLO
    PlayPreference.OPEN_TO_JOIN -> ProfilePlayPreference.OPEN_TO_JOIN
}
