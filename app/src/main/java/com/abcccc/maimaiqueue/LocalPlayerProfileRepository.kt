package com.abcccc.maimaiqueue

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface PlayerProfileRepository {
    suspend fun getProfiles(): List<PlayerProfile>
    suspend fun upsertProfile(profile: PlayerProfile): PlayerProfile?
    suspend fun replaceProfiles(profiles: List<PlayerProfile>): List<PlayerProfile>?
}

class LocalPlayerProfileRepository(context: Context) : PlayerProfileRepository {
    private val writeMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "player_profiles",
        Context.MODE_PRIVATE
    )

    override suspend fun getProfiles(): List<PlayerProfile> = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            clearAmbiguousQqBindings(
                assignMissingPublicPlayerIds(loadProfiles())
            ).also { profiles ->
                if (profiles.isNotEmpty()) saveProfiles(profiles)
            }
        }
    }

    override suspend fun upsertProfile(
        profile: PlayerProfile
    ): PlayerProfile? = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val profiles = assignMissingPublicPlayerIds(loadProfiles()).toMutableList()
            val canonicalProfile = ensurePublicPlayerId(
                profile.withCanonicalContact(),
                profiles
            )
            val existingIndex = profiles.indexOfFirst { it.id == canonicalProfile.id }
            if (existingIndex >= 0) {
                profiles[existingIndex] = canonicalProfile
            } else {
                profiles += canonicalProfile
            }
            canonicalProfile.takeIf { saveProfiles(profiles) }
        }
    }

    override suspend fun replaceProfiles(
        profiles: List<PlayerProfile>
    ): List<PlayerProfile>? =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val storedProfiles = assignMissingPublicPlayerIds(profiles)
                storedProfiles.takeIf { saveProfiles(storedProfiles) }
            }
        }

    private fun assignMissingPublicPlayerIds(profiles: List<PlayerProfile>): List<PlayerProfile> {
        val assigned = mutableListOf<PlayerProfile>()
        profiles.forEach { profile -> assigned += ensurePublicPlayerId(profile, assigned) }
        return assigned
    }

    private fun ensurePublicPlayerId(
        profile: PlayerProfile,
        profiles: List<PlayerProfile>
    ): PlayerProfile {
        val occupied = profiles.asSequence()
            .filter { it.id != profile.id }
            .flatMap { other ->
                listOfNotNull(other.publicPlayerId).asSequence() +
                    other.publicPlayerIdAliases.asSequence()
            }
            .toHashSet()
        val existing = profile.publicPlayerId
            ?.takeIf(::isValidPublicPlayerId)
            ?.takeUnless(occupied::contains)
        if (existing != null) return profile.copy(publicPlayerId = existing)
        val start = stablePublicPlayerIdStart(profile.id)
        repeat(PUBLIC_PLAYER_ID_SPACE) { offset ->
            val candidate = ((start + offset) % PUBLIC_PLAYER_ID_SPACE)
                .toString()
                .padStart(PUBLIC_PLAYER_ID_LENGTH, '0')
            if (candidate !in occupied) return profile.copy(publicPlayerId = candidate)
        }
        return profile.copy(publicPlayerId = null)
    }

    private fun stablePublicPlayerIdStart(profileId: String): Int {
        var hash = FNV_OFFSET_BASIS
        profileId.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash = (hash * FNV_PRIME) and 0xffff_ffffL
        }
        return (hash % PUBLIC_PLAYER_ID_SPACE).toInt()
    }

    private fun loadProfiles(): List<PlayerProfile> {
        val primary = preferences.getString(KEY_PROFILES, null)?.let(::decodeProfiles)
        if (primary != null) return primary
        return preferences.getString(KEY_BACKUP_PROFILES, null)
            ?.let(::decodeProfiles)
            .orEmpty()
    }

    private fun decodeProfiles(serialized: String): List<PlayerProfile>? = runCatching {
        val array = JSONArray(serialized)
        val profiles = mutableListOf<PlayerProfile>()
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@runCatching null
            val id = item.optString("id").takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val nickname = item.optString("nickname").trim().takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val gender = enumValueOrDefault(
                item.optString("gender"),
                PlayerGender.UNDISCLOSED
            )
            val preference = enumValueOrDefault(
                item.optString("defaultPreference"),
                ProfilePlayPreference.OPEN_TO_JOIN
            )
            val createdAtMillis = item.optLong("createdAtMillis", 0L)
                .takeIf { it > 0L } ?: System.currentTimeMillis()
            profiles += PlayerProfile(
                id = id,
                publicPlayerId = item.optNullableString("publicPlayerId")
                    ?.takeIf(::isValidPublicPlayerId),
                publicPlayerIdAliases = item.optJSONArray("publicPlayerIdAliases")
                    ?.let { aliases ->
                        buildSet {
                            repeat(aliases.length()) { aliasIndex ->
                                aliases.optString(aliasIndex)
                                    .takeIf(::isValidPublicPlayerId)
                                    ?.let(::add)
                            }
                        }
                    }
                    .orEmpty(),
                nickname = nickname,
                gender = gender,
                defaultPreference = preference,
                qqNumber = item.optNullableString("qqNumber"),
                avatarReference = item.optNullableString("avatarReference"),
                usageCount = item.optInt("usageCount", 0).coerceAtLeast(0),
                lastUsedAtMillis = item.optLongOrNull("lastUsedAtMillis"),
                qqVisibility = enumValueOrDefault(
                    item.optString("qqVisibility"),
                    QqVisibility.TERMINAL_ONLY
                ),
                notificationPreferences = QueueNotificationPreferences(
                    enabled = item.optBoolean("notificationEnabled", true),
                    queueChanges = item.optBoolean("notifyQueueChanges", true),
                    playingPosition = item.optBoolean("notifyPlayingPosition", false),
                    onlineCheckIn = item.optBoolean("notifyOnlineCheckIn", true),
                    absence = item.optBoolean("notifyAbsence", true),
                    machineStatus = item.optBoolean("notifyMachineStatus", false)
                ),
                webAccountBound = item.optBoolean("webAccountBound", false),
                terminalEditingAllowed = item.optBoolean("terminalEditingAllowed", true),
                visitedVenuesPublic = item.optBoolean("visitedVenuesPublic", true),
                webProfileRevision = item.optLong("webProfileRevision", 0L)
                    .coerceAtLeast(0L),
                setupVersion = item.optInt("setupVersion", 0).coerceAtLeast(0),
                revision = item.optLong("revision", 1L).coerceAtLeast(1L),
                createdAtMillis = createdAtMillis,
                updatedAtMillis = item.optLong("updatedAtMillis", 0L)
                    .takeIf { it > 0L } ?: createdAtMillis
            ).withCanonicalContact()
        }
        profiles
    }.getOrNull()

    private fun saveProfiles(profiles: List<PlayerProfile>): Boolean {
        val array = JSONArray()
        profiles.forEach { rawProfile ->
            val profile = rawProfile.withCanonicalContact()
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("publicPlayerId", profile.publicPlayerId ?: JSONObject.NULL)
                    put(
                        "publicPlayerIdAliases",
                        JSONArray(profile.publicPlayerIdAliases.sorted())
                    )
                    put("nickname", profile.nickname)
                    put("gender", profile.gender.name)
                    put("defaultPreference", profile.defaultPreference.name)
                    put("qqNumber", profile.qqNumber ?: JSONObject.NULL)
                    put("avatarReference", profile.avatarReference ?: JSONObject.NULL)
                    put("usageCount", profile.usageCount)
                    put("lastUsedAtMillis", profile.lastUsedAtMillis ?: JSONObject.NULL)
                    put("qqVisibility", profile.qqVisibility.name)
                    put("notificationEnabled", profile.notificationPreferences.enabled)
                    put("notifyQueueChanges", profile.notificationPreferences.queueChanges)
                    put("notifyPlayingPosition", profile.notificationPreferences.playingPosition)
                    put("notifyOnlineCheckIn", profile.notificationPreferences.onlineCheckIn)
                    put("notifyAbsence", profile.notificationPreferences.absence)
                    put("notifyMachineStatus", profile.notificationPreferences.machineStatus)
                    put("webAccountBound", profile.webAccountBound)
                    put("terminalEditingAllowed", profile.terminalEditingAllowed)
                    put("visitedVenuesPublic", profile.visitedVenuesPublic)
                    put("webProfileRevision", profile.webProfileRevision)
                    put("setupVersion", profile.setupVersion)
                    put("revision", profile.revision)
                    put("createdAtMillis", profile.createdAtMillis)
                    put("updatedAtMillis", profile.updatedAtMillis)
                }
            )
        }
        val serialized = array.toString()
        val currentPrimary = preferences.getString(KEY_PROFILES, null)
            ?.takeIf { decodeProfiles(it) != null }
        val currentBackup = preferences.getString(KEY_BACKUP_PROFILES, null)
            ?.takeIf { decodeProfiles(it) != null }
        return preferences.edit()
            .putString(KEY_BACKUP_PROFILES, currentPrimary ?: currentBackup ?: serialized)
            .putString(KEY_PROFILES, serialized)
            .commit()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val PUBLIC_PLAYER_ID_SPACE = 1_000_000
        const val FNV_OFFSET_BASIS = 2_166_136_261L
        const val FNV_PRIME = 16_777_619L
        const val KEY_PROFILES = "profiles"
        const val KEY_BACKUP_PROFILES = "previous_valid_profiles"
    }
}
