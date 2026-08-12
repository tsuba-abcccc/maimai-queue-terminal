package com.abcccc.maimaiqueue

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCloudSyncControllerTest {
    @Test
    fun privateChannelFailureOverridesAHealthyPublicUploadStatus() {
        val observedAtMillis = 456L
        val combined = combinedQueueCloudSyncStatus(
            QueueCloudSyncStatus(
                phase = QueueCloudSyncPhase.SYNCED,
                lastSuccessfulAtMillis = 123L
            ),
            "服务器没有转发终端命令接口。",
            observedAtMillis
        )

        assertEquals(QueueCloudSyncPhase.WAITING_TO_RETRY, combined.phase)
        assertEquals(123L, combined.lastSuccessfulAtMillis)
        assertEquals(observedAtMillis, combined.retryStartedAtMillis)
        assertEquals(observedAtMillis, combined.lastErrorAtMillis)
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
    fun recoveredPrivateFailureKeepsOnlyTheLastErrorTime() {
        val combined = combinedQueueCloudSyncStatus(
            publicStatus = QueueCloudSyncStatus(
                phase = QueueCloudSyncPhase.SYNCED,
                lastSuccessfulAtMillis = 123L
            ),
            privateFailureDetail = null,
            privateFailureRetryStartedAtMillis = null,
            privateFailureLastErrorAtMillis = 789L
        )

        assertEquals(QueueCloudSyncPhase.SYNCED, combined.phase)
        assertEquals(null, combined.retryStartedAtMillis)
        assertEquals(789L, combined.lastErrorAtMillis)
    }

    @Test
    fun combinedFailureKeepsTheEarliestRetryStartAndLatestError() {
        val combined = combinedQueueCloudSyncStatus(
            publicStatus = QueueCloudSyncStatus(
                phase = QueueCloudSyncPhase.WAITING_TO_RETRY,
                retryStartedAtMillis = 500L,
                lastErrorAtMillis = 900L,
                retryDetail = "公开队列上传失败"
            ),
            privateFailureDetail = "玩家资料同步失败",
            privateFailureRetryStartedAtMillis = 300L,
            privateFailureLastErrorAtMillis = 800L
        )

        assertEquals(300L, combined.retryStartedAtMillis)
        assertEquals(900L, combined.lastErrorAtMillis)
        assertTrue(combined.retryDetail.orEmpty().contains("公开队列上传失败"))
        assertTrue(combined.retryDetail.orEmpty().contains("资料与命令同步"))
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

    @Test
    fun refreshRepublishesTheLatestSubmittedState() = runBlocking {
        val publisher = RecordingPublisher()
        val controller = QueueCloudSyncController(
            scope = this,
            publisher = publisher,
            onStatusChange = {}
        )
        val latest = state(revision = 7L)

        controller.submit(latest)
        assertEquals(7L, withTimeout(1_000L) { publisher.publishedStates.receive() }.revision)

        controller.refresh()
        assertEquals(7L, withTimeout(1_000L) { publisher.publishedStates.receive() }.revision)
        controller.setEnabled(false)
    }

    @Test
    fun disablingSyncEndsTheCurrentRetryPeriodButKeepsTheLastErrorTime() = runBlocking {
        val statuses = Channel<QueueCloudSyncStatus>(Channel.UNLIMITED)
        val controller = QueueCloudSyncController(
            scope = this,
            publisher = RecordingPublisher(
                publishResult = QueuePublishResult.Failure("网络错误")
            ),
            onStatusChange = { statuses.trySend(it) }
        )

        controller.submit(state(revision = 8L))
        val waiting = withTimeout(1_000L) { statuses.receive() }
        assertEquals(QueueCloudSyncPhase.WAITING_TO_RETRY, waiting.phase)
        assertTrue(waiting.retryStartedAtMillis != null)

        controller.setEnabled(false)
        val disabled = withTimeout(1_000L) { statuses.receive() }
        assertEquals(QueueCloudSyncPhase.DISABLED, disabled.phase)
        assertEquals(null, disabled.retryStartedAtMillis)
        assertTrue(disabled.lastErrorAtMillis != null)

        controller.setEnabled(true)
        val configured = withTimeout(1_000L) { statuses.receive() }
        assertEquals(QueueCloudSyncPhase.CONFIGURED, configured.phase)
        assertEquals(null, configured.retryStartedAtMillis)
        controller.setEnabled(false)
    }

    @Test
    fun oneOffPublishUsesTheSameLockAsTheRegularPublishLoop() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val publisher = RecordingPublisher(
            beforeReturn = { state ->
                if (state.revision == 1L) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
        )
        val controller = QueueCloudSyncController(
            scope = this,
            publisher = publisher,
            onStatusChange = {}
        )

        controller.submit(state(revision = 1L))
        firstStarted.await()
        assertEquals(1L, publisher.publishedStates.receive().revision)
        val oneOff = launch {
            controller.withPublishLock {
                publisher.publish(state(revision = 2L))
            }
        }
        yield()
        assertFalse(oneOff.isCompleted)

        releaseFirst.complete(Unit)
        withTimeout(1_000L) { oneOff.join() }
        assertEquals(2L, publisher.publishedStates.receive().revision)
        controller.setEnabled(false)
    }

    private class RecordingPublisher(
        override val isConfigured: Boolean = true,
        private val publishResult: QueuePublishResult = QueuePublishResult.Success,
        private val beforeReturn: suspend (PersistedQueueState) -> Unit = {}
    ) : QueueStatePublisher {
        val publishedStates = Channel<PersistedQueueState>(Channel.UNLIMITED)

        override suspend fun publish(
            state: PersistedQueueState,
            auditLogs: List<AuditLogEntry>,
            displaySettings: QueuePublicDisplaySettings,
            playerProfiles: List<PlayerProfile>
        ): QueuePublishResult {
            publishedStates.send(state)
            beforeReturn(state)
            return publishResult
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
