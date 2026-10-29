# Relay SDK for Android (Kotlin and Java)

Official [Relay](https://sendrelay.com.ng) SDK for Android: show a delivery's status, the rider on a map and a live arrival time. One library for Kotlin and Java, and it also runs on any JVM 11+.

```kotlin
// build.gradle.kts
implementation("ng.com.sendrelay:relay-android:1.7.0")
```

Android 7+ (API 24). Built on OkHttp.

## Kotlin

```kotlin
// Your server mints the token with its API key (relay.auth.createWebSocketToken).
val relay = RelayRealtimeClient({ taskIds -> api.trackingToken(taskIds) })
val tracker = TaskTracker(relay, taskId)

lifecycleScope.launch {
    tracker.state.collect { state ->
        statusView.text = state.status?.value ?: "Finding a rider"
        state.eta?.let { etaView.text = "${(it.secondsRemaining() + 59) / 60} min away" }
    }
}
tracker.start()   // in onStart
tracker.stop()    // in onStop
```

## Java

```java
RelayRealtimeClient relay = new RelayRealtimeClient(taskIds -> api.trackingToken(taskIds));
TaskTracker tracker = new TaskTracker(relay, taskId);
tracker.addListener(state -> runOnUiThread(() -> render(state)));
tracker.start();
```

The token provider runs on a background thread, so a blocking HTTP call is fine. Listeners are called on a background thread too; switch to the UI thread before touching views.

The client reconnects with backoff, resubscribes after a reconnect, sends heartbeats and refreshes the token before it expires. Call `relay.connect()` when your app returns to the foreground to reconnect at once.

[Full documentation](https://docs.sendrelay.com.ng/sdks/android-sdk)
