package com.example.actions

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class PendingConfirmation(
    val id: String = UUID.randomUUID().toString(),
    val actionName: String,
    val title: String,
    val details: String,
    val token: String,
    val createdAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    val onApproved: suspend () -> String,
    val onRejected: () -> Unit
)

class SafetyGuard {

    private val secureRandom = SecureRandom()
    private val _pendingRequest = MutableStateFlow<PendingConfirmation?>(null)
    val pendingRequest: StateFlow<PendingConfirmation?> = _pendingRequest.asStateFlow()

    /** Guards the read-then-clear of [_pendingRequest] so a request can only ever run once. */
    private val claimLock = Any()

    /** Monotonic count of confirmation requests, used to verify the isSensitive contract. */
    private val requestCount = AtomicInteger(0)

    fun confirmationRequestCount(): Int = requestCount.get()

    fun createConfirmationRequest(
        actionName: String,
        title: String,
        details: String,
        onApproved: suspend () -> String,
        onRejected: () -> Unit = {}
    ): String {
        val tokenBytes = ByteArray(16)
        secureRandom.nextBytes(tokenBytes)
        // Byte must be masked to 0..255: "%02x".format(byte) sign-extends and renders
        // negative bytes as 8 characters (ffffff80).
        val token = tokenBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        val request = PendingConfirmation(
            actionName = actionName,
            title = title,
            details = details,
            token = token,
            onApproved = onApproved,
            onRejected = onRejected
        )
        requestCount.incrementAndGet()

        synchronized(claimLock) {
            // A single slot means a second sensitive action in the same turn would silently
            // orphan the first. Explicitly reject the superseded request instead.
            _pendingRequest.getAndUpdate { current ->
                current?.onRejected?.invoke()
                request
            }
        }

        return "Confirmation required for $title. Please confirm on the device display."
    }

    suspend fun approve(token: String): String {
        val claimed = synchronized(claimLock) {
            val current = _pendingRequest.value ?: return "No confirmation request pending."
            if (SystemClock.elapsedRealtime() - current.createdAtElapsedMs > CONFIRMATION_TTL_MS) {
                _pendingRequest.value = null
                current.onRejected.invoke()
                return "Confirmation request expired. Please ask again."
            }
            if (current.token != token) {
                return "Security token mismatch. Action denied."
            }
            // Clear before running so a double tap cannot execute onApproved twice.
            _pendingRequest.value = null
            current
        }

        return try {
            claimed.onApproved()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Action failed: ${e.message}"
        }
    }

    fun reject() {
        val current = synchronized(claimLock) {
            _pendingRequest.getAndSet(null)
        }
        current?.onRejected?.invoke()
    }

    companion object {
        private const val CONFIRMATION_TTL_MS = 60_000L
    }
}