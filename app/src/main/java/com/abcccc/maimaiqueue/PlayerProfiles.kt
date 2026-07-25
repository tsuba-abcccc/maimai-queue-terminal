package com.abcccc.maimaiqueue

import java.text.Collator
import java.util.Locale
import java.util.UUID

private val chineseNicknameCollator: Collator = Collator.getInstance(Locale.CHINA).apply {
    strength = Collator.PRIMARY
}

enum class PlayerGender {
    MALE,
    FEMALE,
    UNDISCLOSED
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

data class PlayerProfile(
    val id: String,
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference,
    val qqNumber: String? = null,
    val avatarReference: String? = null,
    val usageCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis
) {
    val hasValidContact: Boolean
        get() = normalizedQqNumber() != null && isValidQqNumber(normalizedQqNumber())

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
        updatedAtMillis = atMillis
    )
}

fun createPlayerProfile(
    nickname: String,
    gender: PlayerGender,
    defaultPreference: ProfilePlayPreference,
    qqNumber: String? = null,
    createdAtMillis: Long = System.currentTimeMillis()
): PlayerProfile = PlayerProfile(
    id = UUID.randomUUID().toString(),
    nickname = nickname.trim(),
    gender = gender,
    defaultPreference = defaultPreference,
    qqNumber = qqNumber,
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
                updatedAtMillis = maxOf(profile.updatedAtMillis, migratedAtMillis)
            )
        } else {
            profile
        }
    }
}

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
