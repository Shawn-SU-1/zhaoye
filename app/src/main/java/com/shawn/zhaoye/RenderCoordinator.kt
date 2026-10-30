package com.shawn.zhaoye

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serial native rendering with a conflated mailbox.
 *
 * A slider can submit hundreds of values while one frame is still rendering.
 * Keeping only the newest value prevents a backlog and means the last value is
 * always the one that eventually reaches the screen.
 */
internal class RenderCoordinator<T>(
    scope: CoroutineScope,
    private val worker: suspend (T) -> Unit,
) {
    private val requests = Channel<T>(Channel.CONFLATED)
    private val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (request in requests) worker(request)
    }

    fun submit(request: T) {
        requests.trySend(request)
    }

    fun close() {
        requests.close()
        job.cancel()
    }
}
