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
    val phoneNumber: String? = null,
    val avatarReference: String? = null,
    val usageCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis
) {
    val hasValidContact: Boolean
        get() = hasPlayerContact(qqNumber, phoneNumber) &&
            isValidQqNumber(qqNumber) &&
            isValidPhoneNumber(phoneNumber)

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
    phoneNumber: String? = null,
    createdAtMillis: Long = System.currentTimeMillis()
): PlayerProfile = PlayerProfile(
    id = UUID.randomUUID().toString(),
    nickname = nickname.trim(),
    gender = gender,
    defaultPreference = defaultPreference,
    qqNumber = normalizeOptionalContact(qqNumber),
    phoneNumber = normalizeOptionalContact(phoneNumber),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = createdAtMillis
)

fun hasPlayerContact(qqNumber: String?, phoneNumber: String?): Boolean =
    !qqNumber.isNullOrBlank() || !phoneNumber.isNullOrBlank()

fun isValidQqNumber(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.isEmpty() ||
        (normalized.length in QQ_NUMBER_LENGTH_RANGE && normalized.all { it in '0'..'9' })
}

fun isValidPhoneNumber(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return true
    if (normalized.length > MAX_PHONE_NUMBER_LENGTH) return false
    if (normalized.any { it !in PHONE_NUMBER_CHARACTERS }) return false
    if ('+' in normalized && (normalized.first() != '+' || normalized.count { it == '+' } > 1)) {
        return false
    }
    return normalized.count { it in '0'..'9' } in PHONE_DIGIT_COUNT_RANGE
}

fun normalizeOptionalContact(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

const val MAX_QQ_NUMBER_LENGTH = 12
const val MAX_PHONE_NUMBER_LENGTH = 20
private val QQ_NUMBER_LENGTH_RANGE = 5..MAX_QQ_NUMBER_LENGTH
private val PHONE_DIGIT_COUNT_RANGE = 7..15
private val PHONE_NUMBER_CHARACTERS = ('0'..'9').toSet() + setOf('+', '-', ' ', '(', ')')

fun filterAndSortPlayerProfiles(
    profiles: List<PlayerProfile>,
    query: String,
    sortMode: ProfileSortMode
): List<PlayerProfile> {
    val normalizedQuery = query.trim()
    val filtered = if (normalizedQuery.isEmpty()) {
        profiles
    } else {
        profiles.filter { it.nickname.contains(normalizedQuery, ignoreCase = true) }
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
