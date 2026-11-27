package com.abcccc.maimaiqueue

import java.text.Collator
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln1p

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
    val publicPlayerId: String? = null,
    val publicPlayerIdAliases: Set<String> = emptySet(),
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference,
    val qqNumber: String? = null,
    val avatarReference: String? = null,
    val usageCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val recentUsageAtMillis: List<Long> = emptyList(),
    val qqVisibility: QqVisibility = QqVisibility.TERMINAL_ONLY,
    val notificationPreferences: QueueNotificationPreferences =
        QueueNotificationPreferences(),
    val webAccountBound: Boolean = false,
    val terminalEditingAllowed: Boolean = true,
    val visitedVenuesPublic: Boolean = true,
    val webProfileRevision: Long = 0L,
    val setupVersion: Int = 0,
    val revision: Long = 1L,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    /**
     * Whether the player has been shown the explanation of letting the system arrange a
     * registration between two machines. A record, not a setting: it only ever turns on, and
     * all it decides is whether the explanation opens by itself the next time.
     */
    val pairedMachineQueueingIntroduced: Boolean = false,
    /**
     * The player's last answer to letting the registrations behind pass a 单人游玩 registration
     * of this profile (solo-pass-plan-2026-11.md). The tick box starts from it the next time
     * (maintainer, 2026-11-26: "如果是玩家资料的话，这个勾选框记忆上次状态"). It changes whenever
     * the player answers, when joining or later in 更改游玩偏好, on the terminal or the website.
     */
    val lastAllowsPassing: Boolean = false,
    /**
     * Whether the player has been shown the explanation of letting others pass (a 单人游玩
     * registration or a fixed pair). A record like [pairedMachineQueueingIntroduced]: it only
     * ever turns on and decides whether the explanation opens by itself the next time.
     */
    val passingIntroduced: Boolean = false
) {
    val hasValidContact: Boolean
        get() = normalizedQqNumber() != null && isValidQqNumber(normalizedQqNumber())

    val hasCompleteRequiredDetails: Boolean
        get() = setupVersion >= CURRENT_PLAYER_PROFILE_SETUP_VERSION

    fun normalizedQqNumber(): String? = normalizeOptionalContact(qqNumber)

    fun withCanonicalContact(): PlayerProfile {
        val normalizedQqNumber = normalizedQqNumber()
        val normalizedPublicPlayerIdAliases = publicPlayerIdAliases
            .asSequence()
            .filter(::isValidPublicPlayerId)
            .filterNot { it == publicPlayerId }
            .toSet()
        return if (
            qqNumber == normalizedQqNumber &&
            publicPlayerIdAliases == normalizedPublicPlayerIdAliases
        ) {
            this
        } else {
            copy(
                qqNumber = normalizedQqNumber,
                publicPlayerIdAliases = normalizedPublicPlayerIdAliases
            )
        }
    }

    fun recordUsage(
        atMillis: Long = System.currentTimeMillis(),
        preferenceToRemember: PlayPreference? = null,
        /** The explanation was shown where this registration was created, see the field. */
        pairedMachineQueueingIntroducedNow: Boolean = false,
        /** The answer to the passing tick box, where it was offered; null keeps the last one. */
        passingChoice: Boolean? = null
    ): PlayerProfile = copy(
        defaultPreference = preferenceToRemember?.toProfilePlayPreference() ?: defaultPreference,
        lastAllowsPassing = passingChoice ?: lastAllowsPassing,
        // The box was ticked where the registration was created, and the explanation shown there.
        passingIntroduced = passingIntroduced || passingChoice == true,
        pairedMachineQueueingIntroduced =
            pairedMachineQueueingIntroduced || pairedMachineQueueingIntroducedNow,
        usageCount = usageCount + 1,
        lastUsedAtMillis = atMillis,
        recentUsageAtMillis = normalizeRecentUsageTimestamps(
            recentUsageAtMillis + listOfNotNull(lastUsedAtMillis) + atMillis,
            lastUsedAtMillis = atMillis
        ),
        revision = revision + 1L,
        updatedAtMillis = atMillis
    )
}

/** Remembers the answer to the passing tick box given after joining; see [PlayerProfile.lastAllowsPassing]. */
internal fun PlayerProfile.withPassingChoice(enabled: Boolean, atMillis: Long): PlayerProfile =
    if (lastAllowsPassing == enabled && (passingIntroduced || !enabled)) {
        this
    } else {
        copy(
            lastAllowsPassing = enabled,
            // Wherever the box was ticked, the explanation was shown with it.
            passingIntroduced = passingIntroduced || enabled,
            revision = revision + 1L,
            updatedAtMillis = maxOf(atMillis, updatedAtMillis + 1L)
        )
    }

/** Records that the explanation of letting others pass was shown; see [PlayerProfile.passingIntroduced]. */
internal fun PlayerProfile.withPassingIntroduced(atMillis: Long): PlayerProfile =
    if (passingIntroduced) {
        this
    } else {
        copy(
            passingIntroduced = true,
            revision = revision + 1L,
            updatedAtMillis = maxOf(atMillis, updatedAtMillis + 1L)
        )
    }

