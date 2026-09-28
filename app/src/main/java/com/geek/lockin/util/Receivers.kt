package com.geek.lockin.util

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread while keeping the broadcast (and process) alive until it finishes. */
fun BroadcastReceiver.doAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            // Stay under the ~10 s broadcast budget.
            withTimeout(9_000) { block() }
        } catch (t: Throwable) {
            Log.e("goAsync", "Receiver work failed", t)
        } finally {
            pending.finish()
        }
    }
}
