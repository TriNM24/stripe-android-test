package com.stripe.android.polling

import androidx.annotation.RestrictTo
import com.stripe.android.model.StripeIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface IntentStatusPoller {
    val state: StateFlow<StripeIntent.Status?>

    fun startPolling(scope: CoroutineScope)
    suspend fun forcePoll(): StripeIntent.Status?
    fun resumePolling()
    fun pausePolling()
    fun cancel()
}
