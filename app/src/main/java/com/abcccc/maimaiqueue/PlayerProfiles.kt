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
        get() = canonicalContact().isValid

    fun canonicalContact(): PlayerContact = canonicalPlayerContact(qqNumber, phoneNumber)

    fun withCanonicalContact(): PlayerProfile {
        val contact = canonicalContact()
        return if (qqNumber == contact.qqNumber && phoneNumber == contact.phoneNumber) {
            this
        } else {
            copy(qqNumber = contact.qqNumber, phoneNumber = contact.phoneNumber)
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
    phoneNumber: String? = null,
    createdAtMillis: Long = System.currentTimeMillis()
): PlayerProfile = PlayerProfile(
    id = UUID.randomUUID().toString(),
    nickname = nickname.trim(),
    gender = gender,
    defaultPreference = defaultPreference,
    qqNumber = qqNumber,
    phoneNumber = phoneNumber,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = createdAtMillis
).withCanonicalContact()

data class PlayerContact(
    val qqNumber: String? = null,
    val phoneNumber: String? = null
) {
    val isPresent: Boolean
        get() = qqNumber != null || phoneNumber != null

    val isValid: Boolean
        get() = isPresent && isValidQqNumber(qqNumber) && isValidPhoneNumber(phoneNumber)
}

fun canonicalPlayerContact(qqNumber: String?, phoneNumber: String?): PlayerContact {
    val normalizedQq = normalizeOptionalContact(qqNumber)
    val normalizedPhone = normalizeOptionalContact(phoneNumber)
    return if (normalizedQq != null) {
        PlayerContact(qqNumber = normalizedQq)
    } else {
        PlayerContact(phoneNumber = normalizedPhone)
    }
}

fun playerContactFromInput(value: String, allowPhoneNumber: Boolean): PlayerContact {
    val normalized = normalizeOptionalContact(value)
    return if (allowPhoneNumber && isMainlandChinaMobileNumber(normalized)) {
        PlayerContact(phoneNumber = normalized)
    } else {
        PlayerContact(qqNumber = normalized)
    }
}

fun isValidQqNumber(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.isEmpty() ||
        (normalized.length in QQ_NUMBER_LENGTH_RANGE && normalized.all { it in '0'..'9' })
}

fun isValidPhoneNumber(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.isEmpty() || isMainlandChinaMobileNumber(normalized)
}

fun isMainlandChinaMobileNumber(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.length == MAX_PHONE_NUMBER_LENGTH &&
        normalized[0] == '1' && normalized[1] in '3'..'9' &&
        normalized.all { it in '0'..'9' }
}

fun normalizeOptionalContact(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

const val MAX_QQ_NUMBER_LENGTH = 12
const val MAX_PHONE_NUMBER_LENGTH = 11
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
