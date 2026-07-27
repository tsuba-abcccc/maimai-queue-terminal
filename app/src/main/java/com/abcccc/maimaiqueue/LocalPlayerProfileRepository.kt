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
    suspend fun upsertProfile(profile: PlayerProfile): Boolean
    suspend fun replaceProfiles(profiles: List<PlayerProfile>): Boolean
}

class LocalPlayerProfileRepository(context: Context) : PlayerProfileRepository {
    private val writeMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "player_profiles",
        Context.MODE_PRIVATE
    )

    override suspend fun getProfiles(): List<PlayerProfile> = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            clearAmbiguousQqBindings(loadProfiles()).also { profiles ->
                if (profiles.isNotEmpty()) saveProfiles(profiles)
            }
        }
    }

    override suspend fun upsertProfile(profile: PlayerProfile): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val profiles = loadProfiles().toMutableList()
            val canonicalProfile = profile.withCanonicalContact()
            val existingIndex = profiles.indexOfFirst { it.id == canonicalProfile.id }
            if (existingIndex >= 0) {
                profiles[existingIndex] = canonicalProfile
            } else {
                profiles += canonicalProfile
            }
            saveProfiles(profiles)
        }
    }

    override suspend fun replaceProfiles(profiles: List<PlayerProfile>): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                saveProfiles(profiles)
            }
        }

    private fun loadProfiles(): List<PlayerProfile> {
        val serialized = preferences.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@repeat
                    val nickname = item.optString("nickname").trim().takeIf { it.isNotBlank() }
                        ?: return@repeat
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
                    add(
                        PlayerProfile(
                            id = id,
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
                                queueChanges = item.optBoolean(
                                    "notifyQueueChanges",
                                    true
                                ),
                                playingPosition = item.optBoolean(
                                    "notifyPlayingPosition",
                                    false
                                ),
                                onlineCheckIn = item.optBoolean(
                                    "notifyOnlineCheckIn",
                                    true
                                ),
                                absence = item.optBoolean("notifyAbsence", true),
                                machineStatus = item.optBoolean(
                                    "notifyMachineStatus",
                                    false
                                )
                            ),
                            setupVersion = item.optInt("setupVersion", 0).coerceAtLeast(0),
                            revision = item.optLong("revision", 1L).coerceAtLeast(1L),
                            createdAtMillis = createdAtMillis,
                            updatedAtMillis = item.optLong("updatedAtMillis", 0L)
                                .takeIf { it > 0L } ?: createdAtMillis
                        ).withCanonicalContact()
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveProfiles(profiles: List<PlayerProfile>): Boolean {
        val array = JSONArray()
        profiles.forEach { rawProfile ->
            val profile = rawProfile.withCanonicalContact()
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
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
                    put("setupVersion", profile.setupVersion)
                    put("revision", profile.revision)
                    put("createdAtMillis", profile.createdAtMillis)
                    put("updatedAtMillis", profile.updatedAtMillis)
                }
            )
        }
        return preferences.edit().putString(KEY_PROFILES, array.toString()).commit()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_PROFILES = "profiles"
    }
}
