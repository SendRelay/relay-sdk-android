package ng.com.sendrelay.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The rider's ETA as an absolute arrival time. Same shape as `liveEta` on the server's task read. */
data class TrackedEta(
    val stageIndex: Int,
    /** Driving time left when the rider last reported, in seconds. */
    val seconds: Int,
    /** Road distance left when the rider last reported, in metres. */
    val meters: Int,
    /** When the rider is expected at the stop (epoch millis). Count down to this. */
    val arrivesAtMillis: Long,
    /** When the rider last reported this (epoch millis). */
    val updatedAtMillis: Long,
) {
    /** Seconds left from [nowMillis], never below zero. */
    @JvmOverloads
    fun secondsRemaining(nowMillis: Long = System.currentTimeMillis()): Long =
        maxOf(0L, Math.round((arrivesAtMillis - nowMillis) / 1000.0))
}

enum class TrackerConnection { IDLE, CONNECTING, LIVE, RECONNECTING, OFFLINE }

/** Everything known about one tracked task. Mirrors the web SDKs' tracker state. */
data class TaskTrackerState @JvmOverloads constructor(
    val taskId: String,
    val connection: TrackerConnection = TrackerConnection.IDLE,
    val status: TaskStatus? = null,
    val currentStageIndex: Int? = null,
    val rider: RiderLocation? = null,
    val riderId: String? = null,
    val eta: TrackedEta? = null,
    /** Lifecycle events, newest first (location updates excluded). */
    val events: List<TaskEvent> = emptyList(),
    val finished: Boolean = status?.isFinished ?: false,
    val error: String? = null,
) {
    /** The state after an event. Events for other tasks change nothing. */
    @JvmOverloads
    fun applying(event: RelayEvent, maxEvents: Int = 50): TaskTrackerState = when (event) {
        is RelayEvent.Task -> {
            val e = event.event
            if (e.taskId != taskId) {
                this
            } else {
                val nextStatus = e.type.status ?: status
                val done = nextStatus?.isFinished ?: false
                val stage = e.currentStageIndex ?: e.nextStageIndex ?: currentStageIndex
                // The ETA belongs to a stop; drop it once the task moves on or ends.
                val keepEta = !done && !(eta != null && stage != null && eta.stageIndex != stage)
                copy(
                    status = nextStatus,
                    finished = done,
                    currentStageIndex = stage,
                    eta = if (keepEta) eta else null,
                    events = (listOf(e) + events).take(maxEvents),
                )
            }
        }
        is RelayEvent.RiderLocationChanged -> {
            val u = event.update
            if (u.taskId != taskId) {
                this
            } else {
                val reported = u.timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: System.currentTimeMillis()
                copy(
                    connection = TrackerConnection.LIVE,
                    rider = u.location,
                    riderId = u.riderId ?: riderId,
                    eta = u.eta?.let { TrackedEta(it.stageIndex, it.seconds, it.meters, reported + it.seconds * 1000L, reported) } ?: eta,
                )
            }
        }
        RelayEvent.ConnectionOpen -> copy(connection = TrackerConnection.LIVE, error = null)
        is RelayEvent.Reconnecting -> copy(connection = TrackerConnection.RECONNECTING)
        is RelayEvent.ConnectionClose -> copy(connection = if (finished) TrackerConnection.IDLE else TrackerConnection.OFFLINE)
        is RelayEvent.ConnectionError -> copy(connection = TrackerConnection.OFFLINE, error = event.message)
    }
}

/** Receives tracker state changes, for Java. */
fun interface TaskTrackerListener {
    fun onChange(state: TaskTrackerState)
}

/**
 * Tracks one task: status, rider position and live ETA.
 *
 * Kotlin: collect [state]. Java: [addListener]. Call [start] when the screen opens and [stop]
 * when it closes.
 */
class TaskTracker @JvmOverloads constructor(
    private val client: RelayRealtimeClient,
    taskId: String,
    initial: TaskTrackerState = TaskTrackerState(taskId),
) {
    private val mutable = MutableStateFlow(initial)
    private val listeners = CopyOnWriteArrayList<TaskTrackerListener>()
    private var subscription: AutoCloseable? = null

    val state: StateFlow<TaskTrackerState> = mutable.asStateFlow()

    fun addListener(listener: TaskTrackerListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    @Synchronized
    fun start() {
        if (subscription != null) return
        subscription = client.addListener { event -> update { it.applying(event) } }
        update { it.copy(connection = if (client.isConnected) TrackerConnection.LIVE else TrackerConnection.CONNECTING) }
        client.listen(mutable.value.taskId)
    }

    @Synchronized
    fun stop() {
        subscription?.close()
        subscription = null
        client.stopListening(mutable.value.taskId)
        update { it.copy(connection = TrackerConnection.IDLE) }
    }

    private fun update(change: (TaskTrackerState) -> TaskTrackerState) {
        val next = synchronized(mutable) { change(mutable.value).also { mutable.value = it } }
        for (listener in listeners) runCatching { listener.onChange(next) }
    }
}
