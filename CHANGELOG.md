# Changelog

## 1.8.0 — 2026-11-03

- Keep looking for a rider and scheduled pickups ([dispatch strategies](https://docs.sendrelay.com.ng/tasks/keep-looking-and-scheduling)).
- `TaskStatus.SCHEDULED` and `SEARCHING`, and the `DISPATCH_STARTED`, `SEARCHING` and `RIDERS_NEARBY` task events, with `strategy`, `pickupAt`, `searchExpiresAt` and `ridersNearby`. `TaskTrackerState.searchExpiresAt` to count down to.
- `TaskEvent.rawStatus` holds the status exactly as the server sent it. `status` is null for a status this version doesn't know, as before, so new Relay statuses (for tasks that keep searching for a rider) never stop an event from arriving. Use an `else` branch when you `when` over `TaskStatus`.

## 1.7.0 — 2026-10-29

- First release, versioned with the other Relay app SDKs (they move together). Matches the shared SDK conformance suite (packages/sdks/conformance).
