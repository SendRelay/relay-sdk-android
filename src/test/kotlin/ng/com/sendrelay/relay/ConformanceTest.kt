package ng.com.sendrelay.relay

// Runs the shared realtime conformance fixtures (packages/sdks/conformance) against this SDK.
// Every Relay UI SDK runs the same fixtures, which is how they stay at parity.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConformanceTest {
    private val dir = File(System.getenv("RELAY_CONFORMANCE_DIR") ?: "../conformance")

    private fun read(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText()).jsonObject

    private fun available() = File(dir, "manifest.json").exists()

    /** The event under the API's field names, so fixture paths apply to typed events. */
    private fun apiJson(event: RelayEvent): Map<String, Any?> = when (event) {
        is RelayEvent.Task -> with(event.event) {
            mapOf(
                "taskId" to taskId, "status" to status?.value, "riderId" to riderId,
                "stageIndex" to stageIndex, "reason" to reason, "cancelledBy" to cancelledBy,
            )
        }
        is RelayEvent.RiderLocationChanged -> with(event.update) {
            mapOf(
                "taskId" to taskId, "riderId" to riderId, "timestamp" to timestamp,
                "location" to mapOf("latitude" to location.latitude, "longitude" to location.longitude),
                "eta" to eta?.let { mapOf("stageIndex" to it.stageIndex, "seconds" to it.seconds, "meters" to it.meters) },
            )
        }
        else -> emptyMap()
    }

    private fun lookup(value: Any?, dotted: String): Any? =
        dotted.split(".").fold(value) { node, key -> (node as? Map<*, *>)?.get(key) }

    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull
        is JsonObject -> element.mapValues { plain(it.value) }
        is JsonArray -> element.map(::plain)
    }

    private fun same(actual: Any?, expected: Any?): Boolean = when {
        actual is Number && expected is Number -> actual.toDouble() == expected.toDouble()
        else -> actual == expected
    }

    @TestFactory
    fun frames(): List<DynamicTest> {
        assumeTrue(available(), "conformance folder not found")
        return read("realtime/frames.json")["cases"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
                val event = RelayProtocol.parse(case["frame"].toString())
                val expect = case["expect"]!!.jsonObject
                val wanted = (expect["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (wanted == null) {
                    assertNull(event)
                    return@dynamicTest
                }
                assertEquals(wanted, event?.name)
                val json = apiJson(event!!)
                for ((key, want) in (expect["fields"] as? JsonObject).orEmpty()) {
                    val got = lookup(json, key)
                    assert(same(got, plain(want))) { "$key: got $got, want $want" }
                }
            }
        }
    }

    @TestFactory
    fun urls(): List<DynamicTest> {
        assumeTrue(available(), "conformance folder not found")
        return read("realtime/url.json")["cases"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
                val url = URI(
                    RelayProtocol.webSocketUrl(
                        case["baseUrl"]!!.jsonPrimitive.content,
                        case["token"]!!.jsonPrimitive.content,
                        (case["deviceId"] as? JsonPrimitive)?.content,
                    ),
                )
                val want = case["expect"]!!.jsonObject
                assertEquals(want["scheme"]!!.jsonPrimitive.content, url.scheme)
                assertEquals(want["host"]!!.jsonPrimitive.content, url.host)
                assertEquals((want["port"] as? JsonPrimitive)?.doubleOrNull?.toInt(), url.port.takeIf { it > 0 })
                assertEquals(want["path"]!!.jsonPrimitive.content, url.path)
                val query = url.rawQuery.split("&").associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to URLDecoder.decode(v, "UTF-8")
                }
                assertEquals(want["query"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }, query)
            }
        }
    }

    @Test
    fun defaults() {
        assumeTrue(available(), "conformance folder not found")
        val d = read("realtime/url.json")["defaults"]!!.jsonObject
        val options = RelayClientOptions()
        val reconnect = d["reconnect"]!!.jsonObject
        val heartbeat = d["heartbeat"]!!.jsonObject
        assertEquals(d["url"]!!.jsonPrimitive.content, options.url)
        assertEquals(reconnect["enabled"]!!.jsonPrimitive.content.toBoolean(), options.reconnect.enabled)
        assertEquals(reconnect["initialDelayMs"]!!.jsonPrimitive.content.toLong(), options.reconnect.initialDelayMillis)
        assertEquals(reconnect["maxDelayMs"]!!.jsonPrimitive.content.toLong(), options.reconnect.maxDelayMillis)
        assertEquals(reconnect["backoffMultiplier"]!!.jsonPrimitive.content.toDouble(), options.reconnect.backoffMultiplier)
        assertEquals(heartbeat["enabled"]!!.jsonPrimitive.content.toBoolean(), options.heartbeat.enabled)
        assertEquals(heartbeat["intervalMs"]!!.jsonPrimitive.content.toLong(), options.heartbeat.intervalMillis)
        assertEquals(heartbeat["timeoutMs"]!!.jsonPrimitive.content.toLong(), options.heartbeat.timeoutMillis)
        assertEquals(d["tokenRefreshBeforeExpiryMs"]!!.jsonPrimitive.content.toLong(), options.tokenRefreshBeforeExpiryMillis)
    }

    @Test
    fun outgoing() {
        assumeTrue(available(), "conformance folder not found")
        val messages = read("realtime/outgoing.json")["messages"]!!.jsonObject
        fun want(name: String) = messages[name]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.replace("<taskId>", "task-1") }
        assertEquals(want("subscribe"), RelayProtocol.subscribe("task-1"))
        assertEquals(want("unsubscribe"), RelayProtocol.unsubscribe("task-1"))
        assertEquals(want("ping"), RelayProtocol.PING)
        assertEquals(
            Json.parseToJsonElement(RelayProtocol.encode(RelayProtocol.subscribe("task-1"))),
            Json.parseToJsonElement(JsonObject(want("subscribe").mapValues { JsonPrimitive(it.value) }).toString()),
        )
    }
}

