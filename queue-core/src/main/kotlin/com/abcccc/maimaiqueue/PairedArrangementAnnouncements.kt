package com.abcccc.maimaiqueue

/** What [QueueEngine.refreshPairedArrangements] found out. */
data class PairedArrangementRefresh(
    val state: QueueEngineState,
    /** Registrations whose preview has now been shown long enough for the player to be told. */
    val notices: List<PairedArrangementNotice> = emptyList()
)

data class PairedArrangementNotice(
    val registrationKey: Int,
    val homeMachineId: String,
    val pairedMachineId: String,
    /** The earliest moment at which the registration may be arranged to the other machine. */
    val earliestArrangementAtMillis: Long
)

/**
 * Keeps the previews that announce an arrangement up to date with the clock.
 *
 * A registration is only arranged to the other machine of its pair after a preview there has
 * announced it for [PairedArrangementRules.leadMillis] without a break. Whether a preview is
 * shown follows from a forecast that already obeys that rule: a registration without a preview
 * is assumed to be announced from now on, so the forecast never promises an arrangement that
 * the rules would not allow yet.
 */
internal object PairedArrangementAnnouncements {
    private class Candidate(val key: Int, val homeMachineId: String, val pairedMachineId: String)

    fun refresh(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, PairedMachineForecast.RoundDurations>,
        nowMillis: Long
    ): PairedArrangementRefresh {
        val rules = policy.pairedArrangementRules ?: return PairedArrangementRefresh(state)
        val normalized = PairedMachineQueues.normalize(state, policy)
        val candidates = normalized.queues.flatMap { (machineId, queue) ->
            val partner = PairedMachineQueues.activePartner(normalized, policy, machineId)
                ?: return@flatMap emptyList()
            queue.waiting
                .filter(Registration::holdsPairedMachinePlace)
                .map { Candidate(it.key, machineId, partner) }
        }
        if (candidates.isEmpty()) return PairedArrangementRefresh(normalized)

        val records = normalized.pairedArrangements.toMutableMap()
        fun shownMillis(record: PairedArrangementRecord): Long? =
            record.previewSinceMillis?.let { (record.previewPausedAtMillis ?: nowMillis) - it }

        // Every candidate counts as announced: for as long as its preview has really been shown,
        // or from this moment on.
        val announcedToAll = normalized.copy(
            pairedArrangements = records + candidates.associate { candidate ->
                val record = records[candidate.key] ?: PairedArrangementRecord()
                candidate.key to record.copy(
                    previewSinceMillis = nowMillis - (shownMillis(record) ?: 0L),
                    previewPausedAtMillis = null
                )
            }
        )
        val joint = PairedMachineForecast.forecast(announcedToAll, policy, durations, nowMillis)

        val notices = mutableListOf<PairedArrangementNotice>()
        candidates.forEach { candidate ->
            val record = records[candidate.key] ?: PairedArrangementRecord()
            val running = record.previewSinceMillis != null
            val turn = joint.firstTurns[candidate.key]
            val shown = turn != null &&
                turn.machineId == candidate.pairedMachineId &&
                policy.isOperational(candidate.pairedMachineId) &&
                run {
                    val ownWait = ownMachineWaitMillis(
                        announcedToAll,
                        policy,
                        durations,
                        nowMillis,
                        candidate
                    )
                    // Appearing takes a clear lead; a preview that is running only breaks when
                    // hardly any lead is left, so that it does not flicker around one value.
                    val margin = if (running) rules.breakMarginMillis else rules.appearMarginMillis
                    ownWait == null || ownWait - turn.waitMillis > margin
                }
            if (!shown) {
                val cleared = record.copy(previewSinceMillis = null, previewPausedAtMillis = null)
                if (cleared == PairedArrangementRecord()) {
                    records.remove(candidate.key)
                } else {
                    records[candidate.key] = cleared
                }
                return@forEach
            }

            // The count only runs while the queues are kept up normally.
            val paused = PairedMachineQueues.roundIsOverdue(
                normalized.queue(candidate.pairedMachineId),
                rules,
                nowMillis
            ) || (
                policy.isOperational(candidate.homeMachineId) &&
                    PairedMachineQueues.roundIsOverdue(
                        normalized.queue(candidate.homeMachineId),
                        rules,
                        nowMillis
                    )
                )
            var since = record.previewSinceMillis ?: nowMillis
            var pausedAt = record.previewPausedAtMillis
            if (paused) {
                pausedAt = pausedAt ?: nowMillis
            } else if (pausedAt != null) {
                since += nowMillis - pausedAt
                pausedAt = null
            }
            var noticedAt = record.noticedAtMillis
            if (!paused && noticedAt == null && nowMillis - since >= rules.noticeAfterMillis) {
                noticedAt = nowMillis
                notices += PairedArrangementNotice(
                    registrationKey = candidate.key,
                    homeMachineId = candidate.homeMachineId,
                    pairedMachineId = candidate.pairedMachineId,
                    earliestArrangementAtMillis = since + rules.leadMillis
                )
            }
            records[candidate.key] = record.copy(
                previewSinceMillis = since,
                previewPausedAtMillis = pausedAt,
                noticedAtMillis = noticedAt
            )
        }

        val announced = PairedMachineQueues.normalize(
            normalized.copy(pairedArrangements = records),
            policy
        )
        // A machine that stands empty may now take a registration whose announcement is over.
        val settled = PairedMachineQueues.fillIdleMachines(announced, policy, nowMillis)
        return PairedArrangementRefresh(settled, notices)
    }

    /**
     * When the candidate would play if it stayed at its own machine; null when that machine
     * never reaches it, for example while it is out of operation.
     */
    private fun ownMachineWaitMillis(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, PairedMachineForecast.RoundDurations>,
        nowMillis: Long,
        candidate: Candidate
    ): Long? {
        val queue = state.queue(candidate.homeMachineId) ?: return null
        val stayingHome = state.replace(
            candidate.homeMachineId,
            queue.copy(
                waiting = queue.waiting.map { registration ->
                    if (registration.key == candidate.key) {
                        registration.copy(alsoQueuesAtPairedMachine = false)
                    } else {
                        registration
                    }
                }
            )
        )
        val turn = PairedMachineForecast.forecast(stayingHome, policy, durations, nowMillis)
            .firstTurns[candidate.key]
            ?: return null
        return turn.waitMillis.takeIf { turn.machineId == candidate.homeMachineId }
    }
}
