# Trainworks — Progress / Handoff Notes

> **Read this first if you're picking up a new session.** This is a working log of what's
> actually been built and tested, as opposed to `DESIGN.md` and `design/*.md`, which describe the
> overall plan (those are still accurate for architecture/intent — this file is "what's true
> right now" and "what's next").

**Repo:** local git + pushed to https://github.com/jordstyer/Trainworks (public), `master` branch.
**Last commit as of writing:** `8734b95` — "Revert rotation-while-moving: real bug, not worth patching blind"
**Build:** `./gradlew build` passes. Forge 1.20.1, mod id `trainworks`.

---

## 1. How to keep working on this

- Design docs (read for *why*, not just *what*): [DESIGN.md](DESIGN.md) (overview + all 7 open
  decisions, all resolved), [design/track-graph.md](design/track-graph.md) (Phase 1),
  [design/trains.md](design/trains.md) (Phase 2/3 — **has the up-to-date milestone checklist**,
  check it before assuming something isn't done), [design/automation.md](design/automation.md)
  (Phase 4/5, not started), [design/polish.md](design/polish.md) (Phase 6, not started).
- Every design doc's checklist is kept current — `[x]` items are genuinely done and in-game
  tested unless the note next to them says otherwise. Trust the checklist over guessing from
  code alone.
- Test loop: `./gradlew build` (compiles fast, ~8s once warm), then the user runs the actual
  client themselves (`gradlew runClient` from their own terminal — I don't have a way to launch
  or see the running game). I *do* have read access to `run/logs/latest.log` and `debug.log` on
  this machine, which was decisive for finding two of the bugs below — check those first before
  guessing when something's reported broken.
- API verification discipline that's paid off repeatedly: before using an unfamiliar Minecraft/
  Forge API, extract the actual decompiled source from
  `~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.0_mapped_official_1.20.1/forge-1.20.1-47.4.0_mapped_official_1.20.1-sources.jar`
  (official-mapped names, not SRG) rather than guessing signatures from memory. This caught
  several would-be compile errors and at least one subtle behavioral assumption before it shipped.

---

## 2. What's actually working, end to end, in-game

### Phase 1 — Track graph (100% done, all checklist items confirmed)
- Place a **Track Anchor** block; it records a precise facing (from look angle at placement,
  not snapped) into a server-side `TrackGraph` (`SavedData`). The block's visual facing indicator
  snaps to 4 directions (90°) — a Minecraft blockstate-rotation limitation, not a data limitation.
- **Track Linking Tool** (hammer): right-click two anchors to connect them. Generates a piecewise
  Bézier curve (straight lead-in/out at each anchor + curved middle section) between them, with
  connect-time validation (max grade, min curve radius, obstruction) that rejects bad connections
  before creating anything.
- Sneak-right-click an unconnected anchor with the hammer to re-face it before connecting.
- The connection renders as a **continuous curved ribbon** (not discrete blocks) via a custom
  `BlockEntityRenderer` (`TrackSegmentRenderer`) — each `TrackSegmentBlock` stores a short local
  polyline synced to the client, and the renderer draws it as a flat textured strip. This was the
  single highest-risk piece of Phase 1 and works correctly with no known issues.
- Placeholder pixel-art textures exist for anchor, segment (ballast/tie/rail), and the hammer item
  (generated via a one-off Python/Pillow script, not checked into a build step — regenerate by
  hand if textures need to change).
- Breaking an anchor cleanly cascades: removes its graph node, any edges through it, and the
  now-orphaned segment/ribbon blocks in the world.

**Not done:** the chunk→edge spatial index (design/track-graph.md's last unchecked item) — pure
infrastructure, deliberately skipped for now since nothing needs it yet.

### Phase 2 — Trains (mostly done; driving is the one big remaining piece)
- **Train Bogie** block: place on top of a `TrackSegmentBlock` (auto-breaks otherwise, like a
  flower needing dirt). Records `(edgeId, distance)` approximated from the segment beneath it.
- **Assembly** (`CarriageAssembler`): build any structure directly above a bogie, right-click the
  bogie empty-handed. Flood-fills the connected structure (stopping at air and at any
  track/anchor/bogie block), captures block *states* (not block-entity data — a chest's contents
  do **not** survive assembly, known gap), removes the original blocks, and spawns a
  `CarriageEntity`.
- **One or two bogies both work.** With two bogies connected by one structure (both must be on
  the *same* track edge or assembly is rejected), the carriage's spawn position is the midpoint
  between their curve positions and its stored yaw comes from the line between them — this is the
  real fix for a single rigid structure not being able to perfectly trace a curve.
- **The carriage is a genuine custom `Entity`** (`CarriageEntity`), not a block entity, since it
  needs to move freely. Captured blocks are stored as `(Vec3 relativeOffset, BlockState)` pairs
  (fractional, not integer — the two-bogie midpoint usually isn't block-aligned), synced to
  clients via Forge's `IEntityAdditionalSpawnData` and rendered via a custom `EntityRenderer`
  (`CarriageRenderer`) that calls `BlockRenderDispatcher.renderSingleBlock` per captured block.
- **Unmanned movement proof**: if assembled with a track reference, the carriage advances along
  its edge every server tick at a hardcoded ~1 block/sec test speed (`CarriageEntity
  .TEST_SPEED_BLOCKS_PER_TICK`), with smooth client-side interpolation. **Confirmed working.**
  No player control yet — that's the next thing to build (see §4).

**Known, deliberate limitation right now:** a moving carriage does **not** visually rotate to
match the track as it travels — it keeps whatever orientation it was built in. Two attempts at
fixing this (documented in detail in `design/trains.md` and in git history around commits
`d595814`–`8734b95`) both introduced real bugs and were reverted. This needs a fresh, careful
design pass, not another quick patch — see §4 for the recommended approach.

**Not done:** control stand / manual throttle+brake / actual driving. Disassembly (reverse of
assembly). Both are unchecked in `design/trains.md`'s Phase 2 checklist.

### Phases 3–6 (automation, signals, polish)
Not started. Design docs exist and are believed still accurate; no code written.

---

## 3. Bugs found and fixed (worth knowing about even though they're resolved — several are
non-obvious Minecraft/Forge gotchas that could recur elsewhere in this codebase)

1. **Blockstate rotation only supports 0/90/180/270.** Tried an 8-way (45°) facing indicator on
   the anchor block; Minecraft's `BlockModelRotation` is a fixed 16-entry table of exactly the
   90°-multiple X/Y combinations, so anything else silently fails to resolve and the block
   renders as the missing-model checkerboard. Fixed by dropping to 4-way. (commit `1165a25`)
2. **Breaking an anchor didn't clean up its track segment blocks** — only the graph data was
   removed, leaving orphaned ribbon blocks in the world referencing a now-nonexistent edge.
   (`91aaefc`)
3. **Curve tangent direction bug**: node B's facing axis was resolved against the *reverse*
   (B→A) direction instead of the same A→B direction node A uses — picked the wrong half of B's
   axis, producing a curve that ignored B's actual facing. Coincidentally looked fine when both
   anchors were aimed directly at each other (both choices collapse to the same answer there),
   which is exactly why it went unnoticed until tested with genuinely different facings. (`29d492b`)
4. **A single whole-span Bézier is only exactly tangent at one infinitesimal point** — track
   still visibly curved right up to the anchor. Fixed with a piecewise straight-lead-in/curve/
   straight-lead-out construction. (`a7ea389`, lengthened further in `cbe933a`)
5. **Obstruction validation checked distance=0**, which is the anchor's own block position — every
   connection would have failed validation immediately (anchor isn't air/replaceable). Caught
   before it ever reached testing. (`9f35458`)
6. **Carriage invisible after assembly**: `Entity.getBoundingBoxForCulling()` defaults to the
   declared `EntityType` size (1×1×1), not the actual rendered extent — frustum culling can skip
   the whole `render()` call for anything bigger. (`095ff77`)
7. **Custom spawn data never actually reached the client**: implementing
   `IEntityAdditionalSpawnData` does *nothing* by itself — `Entity.getAddEntityPacket()` must be
   explicitly overridden to return `NetworkHooks.getEntitySpawningPacket(this)`, or the client
   gets a plain vanilla spawn packet with none of the custom payload. (`3ebcb86`)
8. **Carriage rendered rotated 90° from correct** on a curve: `Axis.YP.rotationDegrees(angle)` is
   a standard right-handed (counter-clockwise-viewed-from-above) rotation, but Minecraft yaw
   increases *clockwise* viewed from above (verified against `Direction.fromYRot`) — opposite
   handedness, needs negating. (`8d58084`)
9. **That still wasn't right** — realized rotating captured offsets at all was conceptually wrong
   for a *static* carriage: the blocks were captured already aligned with the track (that's what
   building "coincident with the track" means), so applying the track's absolute yaw on top
   double-counts the alignment. Reverted to no rotation for the static case. (`5ba129c`)
10. **Jittery movement**: the base `Entity.lerpTo()` has no interpolation at all (just snaps) —
    only specific vanilla subclasses (`LivingEntity`, vehicles) override it to ease over several
    ticks. `CarriageEntity` now does too. (`67f4b0c`)
11. **Rotation looked frozen while moving**: vanilla's generic rotation network sync only sends
    an update once accumulated change crosses roughly a 1.4° threshold since the last value it
    actually sent — a carriage turning very gradually every tick could take a long time (or,
    within a short test, effectively never) to trip it. Routed the value through
    `SynchedEntityData` instead, which has no such gate. (`a4a6c55`)
12. **That introduced a worse bug**: confirmed via the entity's debug hitbox that *position* was
    correct while the *rendered blocks* were displaced far away — almost certainly a
    synchronization-timing mismatch between the two separately-synced yaw values (`assemblyYaw`
    via the spawn packet, `trackYaw` via `SynchedEntityData`). Reverted entirely rather than
    take a fourth speculative fix. (`8734b95`)

**Pattern worth internalizing:** rendering/entity-sync bugs in this codebase have consistently
turned out to have a real, findable root cause (a missing override, a wrong assumption about a
framework default, a genuine handedness mismatch) rather than needing "just try flipping a sign
and see" — when something looks broken, it's been worth tracing into the actual decompiled
source rather than guessing repeatedly.

---

## 4. Recommended next step: driving (control stand + throttle/brake)

This is the last unchecked item in Phase 2 before it's "done." Suggested approach, informed by
the rotation saga above:

1. **Design the rotation-while-moving mechanism deliberately this time**, before writing driving
   input handling. Leading candidate not yet tried: skip trying to sync a second "current yaw"
   value at all — instead have the **client-side renderer derive rotation itself** from the
   entity's own already-correctly-interpolated position history (e.g., compare current
   interpolated position to the position a few ticks ago to derive a facing direction), or
   alternatively give the carriage real client-side prediction of its own track position (sync
   `edgeId`/`distance` once via spawn data, let the *client* independently compute
   `curve.positionAt`/`yawAt` each frame using the same track graph — but the client doesn't have
   `TrackGraphSavedData`, only the server does, so this would need the curve geometry itself
   synced once at spawn, not recomputed from a live graph). Worth 10 minutes of design thought
   before implementation, given this exact feature has already eaten three debugging cycles.
2. **Control stand**: needs a way to designate one captured block as "the thing you interact with
   to drive this carriage." Since `CapturedBlock` currently only stores `(Vec3, BlockState)` with
   no identity/marker concept, this likely needs either (a) a dedicated `ControlStandBlock` type
   that gets special-cased during capture (flag it, store its relative offset separately on the
   entity), or (b) generic entity interaction (`Entity.interact`/`interactAt`) that translates the
   click's hit position back to a relative offset and checks if *any* captured block there is a
   control stand.
3. **Throttle/brake** needs a player input path to the server. Simplest first version: right-click
   the control stand to toggle "moving at fixed test speed" vs. "stopped" — a big simplification
   from the design doc's eventual notched-throttle vision, but proves the input-reaches-server
   path before adding nuance.
4. Once basic start/stop works, **disassembly** (the other unchecked Phase 2 item) is a much
   smaller, more contained task: reverse of assembly, placing `capturedBlocks` back into the
   world at the carriage's current aligned position and despawning the entity. Do this once
   driving works, since by then there's a reason to want to stop and rebuild a carriage.

---

## 5. Scaffold/tooling notes (from earlier in the project, still true)

- Gradle wrapper, `build.gradle`, `gradle.properties` all set up and working — `mod_id=trainworks`,
  Forge 47.2.0, mappings `official` 1.20.1, Java 17 toolchain (auto-provisioned even though the
  system JVM is 21).
- Package layout mirrors the phases: `com.trainworks.track` (Phase 1), `com.trainworks.train`
  (Phase 2/3), `com.trainworks.automation` (Phase 4/5, empty so far), `com.trainworks.client`
  (all client-only rendering code, registered via `TrainworksClientEvents`,
  `@Mod.EventBusSubscriber(..., value = Dist.CLIENT)`).
- No CI, no tests — this is solo-dev/prototype-stage tooling by design so far.
