package ng.com.sendrelay.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** How the client reconnects after the socket drops. */
data class ReconnectOptions @JvmOverloads constructor(
    val enabled: Boolean = true,
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000,
    val backoffMultiplier: Double = 1.5,
)

/** How often the client checks the socket is alive. */
data class HeartbeatOptions @JvmOverloads constructor(
    val enabled: Boolean = true,
    val intervalMillis: Long = 30_000,
    /** Reconnect when no pong arrives within this long after a ping. */
    val timeoutMillis: Long = 10_000,
)

data class RelayClientOptions @JvmOverloads constructor(
    /** Realtime URL. Point it at a local API during development. */
    val url: String = RelayProtocol.DEFAULT_URL,
    /** Device ID sent on connect. Pass one you persist (for example per install); default is random per client. */
    val deviceId: String? = null,
    val reconnect: ReconnectOptions = ReconnectOptions(),
    val heartbeat: HeartbeatOptions = HeartbeatOptions(),
    /** Fetch a fresh token this long before the current one expires. */
    val tokenRefreshBeforeExpiryMillis: Long = 300_000,
)

/**
 * Supplies a tracking token for the given task IDs, usually by calling your server, which mints
 * one with `relay.auth.createWebSocketToken`. Called on a background thread, so blocking is fine.
 */
fun interface TokenProvider {
    @Throws(Exception::class)
    fun getToken(taskIds: List<String>): String
}

/** Receives every event. Called on a background thread; post to your UI thread as needed. */
fun interface RelayListener {
    fun onEvent(event: RelayEvent)
}

