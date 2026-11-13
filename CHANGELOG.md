# Changelog

## 1.10.0 — 2026-11-09

- `TaskEvent.revision` and `RevisionGate`. Task events carry `revision` (the task's revision after the event, as on webhooks). The client delivers each task's events in order and once: a frame at or below the last revision it delivered for that task is dropped. Location updates and frames without a revision are always delivered.

## 1.9.0 — 2026-11-08

- New `TaskEventType` values: `STAGE_ARRIVED`, `DETAILS_UPDATED` and `RIDER_UNASSIGNED` (tasks created with `onRiderCancel: FIND_ANOTHER`), with `stageType` and `researchesLeft`. `TaskTracker` moves the task back to PENDING and clears the rider and ETA on `RIDER_UNASSIGNED`.

## 1.8.0 — 2026-11-03

- Keep looking for a rider and scheduled pickups ([dispatch strategies](https://docs.sendrelay.com.ng/tasks/keep-looking-and-scheduling)).
- `TaskStatus.SCHEDULED` and `SEARCHING`, and the `DISPATCH_STARTED`, `SEARCHING` and `RIDERS_NEARBY` task events, with `strategy`, `pickupAt`, `searchExpiresAt` and `ridersNearby`. `TaskTrackerState.searchExpiresAt` to count down to.
- `TaskEvent.rawStatus` holds the status exactly as the server sent it. `status` is null for a status this version doesn't know, as before, so new SendRelay statuses (for tasks that keep searching for a rider) never stop an event from arriving. Use an `else` branch when you `when` over `TaskStatus`.

## 1.7.0 — 2026-10-29

- First release, versioned with the other SendRelay app SDKs (they move together). Matches the shared SDK conformance suite (packages/sdks/conformance).
