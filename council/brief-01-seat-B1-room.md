Coder seat B1 — ELAY Phase 1: Room local layer (entities, converters, DAOs, per-account cache). You have WRITE access in your own clone; your diff is collected as a patch for concierge review.

READ FIRST: `contracts/phase1-planner.md` (§1 column shapes, §3 outbox semantics, §6 your paths), `adr/ADR-003`, `adr/ADR-009` (cache horizon + per-account partitioning), the frozen interfaces in `shared/src/commonMain/kotlin/dev/elay/domain/model/PlannerModels.kt`, and the existing skeleton in `shared/src/*/kotlin/dev/elay/data/local/` (OutboxEntity/OutboxDao/ElayDatabase + platform builders + iosTest RoomSmokeTest) — extend it, don't fork it.

BUILD, only under `shared/src/commonMain|androidMain|iosMain/kotlin/dev/elay/data/local/` and test source sets:
1. Entities mirroring contract §1: GoalEntity, MilestoneEntity, TaskEntity (tags as JSON string column), CaptureEntity, TimeBlockEntity — primitives only (String ids, Long epoch-ms instants, String ISO dates, String tz ids, String enum wire values, Int/Long numerics) + local sync metadata (`syncStatus` PENDING/SYNCED/CONFLICT, `localUpdatedAtEpochMs`). Explicit mapper functions entity↔domain (in `data/local/mapping/`); no broad TypeConverters hiding validation; enum mapping via the domain enums' `wire` values.
2. DAOs per the frozen repository needs: GoalDao (observeAll/get/upsert/delete), MilestoneDao (observeForGoal/upsert/delete), TaskDao (observeToday(startMs,endMs)/observeUnscheduled/observeForGoal/get/upsert/delete), CaptureDao (observeInbox/get/upsert/clarify/delete), TimeBlockDao (observeRange(startMs,endMs)/observeForTask/upsert/delete), keep OutboxDao, plus a @Transaction PlannerWriteDao for atomic "apply ACK: update row + mark outbox" writes. Flow-returning observe queries.
3. Bump ElayDatabase to include the new entities (still version 1 — pre-release, no migration path needed yet; keep exported schema).
4. Tests: extend the iosTest RoomSmokeTest with one new entity round-trip (create/write/close/reopen). Add host tests (commonTest) for: mapper round-trips per entity (incl. every enum value), TaskDao.observeToday boundary (block exactly at start/end ms), OutboxDao FIFO order `(createdAtEpochMs, operationId)`.

TOUCH ONLY the paths above. Never: gradle files (Room/KSP already wired), domain/, data/remote/, sync/, ui/, .github/, contracts/.

VERIFY on this machine before reporting: `export JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'` then `./gradlew.bat :shared:allTests ktlintCheck detekt --console=plain --no-daemon` — all green, and state the executed-test count from `shared/build/test-results` (a zero-test green is a failure). Windows: write scripts to files; Python text mode writes CRLF (`newline=""`).

Report: what you built · commands + last lines proving green · executed-test count · anything UNVERIFIED. Begin with the model you are running as.
