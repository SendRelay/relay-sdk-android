package ng.com.sendrelay.relay

/** The published SDK version. Kept in sync with gradle.properties by scripts/check-sdk-versions.mjs. */
const val RELAY_SDK_VERSION = "1.7.0"

enum class TaskStatus(val value: String) {
    PENDING("PENDING"),
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
    OFFERED("TASK_OFFERED", TaskStatus.OFFERED),
    ASSIGNED("TASK_ASSIGNED", TaskStatus.ASSIGNED),
    IN_PROGRESS("TASK_IN_PROGRESS", TaskStatus.IN_PROGRESS),
    STAGE_COMPLETED("STAGE_COMPLETED", null),
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
