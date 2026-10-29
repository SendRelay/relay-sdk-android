package ng.com.sendrelay.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The API as a Java app sees it: no coroutines, no Kotlin-only types. */
class JavaUsageTest {
    @Test
    void javaCanConfigureListenAndTrack() throws Exception {
        RelayClientOptions options = new RelayClientOptions(
                "http://localhost:8790", "kiosk-1", new ReconnectOptions(), new HeartbeatOptions());
        RelayRealtimeClient relay = new RelayRealtimeClient(taskIds -> "token-for-" + String.join(",", taskIds), options);

        List<String> names = new ArrayList<>();
        AutoCloseable subscription = relay.addListener(event -> names.add(event.getName()));

        TaskTracker tracker = new TaskTracker(relay, "t1");
        List<TaskTrackerState> states = new ArrayList<>();
        tracker.addListener(states::add);

        RelayEvent assigned = RelayProtocol.parse(
                "{\"type\":\"TASK_ASSIGNED\",\"taskId\":\"t1\",\"status\":\"ASSIGNED\",\"currentStageIndex\":0}");
        TaskTrackerState state = new TaskTrackerState("t1").applying(assigned);
        assertEquals(TaskStatus.ASSIGNED, state.getStatus());
        assertNull(state.getEta());

        String url = RelayProtocol.webSocketUrl("https://api.sendrelay.com.ng", "jwt", "kiosk-1");
        assertTrue(url.startsWith("wss://api.sendrelay.com.ng/v1/realtime/ws?token=jwt"));
        assertEquals("ping", RelayProtocol.PING.get("action"));
        assertNotNull(relay.getEvents());

        subscription.close();
        relay.close();
    }
}
