package ng.com.sendrelay.relay

/** The published SDK version. Kept in sync with gradle.properties by scripts/check-sdk-versions.mjs. */
const val RELAY_SDK_VERSION = "1.10.0"

enum class TaskStatus(val value: String) {
    /** Booked for a pickup time; Relay has not started looking yet. */
    SCHEDULED("SCHEDULED"),
    PENDING("PENDING"),
    /** Still looking for a rider after the first offers. */
    SEARCHING("SEARCHING"),
    OFFERED("OFFERED"),
    ASSIGNED("ASSIGNED"),
    IN_PROGRESS("IN_PROGRESS"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
    PAYMENT_FAILED("PAYMENT_FAILED");

    /** True for COMPLETED, FAILED and CANCELLED. */
    val isFinished: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    companion object {
        @JvmStatic
        fun from(value: String?): TaskStatus? = entries.firstOrNull { it.value == value }
    }
}

/** A task lifecycle event from the server. */
enum class TaskEventType(val value: String, val status: TaskStatus?) {
    /** A booked task's search opened: Relay is now looking for a rider. */
    DISPATCH_STARTED("TASK_DISPATCH_STARTED", TaskStatus.PENDING),
    /** The task keeps looking for a rider after its first offers, until `searchExpiresAt`. */
    SEARCHING("TASK_SEARCHING", TaskStatus.SEARCHING),
    /** A waiting manual task has eligible riders near its pickup (a count only). */
    RIDERS_NEARBY("TASK_RIDERS_NEARBY", null),
    OFFERED("TASK_OFFERED", TaskStatus.OFFERED),
    ASSIGNED("TASK_ASSIGNED", TaskStatus.ASSIGNED),
    IN_PROGRESS("TASK_IN_PROGRESS", TaskStatus.IN_PROGRESS),
    STAGE_COMPLETED("STAGE_COMPLETED", null),
    /** The rider reached a stop (`stageIndex`, `stageType`), once per stop. */
    STAGE_ARRIVED("STAGE_ARRIVED", null),
    /** The business edited notes, instructions or a stop's contact; read the task again. */
    DETAILS_UPDATED("TASK_DETAILS_UPDATED", null),
    /** Opt-in (`onRiderCancel: FIND_ANOTHER`): the rider cancelled before pickup; Relay is finding another. */
    RIDER_UNASSIGNED("TASK_RIDER_UNASSIGNED", TaskStatus.PENDING),
    COMPLETED("TASK_COMPLETED", TaskStatus.COMPLETED),
    FAILED("TASK_FAILED", TaskStatus.FAILED),
    CANCELLED("TASK_CANCELLED", TaskStatus.CANCELLED);

    companion object {
        @JvmStatic
        fun from(value: String?): TaskEventType? = entries.firstOrNull { it.value == value }
    }
}

/** One task lifecycle event. Fields the server didn't send are null. */
data class TaskEvent(
    val type: TaskEventType,
    val taskId: String,
    /** Null when the server sent no status, or one this SDK version does not know (see [rawStatus]). */
    val status: TaskStatus? = null,
    val riderId: String? = null,
    val developerId: String? = null,
    val currentStageIndex: Int? = null,
    /** STAGE_COMPLETED: the stage that was completed, and the one the rider moves to next. */
    val stageIndex: Int? = null,
    val nextStageIndex: Int? = null,
    /** TASK_FAILED / TASK_CANCELLED: why. */
    val reason: String? = null,
    /** TASK_CANCELLED: `DEVELOPER` or `RIDER`. */
    val cancelledBy: String? = null,
    val deliveryFee: Long? = null,
    /** TASK_COMPLETED for tasks created with a completion code. */
    val completionCodeVerified: Boolean? = null,
    /** When it happened (ISO 8601), from whichever timestamp the event carries. */
    val at: String? = null,
    /** The status exactly as the server sent it, including ones newer than this SDK. Last, so existing positional calls keep compiling. */
    val rawStatus: String? = null,
    /** TASK_SEARCHING: WAIT_FOR_RIDER or SCHEDULED (or a newer strategy). */
    val strategy: String? = null,
    /** TASK_DISPATCH_STARTED: when the rider should reach the pickup (ISO 8601). */
    val pickupAt: String? = null,
    /** TASK_DISPATCH_STARTED / TASK_SEARCHING: when Relay stops looking (ISO 8601). */
    val searchExpiresAt: String? = null,
    /** TASK_RIDERS_NEARBY: how many eligible riders were found. */
    val ridersNearby: Int? = null,
    /** STAGE_ARRIVED: PICKUP or DROPOFF. */
    val stageType: String? = null,
    /** TASK_RIDER_UNASSIGNED: further rider cancels the task can survive before it ends CANCELLED. */
    val researchesLeft: Int? = null,
    /**
     * The task's revision after this event, as on webhooks and task reads; null when the frame has
     * none (older servers). The client drops a frame at or below the last revision it delivered for
     * the task, so events arrive in order and once.
     */
    val revision: Long? = null,
)

data class RiderLocation(
    val latitude: Double,
    val longitude: Double,
    val bearing: Double? = null,
    val heading: Double? = null,
    /** Metres per second. */
    val speed: Double? = null,
    /** Metres. */
    val accuracy: Double? = null,
)

/** The rider's remaining time and road distance to a stop, as of a location update. */
data class RiderRouteEta(
    /** Index of the stage the rider is heading to. */
    val stageIndex: Int,
    /** Driving time left when the rider reported, in seconds. */
    val seconds: Int,
    /** Road distance left, in metres. */
    val meters: Int,
)

data class RiderLocationUpdate(
    val taskId: String,
    val riderId: String?,
    val location: RiderLocation,
    /** The rider's ETA to their next stop, or null when the rider's app hasn't reported one. */
    val eta: RiderRouteEta?,
    /** When the rider reported this (ISO 8601). `eta.seconds` counts down from here. */
    val timestamp: String?,
    /** True when the server replayed the last known position on subscribe. */
    val replayed: Boolean = false,
)

/** Everything the realtime client tells you about. */
sealed class RelayEvent {
    /** The server's event name, such as `TASK_ASSIGNED` or `RIDER_LOCATION_UPDATE`. */
    abstract val name: String

    data class Task(val event: TaskEvent) : RelayEvent() {
        override val name: String get() = event.type.value
    }

    data class RiderLocationChanged(val update: RiderLocationUpdate) : RelayEvent() {
        override val name: String get() = "RIDER_LOCATION_UPDATE"
    }

    object ConnectionOpen : RelayEvent() {
        override val name: String get() = "CONNECTION_OPEN"
    }

    data class ConnectionClose(val code: Int?, val reason: String?) : RelayEvent() {
        override val name: String get() = "CONNECTION_CLOSE"
    }

    data class ConnectionError(val message: String) : RelayEvent() {
        override val name: String get() = "CONNECTION_ERROR"
    }

    data class Reconnecting(val attempt: Int, val delayMillis: Long) : RelayEvent() {
        override val name: String get() = "CONNECTION_RECONNECTING"
    }
}

class RelayException(message: String, cause: Throwable? = null) : Exception(message, cause)
