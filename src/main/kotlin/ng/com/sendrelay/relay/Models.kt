package ng.com.sendrelay.relay

/** The published SDK version. Kept in sync with gradle.properties by scripts/check-sdk-versions.mjs. */
const val RELAY_SDK_VERSION = "1.13.0"

enum class TaskStatus(val value: String) {
    /** Booked for a pickup time; SendRelay has not started looking yet. */
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
    /** A booked task's search opened: SendRelay is now looking for a rider. */
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
    /** Opt-in (`onRiderCancel: FIND_ANOTHER`): the rider cancelled before pickup; SendRelay is finding another. */
    RIDER_UNASSIGNED("TASK_RIDER_UNASSIGNED", TaskStatus.PENDING),
    /** Pooled delivery: the rider started the shared route; `finalTotal` is the price. Comes after TASK_ASSIGNED. */
    POOLING_GROUPED("TASK_POOLING_GROUPED", null),
    /** Pooled delivery: it goes alone at `maxTotal` through ordinary dispatch; dispatch events follow. */
    POOLING_SOLO("TASK_POOLING_SOLO", null),
    /**
     * Errand: a work stop did not get done (`stage.status` FAILED, `stage.reason` why). The task goes
     * on at `nextStageIndex`, or ends when nothing is left to do.
     */
    STAGE_FAILED("STAGE_FAILED", null),
    /** Errand: the rider asks you something ([TaskEvent.approval]). Answer through the server API before it expires. */
    APPROVAL_REQUESTED("APPROVAL_REQUESTED", null),
    /** Errand: a question was answered, timed out or withdrawn (`approval.status`). */
    APPROVAL_RESOLVED("APPROVAL_RESOLVED", null),
    COMPLETED("TASK_COMPLETED", TaskStatus.COMPLETED),
    FAILED("TASK_FAILED", TaskStatus.FAILED),
    CANCELLED("TASK_CANCELLED", TaskStatus.CANCELLED);

    companion object {
        @JvmStatic
        fun from(value: String?): TaskEventType? = entries.firstOrNull { it.value == value }
    }
}

/** STAGE_FAILED: the errand stop that did not get done. */
data class ErrandStageOutcome(
    /** QUEUE, PURCHASE, PAYMENT, INSPECTION, CUSTOM (or a newer type). */
    val type: String,
    /** Your name for the stop, if you gave one. */
    val reference: String? = null,
    /** FAILED or SKIPPED (or a newer status). */
    val status: String,
    /** OUT_OF_STOCK, CLOSED, REFUSED, NOT_READY, PAYMENT_FAILED, OTHER (or a newer reason). */
    val reason: String? = null,
    /** What the rider wrote. */
    val note: String? = null,
    /** When it ended (ISO 8601). */
    val endedAt: String? = null,
)

/**
 * APPROVAL_REQUESTED / APPROVAL_RESOLVED: a question from the rider on an errand. Answer it with the
 * server SDK (`tasks.decideApproval`); the full request, with what approving does to the price, is on
 * `tasks.approvals`.
 */
data class ErrandApproval(
    val id: String,
    val stageIndex: Int? = null,
    /** SUBSTITUTE, PRICE_ABOVE_MAX, OVER_SPEND_LIMIT, MORE_TIME, QUESTION (or a newer kind). */
    val kind: String,
    /** PENDING, APPROVED, DECLINED, EXPIRED (or a newer status). */
    val status: String,
    /** The question in plain words, as the rider app shows it. */
    val question: String? = null,
    /** A photo the rider attached (a file path; open it through the server API). */
    val photo: String? = null,
    /** When the rider stops waiting (ISO 8601). */
    val expiresAt: String? = null,
    /** The note sent with the answer. */
    val note: String? = null,
    val decidedAt: String? = null,
) {
    /** True while the rider is waiting for your answer. */
    val isPending: Boolean get() = status == "PENDING"
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
    /** TASK_DISPATCH_STARTED / TASK_SEARCHING: when SendRelay stops looking (ISO 8601). */
    val searchExpiresAt: String? = null,
    /** TASK_RIDERS_NEARBY: how many eligible riders were found. */
    val ridersNearby: Int? = null,
    /** STAGE_ARRIVED: PICKUP or DROPOFF (or, on an errand, the work stop's type). STAGE_FAILED: the failed stop's type. */
    val stageType: String? = null,
    /** TASK_RIDER_UNASSIGNED: further rider cancels the task can survive before it ends CANCELLED. */
    val researchesLeft: Int? = null,
    /**
     * The task's revision after this event, as on webhooks and task reads; null when the frame has
     * none (older servers). The client drops a frame at or below the last revision it delivered for
     * the task, so events arrive in order and once.
     */
    val revision: Long? = null,
    /** TASK_POOLING_*: the wave (for example MIDDAY) and its date (YYYY-MM-DD, Lagos). */
    val wave: String? = null,
    val waveDate: String? = null,
    /** TASK_POOLING_GROUPED: how many other deliveries share the route (never who they are). */
    val sharedWith: Int? = null,
    /** TASK_POOLING_*: the most it can cost, in kobo. */
    val maxTotal: Long? = null,
    /** TASK_POOLING_GROUPED: what it costs, in kobo. */
    val finalTotal: Long? = null,
    /** TASK_POOLING_GROUPED: the planned pickup and delivery times (ISO 8601). */
    val plannedPickupAt: String? = null,
    val plannedDeliveryAt: String? = null,
    /** STAGE_FAILED: the stop that did not get done. */
    val stage: ErrandStageOutcome? = null,
    /** STAGE_FAILED: how many stops the task has. */
    val totalStages: Int? = null,
    /** APPROVAL_REQUESTED / APPROVAL_RESOLVED: the rider's question. */
    val approval: ErrandApproval? = null,
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
    /**
     * Pooled delivery: stops the rider makes before this delivery's next one; 0 when it is next (the
     * update then has an [eta]). Null on every other task.
     */
    val stopsBefore: Int? = null,
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
