package com.abcccc.maimaiqueue

fun MachineQueue.resolvePlayerProfileAliases(
    profileAliases: Map<String, String>,
    profiles: List<PlayerProfile>
): MachineQueue {
    if (profileAliases.isEmpty()) return this
    val profilesById = profiles.associateBy(PlayerProfile::id)
    val transform: (Registration) -> Registration = { registration ->
        val targetId = registration.playerProfileId?.let(profileAliases::get)
        val target = targetId?.let(profilesById::get)
        if (targetId == null || target == null) {
            registration
        } else {
            registration.copy(
                displayId = target.nickname,
                gender = target.gender,
                playerProfileId = targetId,
                isTemporary = false
            )
        }
    }
    return copy(
        playing = playing.map(transform),
        waiting = waiting.map(transform)
    )
}