internal const val MAX_RECENT_PLAYER_USAGE_EVENTS = 16

internal fun normalizeRecentUsageTimestamps(
    timestamps: Iterable<Long>,
    lastUsedAtMillis: Long? = null
): List<Long> = buildList {
    addAll(timestamps.filter { it > 0L })
    lastUsedAtMillis?.takeIf { it > 0L }?.let(::add)
}.asSequence()
    .distinct()
    .sortedDescending()
    .take(MAX_RECENT_PLAYER_USAGE_EVENTS)
    .sorted()
    .toList()

val PlayerProfile.canEditOnTerminal: Boolean
    get() = !webAccountBound || terminalEditingAllowed

/**
 * Whether the profile must be completed in the editor before it can be used to create or claim
 * a registration.
 */
fun PlayerProfile.needsCompletionBeforeUse(): Boolean =
    !hasValidContact || !hasCompleteRequiredDetails

internal fun normalizePlayerAvatarReference(value: String?): String? {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (normalized.length > 512) return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
    return uri.toASCIIString()
}

fun createPlayerProfile(
    nickname: String,
    gender: PlayerGender,
    defaultPreference: ProfilePlayPreference,
    qqNumber: String? = null,
    qqVisibility: QqVisibility = QqVisibility.PUBLIC_WEBSITE,
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

fun isValidPublicPlayerId(value: String?): Boolean =
    value != null && value.length == PUBLIC_PLAYER_ID_LENGTH && value.all { it in '0'..'9' }

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

/**
 * Whether a registration of somebody else already uses [nickname]. Only a registration made from
 * this profile is the profile's own: a temporary registration has no profile, and neither has a
 * profile that is still being created, so the two must not be taken for the same player.
 */
internal fun nicknameConflictsWithRegistrations(
    nickname: String,
    registrations: List<Registration>,
    profileId: String?,
    equivalentProfileIds: Set<String> = emptySet(),
    exceptRegistrationKey: Int? = null
): Boolean {
    val normalizedNickname = nickname.trim()
    if (normalizedNickname.isBlank()) return false
    return registrations.any { registration ->
        val registrationProfileId = registration.playerProfileId
        val ownRegistration = registrationProfileId != null && (
            registrationProfileId == profileId || registrationProfileId in equivalentProfileIds
            )
        registration.key != exceptRegistrationKey &&
            !ownRegistration &&
            registration.displayId.trim().equals(normalizedNickname, ignoreCase = true)
    }
}

/**
 * The registration that keeps [profile] from being used for another one: a registration made
 * from it, or one under its nickname.
 */
internal fun registrationOfPlayerProfile(
    profile: PlayerProfile,
    registrations: List<Registration>,
    exceptKey: Int? = null
): Registration? = registrations.firstOrNull {
    it.key != exceptKey && (
        it.playerProfileId == profile.id ||
            it.displayId.equals(profile.nickname.trim(), ignoreCase = true)
        )
}

/** The line of a player profile in the library that cannot be chosen: where it is queueing. */
internal fun queuedPlayerProfileLine(positionLabel: String?): String =
    if (positionLabel == null) "排队中" else "排队中·$positionLabel"

internal fun shouldApplyCloudPlayerProfile(
    cloudProfile: PlayerProfile,
    localProfiles: List<PlayerProfile>,
    nicknameConflictsWithQueue: (nickname: String, profileId: String) -> Boolean
): Boolean {
    val localSameId = localProfiles.firstOrNull { it.id == cloudProfile.id }
    if (localSameId != null && cloudProfile.revision <= localSameId.revision) {
        return cloudProfile.revision == localSameId.revision &&
            cloudProfile.publicPlayerId != null &&
            (
                cloudProfile.publicPlayerId != localSameId.publicPlayerId ||
                    cloudProfile.publicPlayerIdAliases != localSameId.publicPlayerIdAliases
                ) &&
            localSameId.copy(
                publicPlayerId = cloudProfile.publicPlayerId,
                publicPlayerIdAliases = cloudProfile.publicPlayerIdAliases
            ) == cloudProfile
    }
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
const val PUBLIC_PLAYER_ID_LENGTH = 6
private val QQ_NUMBER_LENGTH_RANGE = 5..MAX_QQ_NUMBER_LENGTH

fun filterAndSortPlayerProfiles(
    profiles: List<PlayerProfile>,
    query: String,
    sortMode: ProfileSortMode,
    nowMillis: Long = System.currentTimeMillis()
): List<PlayerProfile> {
    val normalizedQuery = query.trim()
    val filtered = if (normalizedQuery.isEmpty()) {
        profiles
    } else {
        profiles.filter { profile ->
            profile.nickname.contains(normalizedQuery, ignoreCase = true) ||
                profile.normalizedQqNumber()?.contains(normalizedQuery) == true ||
                profile.publicPlayerId?.contains(normalizedQuery) == true ||
                profile.publicPlayerIdAliases.any { it.contains(normalizedQuery) }
        }
    }
    val nicknameComparator = Comparator<PlayerProfile> { first, second ->
        chineseNicknameCollator.compare(first.nickname, second.nickname)
            .takeIf { it != 0 }
            ?: first.id.compareTo(second.id)
    }
    val modeComparator = when (sortMode) {
        ProfileSortMode.RECOMMENDED ->
            compareByDescending<PlayerProfile> { playerProfileRecommendationScore(it, nowMillis) }
                .thenByDescending { it.lastUsedAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.createdAtMillis }
                .then(nicknameComparator)
        ProfileSortMode.ALPHABETICAL -> nicknameComparator
    }
    val comparator = if (normalizedQuery.isEmpty()) {
        modeComparator
    } else {
        compareBy<PlayerProfile> { playerProfileSearchMatchRank(it, normalizedQuery) }
            .then(modeComparator)
    }
    return filtered.sortedWith(comparator)
}

internal fun playerProfileRecommendationScore(
    profile: PlayerProfile,
    nowMillis: Long
): Double {
    val recentUsageScore = normalizeRecentUsageTimestamps(
        profile.recentUsageAtMillis,
        profile.lastUsedAtMillis
    ).sumOf { usedAtMillis ->
        val ageMillis = elapsedMillis(nowMillis, usedAtMillis)
        if (ageMillis > RECENT_USAGE_WINDOW_MILLIS) {
            0.0
        } else {
            RECENT_USAGE_EVENT_WEIGHT * exp(
                -DECAY_PER_MILLI * ageMillis / RECENT_USAGE_HALF_LIFE_MILLIS
            )
        }
    }
    val profileAgeMillis = elapsedMillis(nowMillis, profile.createdAtMillis)
    val newProfileScore = if (profileAgeMillis < NEW_PROFILE_BOOST_WINDOW_MILLIS) {
        NEW_PROFILE_MAX_BOOST *
            (1.0 - profileAgeMillis.toDouble() / NEW_PROFILE_BOOST_WINDOW_MILLIS)
    } else {
        0.0
    }
    val lifetimeScore = minOf(
        LIFETIME_USAGE_MAX_SCORE,
        ln1p(profile.usageCount.coerceAtLeast(0).toDouble()) * LIFETIME_USAGE_LOG_WEIGHT
    )
    val lastUsageScore = profile.lastUsedAtMillis?.let { lastUsedAtMillis ->
        LAST_USAGE_MAX_SCORE * exp(
            -DECAY_PER_MILLI * elapsedMillis(nowMillis, lastUsedAtMillis) /
                LAST_USAGE_HALF_LIFE_MILLIS
        )
    } ?: 0.0
    return recentUsageScore + newProfileScore + lifetimeScore + lastUsageScore
}

private fun elapsedMillis(nowMillis: Long, eventAtMillis: Long): Double =
    (nowMillis - eventAtMillis).coerceAtLeast(0L).toDouble()

private fun playerProfileSearchMatchRank(profile: PlayerProfile, query: String): Int {
    val nickname = profile.nickname
    val identifiers = buildList {
        profile.normalizedQqNumber()?.let(::add)
        profile.publicPlayerId?.let(::add)
        addAll(profile.publicPlayerIdAliases)
    }
    return when {
        nickname.equals(query, ignoreCase = true) || identifiers.any { it == query } -> 0
        nickname.startsWith(query, ignoreCase = true) || identifiers.any {
            it.startsWith(query)
        } -> 1
        nickname.contains(query, ignoreCase = true) -> 2
        else -> 3
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
private const val RECENT_USAGE_WINDOW_MILLIS = 90L * MILLIS_PER_DAY
private const val RECENT_USAGE_HALF_LIFE_MILLIS = 14.0 * MILLIS_PER_DAY
private const val RECENT_USAGE_EVENT_WEIGHT = 36.0
private const val NEW_PROFILE_BOOST_WINDOW_MILLIS = 14L * MILLIS_PER_DAY
private const val NEW_PROFILE_MAX_BOOST = 72.0
private const val LIFETIME_USAGE_LOG_WEIGHT = 6.0
private const val LIFETIME_USAGE_MAX_SCORE = 28.0
private const val LAST_USAGE_HALF_LIFE_MILLIS = 21.0 * MILLIS_PER_DAY
private const val LAST_USAGE_MAX_SCORE = 16.0
private const val DECAY_PER_MILLI = 0.6931471805599453

fun ProfilePlayPreference.toPlayPreferenceOrNull(): PlayPreference? = when (this) {
    ProfilePlayPreference.SOLO -> PlayPreference.SOLO
    ProfilePlayPreference.OPEN_TO_JOIN -> PlayPreference.OPEN_TO_JOIN
    ProfilePlayPreference.ASK_EVERY_TIME -> null
}

fun PlayPreference.toProfilePlayPreference(): ProfilePlayPreference = when (this) {
    PlayPreference.SOLO -> ProfilePlayPreference.SOLO
    PlayPreference.OPEN_TO_JOIN -> ProfilePlayPreference.OPEN_TO_JOIN
}
