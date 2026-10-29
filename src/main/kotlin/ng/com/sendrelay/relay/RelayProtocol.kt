package ng.com.sendrelay.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/**
 * Pure pieces of the realtime protocol: the socket URL, outgoing messages and incoming frames.
 * They are tested against the shared conformance fixtures.
 */
object RelayProtocol {
    const val DEFAULT_PATH = "/v1/realtime/ws"
    const val DEFAULT_URL = "https://api.sendrelay.com.ng$DEFAULT_PATH"

    /**
     * The socket URL for a token: https becomes wss, a bare host gets https, and the default path
     * is added when the URL has none.
     */
    @JvmStatic
    @JvmOverloads
    fun webSocketUrl(baseUrl: String = DEFAULT_URL, token: String, deviceId: String? = null, connectionType: String = "APP"): String {
        var text = baseUrl.trim()
        require(text.isNotEmpty()) { "Realtime URL cannot be empty" }
        if (!text.contains("://")) text = "https://$text"
        val uri = URI(text)
        val host = uri.host ?: throw IllegalArgumentException("Invalid realtime URL: missing host in \"$baseUrl\"")
        val scheme = when (uri.scheme.lowercase()) {
            "https", "wss" -> "wss"
            "http", "ws" -> "ws"
            else -> throw IllegalArgumentException("Unsupported URL scheme \"${uri.scheme}\". Use https:// or http://.")
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = if (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") DEFAULT_PATH else uri.rawPath
        val query = buildList {
            add("token" to token)
            add("type" to connectionType)
            deviceId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("deviceId" to it) }
        }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8.name()).replace("+", "%20")}" }
        return "$scheme://$host$port$path?$query"
    }

    @JvmStatic
    fun subscribe(taskId: String): Map<String, String> = mapOf("action" to "subscribe", "type" to "task", "id" to taskId)

    @JvmStatic
    fun unsubscribe(taskId: String): Map<String, String> = mapOf("action" to "unsubscribe", "type" to "task", "id" to taskId)

    @JvmField
    val PING: Map<String, String> = mapOf("action" to "ping")

    internal fun encode(message: Map<String, String>): String =
        JsonObject(message.mapValues { JsonPrimitive(it.value) }).toString()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The event in a raw server frame, or null for frames that aren't events (acknowledgements,
     * pongs, unknown types). A `TASK_UPDATE` envelope is unwrapped.
     */
    @JvmStatic
    fun parse(frame: String): RelayEvent? {
        var obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        if (obj.string("type") == "TASK_UPDATE") obj = (obj["data"] as? JsonObject) ?: return null
        val type = obj.string("type") ?: return null
        val taskId = obj.string("taskId") ?: return null
        if (type == "RIDER_LOCATION_UPDATE") {
            val location = obj["location"] as? JsonObject ?: return null
            val latitude = location.double("latitude") ?: return null
            val longitude = location.double("longitude") ?: return null
            // A malformed ETA is dropped rather than losing the position.
            val eta = (obj["eta"] as? JsonObject)?.let { e ->
                val stage = e.int("stageIndex")
                val seconds = e.double("seconds")
                val meters = e.double("meters")
                if (stage != null && seconds != null && meters != null) {
                    RiderRouteEta(stage, Math.round(seconds).toInt(), Math.round(meters).toInt())
                } else {
                    null
                }
            }
            return RelayEvent.RiderLocationChanged(
                RiderLocationUpdate(
                    taskId = taskId,
                    riderId = obj.string("riderId"),
                    location = RiderLocation(
                        latitude,
                        longitude,
                        bearing = location.double("bearing"),
                        heading = location.double("heading"),
                        speed = location.double("speed"),
                        accuracy = location.double("accuracy"),
                    ),
                    eta = eta,
                    timestamp = obj.string("timestamp"),
                    replayed = obj.bool("replayed") ?: false,
                ),
            )
        }
        val eventType = TaskEventType.from(type) ?: return null
        return RelayEvent.Task(
            TaskEvent(
                type = eventType,
                taskId = taskId,
                status = TaskStatus.from(obj.string("status")),
                riderId = obj.string("riderId"),
                developerId = obj.string("developerId"),
                currentStageIndex = obj.int("currentStageIndex"),
                stageIndex = obj.int("stageIndex"),
                nextStageIndex = obj.int("nextStageIndex"),
                reason = obj.string("reason"),
                cancelledBy = obj.string("cancelledBy"),
                deliveryFee = (obj["deliveryFee"] as? JsonPrimitive)?.longOrNull,
                completionCodeVerified = obj.bool("completionCodeVerified"),
                at = listOf("timestamp", "completedAt", "cancelledAt", "failedAt", "assignedAt", "startedAt", "offeredAt")
                    .firstNotNullOfOrNull { obj.string(it) },
            ),
        )
    }

    /** True for a `pong` frame (the heartbeat's answer). */
    @JvmStatic
    fun isPong(frame: String): Boolean =
        runCatching { json.parseToJsonElement(frame).jsonObject.string("type") == "pong" }.getOrDefault(false)

    /** Seconds until a JWT's `exp`, or null when the token has no readable expiry. */
    @JvmStatic
    @JvmOverloads
    fun secondsUntilExpiry(token: String, nowMillis: Long = System.currentTimeMillis()): Long? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val claims = runCatching {
            json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')))).jsonObject
        }.getOrNull() ?: return null
        val exp = (claims["exp"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return (exp - nowMillis / 1000.0).toLong()
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonElement)?.takeIf { it !is JsonNull } as? JsonPrimitive
    private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content
    private fun JsonObject.double(key: String): Double? = primitive(key)?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
}