class TaskTrackerStateTest {
    private fun event(json: String) = RelayProtocol.parse(json)!!

    @Test
    fun followsADelivery() {
        var state = TaskTrackerState("t1")
        state = state.applying(event("""{"type":"TASK_ASSIGNED","taskId":"t1","status":"ASSIGNED","currentStageIndex":0}"""))
        state = state.applying(
            event("""{"type":"RIDER_LOCATION_UPDATE","taskId":"t1","riderId":"r1","location":{"latitude":6.45,"longitude":3.39},"eta":{"stageIndex":0,"seconds":420,"meters":2350},"timestamp":"2026-10-28T10:00:00.000Z"}"""),
        )
        assertEquals(TaskStatus.ASSIGNED, state.status)
        assertEquals(6.45, state.rider?.latitude)
        assertEquals(2350, state.eta?.meters)
        assertEquals(java.time.Instant.parse("2026-10-28T10:07:00Z").toEpochMilli(), state.eta?.arrivesAtMillis)

        state = state.applying(event("""{"type":"STAGE_COMPLETED","taskId":"t1","stageIndex":0,"nextStageIndex":1}"""))
        assertEquals(1, state.currentStageIndex)
        assertNull(state.eta, "the pickup's ETA no longer applies")

        state = state.applying(event("""{"type":"TASK_COMPLETED","taskId":"t1","status":"COMPLETED"}"""))
        assert(state.finished)
        assertEquals(listOf(TaskEventType.COMPLETED, TaskEventType.STAGE_COMPLETED, TaskEventType.ASSIGNED), state.events.map { it.type })
    }

    @Test
    fun ignoresOtherTasks() {
        val state = TaskTrackerState("t1").applying(event("""{"type":"TASK_CANCELLED","taskId":"other","status":"CANCELLED"}"""))
        assertNull(state.status)
        assert(state.events.isEmpty())
    }

    @Test
    fun tokenExpiry() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":2000}""".toByteArray())
        assertEquals(600L, RelayProtocol.secondsUntilExpiry("h.$payload.s", 1_400_000))
        assertNull(RelayProtocol.secondsUntilExpiry("not-a-jwt"))
    }
}
