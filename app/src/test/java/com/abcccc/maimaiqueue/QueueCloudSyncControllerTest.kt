package com.abcccc.maimaiqueue

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCloudSyncControllerTest {
    @Test
    fun privateChannelFailureOverridesAHealthyPublicUploadStatus() {
        val combined = combinedQueueCloudSyncStatus(
            QueueCloudSyncStatus(
                phase = QueueCloudSyncPhase.SYNCED,
                lastSuccessfulAtMillis = 123L
            ),
            "服务器没有转发终端命令接口。"
        )

        assertEquals(QueueCloudSyncPhase.WAITING_TO_RETRY, combined.phase)
        assertEquals(123L, combined.lastSuccessfulAtMillis)
        assertTrue(combined.retryDetail.orEmpty().contains("资料与命令同步"))
    }

    @Test
    fun privateChannelFailureDoesNotOverrideDisabledStatus() {
        val disabled = QueueCloudSyncStatus(QueueCloudSyncPhase.DISABLED)

        assertEquals(
            disabled,
            combinedQueueCloudSyncStatus(disabled, "网络错误")
        )
    }

    @Test
    fun disabledControllerIgnoresUpdatesAndCanResumeWithCurrentState() = runBlocking {
        val publisher = RecordingPublisher()
        val statuses = mutableListOf<QueueCloudSyncStatus>()
        val controller = QueueCloudSyncController(
            scope = this,
            publisher = publisher,
            initiallyEnabled = false,
            onStatusChange = statuses::add
        )
        val first = state(revision = 1L)
        val second = state(revision = 2L)

        controller.submit(first)
        assertTrue(publisher.publishedStates.tryReceive().isFailure)

        controller.setEnabled(true)
        controller.submit(first)
        assertEquals(1L, withTimeout(1_000L) { publisher.publishedStates.receive() }.revision)

        controller.setEnabled(false)
        controller.submit(second)
        assertTrue(publisher.publishedStates.tryReceive().isFailure)
        assertEquals(QueueCloudSyncPhase.DISABLED, statuses.last().phase)

        controller.setEnabled(true)
        controller.submit(second)
        assertEquals(2L, withTimeout(1_000L) { publisher.publishedStates.receive() }.revision)
        controller.setEnabled(false)
    }

    @Test
    fun enablingWithoutServerConfigurationReportsNotConfigured() = runBlocking {
        val statuses = mutableListOf<QueueCloudSyncStatus>()
        val controller = QueueCloudSyncController(
            scope = this,
            publisher = RecordingPublisher(isConfigured = false),
            initiallyEnabled = false,
            onStatusChange = statuses::add
        )

        controller.setEnabled(true)

        assertEquals(QueueCloudSyncPhase.NOT_CONFIGURED, statuses.single().phase)
    }

    private class RecordingPublisher(
        override val isConfigured: Boolean = true
    ) : QueueStatePublisher {
        val publishedStates = Channel<PersistedQueueState>(Channel.UNLIMITED)

        override suspend fun publish(
            state: PersistedQueueState,
            auditLogs: List<AuditLogEntry>,
            displaySettings: QueuePublicDisplaySettings,
            playerProfiles: List<PlayerProfile>
        ): QueuePublishResult {
            publishedStates.send(state)
            return QueuePublishResult.Success
        }
    }

    private fun state(revision: Long) = PersistedQueueState(
        queueId = "00000000-0000-0000-0000-000000000123",
        revision = revision,
        machineA = MachineQueue(),
        machineB = MachineQueue(),
        machineAStatus = MachineStatus(),
        machineBStatus = MachineStatus(),
        registrationOpen = true,
        nextRegistrationKey = 1,
        savedAtMillis = revision
    )
}