/**
 * Tracks tasks live over Relay's realtime socket. Works on Android and any JVM.
 *
 * Kotlin:
 * ```kotlin
 * val relay = RelayRealtimeClient({ taskIds -> api.trackingToken(taskIds) })
 * lifecycleScope.launch { relay.events.collect { event -> render(event) } }
 * relay.listen(taskId)
 * ```
 *
 * Java:
 * ```java
 * RelayRealtimeClient relay = new RelayRealtimeClient(taskIds -> api.trackingToken(taskIds));
 * relay.addListener(event -> render(event));
 * relay.listen(taskId);
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRealtimeClient @JvmOverloads constructor(
    private val tokenProvider: TokenProvider,
    val options: RelayClientOptions = RelayClientOptions(),
    private val httpClient: OkHttpClient = OkHttpClient(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every state change runs here, one at a time.
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val listeners = CopyOnWriteArrayList<RelayListener>()
    private val flow = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 256)
    private val deviceId = options.deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: "android-${UUID.randomUUID()}"

    private var socket: WebSocket? = null
    private var open = false
    private var generation = 0
    private val listening = LinkedHashSet<String>()
    private var intentionalClose = false
    private var reconnectAttempt = 0
    private var lastPongAt = 0L
    private var jobs = emptyList<Job>()

    /** Every event, for Kotlin coroutines. */
    val events: SharedFlow<RelayEvent> = flow.asSharedFlow()

    /** Receive every event with a callback. Close the returned handle to stop. */
    fun addListener(listener: RelayListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    val isConnected: Boolean get() = open
    val listeningTo: List<String> get() = synchronized(listening) { listening.toList() }

    /** Start receiving events for a task. Connects (asking the token provider) if needed. */
    fun listen(taskId: String) {
        scope.launch(serial) {
            synchronized(listening) { listening.add(taskId) }
            if (open) send(RelayProtocol.subscribe(taskId)) else connectNow()
        }
    }

    /** Stop receiving events for a task. The socket stays open for other tasks. */
    fun stopListening(taskId: String) {
        scope.launch(serial) {
            synchronized(listening) { listening.remove(taskId) }
            if (open) send(RelayProtocol.unsubscribe(taskId))
        }
    }

    /** Open the socket (or reopen it, for example when an Android app returns to the foreground). */
    fun connect() {
        scope.launch(serial) { if (!open) connectNow() }
    }

    /** Close the socket and stop reconnecting. */
    fun disconnect() {
        scope.launch(serial) {
            intentionalClose = true
            teardown()
            emit(RelayEvent.ConnectionClose(1000, "client disconnect"))
        }
    }

    /** Disconnect and release the client's threads. */
    override fun close() {
        intentionalClose = true
        socket?.close(1000, "client closed")
        scope.cancel()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun emit(event: RelayEvent) {
        flow.tryEmit(event)
        for (listener in listeners) runCatching { listener.onEvent(event) }
    }

    private fun send(message: Map<String, String>) {
        socket?.send(RelayProtocol.encode(message))
    }

    private fun teardown() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        socket?.close(1000, null)
        socket = null
        open = false
    }

    private suspend fun validToken(): String = withContext(Dispatchers.IO) {
        val ids = listeningTo
        var token = tokenProvider.getToken(ids)
        if ((RelayProtocol.secondsUntilExpiry(token) ?: 1) <= 0) {
            token = tokenProvider.getToken(ids)
            if ((RelayProtocol.secondsUntilExpiry(token) ?: 1) <= 0) {
                throw RelayException("getToken returned an expired token. Make sure your backend issues a fresh one.")
            }
        }
        token
    }

    private suspend fun connectNow(): Boolean {
        intentionalClose = false
        val token = try {
            validToken()
        } catch (e: Exception) {
            emit(RelayEvent.ConnectionError(e.message ?: "token request failed"))
            return false
        }
        teardown()
        val current = ++generation
        val request = Request.Builder().url(RelayProtocol.webSocketUrl(options.url, token, deviceId)).build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch(serial) { opened(current, token) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (RelayProtocol.isPong(text)) {
                    lastPongAt = System.currentTimeMillis()
                    return
                }
                RelayProtocol.parse(text)?.let(::emit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(serial) { dropped(current, code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch(serial) {
                    if (current == generation && !open) emit(RelayEvent.ConnectionError(t.message ?: "connection failed"))
                    dropped(current, null, t.message)
                }
            }
        })
        return true
    }

    private fun opened(current: Int, token: String) {
        if (current != generation) return
        open = true
        reconnectAttempt = 0
        lastPongAt = System.currentTimeMillis()
        emit(RelayEvent.ConnectionOpen)
        listeningTo.forEach { send(RelayProtocol.subscribe(it)) }
        jobs = listOfNotNull(heartbeat(current), refresh(current, token))
    }

    private fun heartbeat(current: Int): Job? {
        if (!options.heartbeat.enabled) return null
        return scope.launch {
            while (true) {
                delay(options.heartbeat.intervalMillis)
                val sentAt = System.currentTimeMillis()
                withContext(serial) { if (current == generation) send(RelayProtocol.PING) }
                delay(options.heartbeat.timeoutMillis)
                if (lastPongAt < sentAt) {
                    withContext(serial) { dropped(current, null, "heartbeat timeout") }
                    return@launch
                }
            }
        }
    }

    private fun refresh(current: Int, token: String): Job? {
        val left = RelayProtocol.secondsUntilExpiry(token) ?: return null
        return scope.launch {
            delay(max(0, left * 1000 - options.tokenRefreshBeforeExpiryMillis))
            withContext(serial) { if (current == generation && !intentionalClose) connectNow() }
        }
    }

    private suspend fun dropped(current: Int, code: Int?, reason: String?) {
        if (current != generation || intentionalClose) return
        val wasOpen = open
        socket = null
        open = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        if (wasOpen) emit(RelayEvent.ConnectionClose(code, reason))
        if (!options.reconnect.enabled) return
        val r = options.reconnect
        val base = min(r.initialDelayMillis * r.backoffMultiplier.pow(reconnectAttempt), r.maxDelayMillis.toDouble())
        // ±25% jitter so many clients don't reconnect in lockstep.
        val wait = max(0.0, base + base * 0.25 * (Random.nextDouble() * 2 - 1)).toLong()
        reconnectAttempt++
        emit(RelayEvent.Reconnecting(reconnectAttempt, wait))
        scope.launch {
            delay(wait)
            withContext(serial) {
                if (current == generation && !intentionalClose && !connectNow()) dropped(current, null, "reconnect failed")
            }
        }
    }
}
