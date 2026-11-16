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

/**
 * A pooled delivery's route: grouped with other deliveries at its final price, or going alone at its
 * maximum. From TASK_POOLING_GROUPED and TASK_POOLING_SOLO.
 */
data class TrackedPooling(
    /** GROUPED or SOLO. */
    val state: String,
    /** Most it can cost, in kobo. */
    val maxTotal: Long?,
    /** What it costs once grouped, in kobo; null while going alone (it pays [maxTotal]). */
    val finalTotal: Long?,
    /** How many other deliveries share the route (never who they are). */
    val sharedWith: Int?,
    val plannedPickupAt: String?,
    val plannedDeliveryAt: String?,
)

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
    /** When SendRelay stops looking for a rider (ISO 8601), for tasks that keep looking or are booked. Count down to this while SEARCHING. */
    val searchExpiresAt: String? = null,
    /** Pooled delivery only: its route and price, once grouped or sent alone. */
    val pooling: TrackedPooling? = null,
    /**
     * Pooled delivery only: stops the rider makes before this delivery's next one (0 when it is next).
     * While above 0 there is no ETA and the position may be withheld: show the planned times.
     */
    val stopsBefore: Int? = null,
    /** Errand only: the rider's open question, waiting for your answer; null once it is answered, times out, or the task ends. */
    val approval: ErrandApproval? = null,
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
                // The rider who cancelled is gone; their last position and ETA no longer apply.
                val unassigned = e.type == TaskEventType.RIDER_UNASSIGNED
                val grouped = e.type == TaskEventType.POOLING_GROUPED
                val solo = e.type == TaskEventType.POOLING_SOLO
                val nextPooling = if (grouped || solo) {
                    TrackedPooling(
                        state = if (grouped) "GROUPED" else "SOLO",
                        maxTotal = e.maxTotal,
                        finalTotal = if (grouped) e.finalTotal else null,
                        sharedWith = if (grouped) e.sharedWith else null,
                        plannedPickupAt = if (grouped) e.plannedPickupAt else null,
                        plannedDeliveryAt = if (grouped) e.plannedDeliveryAt else null,
                    )
                } else {
                    pooling
                }
                val nextApproval = when {
                    done -> null
                    e.type == TaskEventType.APPROVAL_REQUESTED && e.approval?.isPending == true -> e.approval
                    e.type == TaskEventType.APPROVAL_RESOLVED && (approval == null || approval.id == e.approval?.id) -> null
                    else -> approval
                }
                copy(
                    status = nextStatus,
                    finished = done,
                    currentStageIndex = stage,
                    eta = if (keepEta && !unassigned) eta else null,
                    rider = if (unassigned) null else rider,
                    riderId = if (unassigned) null else riderId,
                    searchExpiresAt = e.searchExpiresAt ?: searchExpiresAt,
                    pooling = nextPooling,
                    // A pooled delivery going alone is tracked like any other task from here.
                    stopsBefore = if (solo) null else stopsBefore,
                    approval = nextApproval,
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
                    // Pooled delivery: an ETA only while this delivery's stop is next.
                    eta = u.eta?.let { TrackedEta(it.stageIndex, it.seconds, it.meters, reported + it.seconds * 1000L, reported) }
                        ?: if ((u.stopsBefore ?: 0) > 0) null else eta,
                    stopsBefore = u.stopsBefore ?: stopsBefore,
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
