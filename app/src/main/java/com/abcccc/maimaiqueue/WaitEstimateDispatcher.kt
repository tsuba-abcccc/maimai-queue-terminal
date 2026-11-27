package com.abcccc.maimaiqueue

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * Where wait estimates are computed: one thread of its own at background priority.
 *
 * An estimate replays the queues many times. With a long queue on a pair of machines that takes
 * a second or more on a fast computer, and longer on the tablet (measured 2026-11-27). On the
 * shared default threads it competed with the queue actions and with drawing. Estimates may be a
 * little late; queueing must stay smooth (maintainer, 2026-11-27: “估计时间算法作为低优先级进行，
 * 不要让平板正常排队边卡顿”). A single thread also means that two estimates never run at once.
 */
internal val WaitEstimateDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(
        {
            // Not available in plain JVM tests.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            runnable.run()
        },
        "wait-estimates"
    ).apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}.asCoroutineDispatcher()

/**
 * Runs [block] on [WaitEstimateDispatcher] and waits for it. For callers that are not coroutines,
 * such as the snapshot upload, which runs on a network thread and can wait.
 */
internal fun <T> onWaitEstimateThread(block: () -> T): T = runBlocking(WaitEstimateDispatcher) { block() }
