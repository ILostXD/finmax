package com.finmax.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Base for per-screen state holders kept in a back stack entry. */
open class ScreenState : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun <T> load(target: MutableState<Load<T>>, block: suspend () -> T): Job {
        if (target.value !is Load.Ok) target.value = Load.Loading
        return scope.launch {
            target.value = try {
                Load.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Load.Failed(friendlyError(e))
            }
        }
    }

    override fun close() = scope.cancel()
}

fun <T> loadState(): MutableState<Load<T>> = mutableStateOf(Load.Loading)

fun friendlyError(e: Throwable): String {
    android.util.Log.w("FINMAX", "error", e)
    return describe(e)
}

private fun describe(e: Throwable): String = when (e) {
    is InvalidStatusException -> when (e.status) {
        401 -> "Your session has expired. Sign in again from Profiles."
        403 -> "This account isn't allowed to access that."
        404 -> "That item no longer exists on the server."
        else -> "The server returned an error (${e.status})."
    }
    is TimeoutException, is SocketTimeoutException -> "The server took too long to respond."
    is UnknownHostException, is ConnectException -> "Can't reach the server. Check that it's online and on the same network."
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}.let { msg ->
    val cause = e.cause
    if (e !is InvalidStatusException && cause is ConnectException) "Can't reach the server. Check that it's online and on the same network." else msg
}
