# Trains — Technical Design (Phase 2/3)

> Data model and math for carriages built from blocks: how they're assembled, how they derive
> position/rotation from the track graph, and how a multi-carriage train moves and turns as one
> unit. Builds directly on [track-graph.md](track-graph.md) — read that first.

Companion to [../DESIGN.md](../DESIGN.md) §3–§4.

---

## 1. Scope

Phase 2: single carriage — assemble, disassemble, ride, drive manually, correct orientation on
curves/slopes. Phase 3: multiple carriages coupled into one train, junctions, manual switching.
This doc covers both since the data model needs to support multi-carriage from day one even
though Phase 2 only exercises the single-carriage case.

---

## 2. Core Data Model

### 2.1 Bogie — the thing that actually touches the track
| Field | Type | Notes |
|---|---|---|
| `edgeId` | long | Which edge (from the track graph) the bogie currently sits on. |
| `distance` | double | Distance along that edge. |
| `powered` | bool | Whether this bogie contributes tractive force (affects §4.2 physics). |

A bogie's world position + facing come directly from evaluating that edge's LUT at `distance`
(see track-graph.md §3.2) — a bogie never stores its own position independently.

### 2.2 Carriage — one assembled unit
| Field | Type | Notes |
|---|---|---|
| `id` | long | |
| `bogies` | list (1–2 `Bogie` refs) | Almost always 2 (front/back); a single-bogie carriage is treated as two virtual points offset by half its fixed length along the track tangent, so the math never needs a special case (§3.3). |
| `blockStructure` | template | Relative block positions + states + block-entity data (chest contents, etc.), captured at assembly time — conceptually identical to a structure block template. |
| `length` | double | Fixed at assembly time from the bogie spacing. Used for the single-bogie virtual-point trick and for carriage-to-carriage spacing checks. |

### 2.3 Train — the logical object (not an entity)
| Field | Type | Notes |
|---|---|---|
| `id` | long | |
| `carriages` | ordered list | Front to back. |
| `occupiedPath` | deque\<long edgeId\> | Every edge currently spanned by any part of the train, oldest (tail) to newest (lead). See §4.1. |
| `leadDistance` | double | Distance of the frontmost point along the current lead edge. |
| `velocity` | double | Signed — direction is "along occupiedPath order," reversing sign reverses travel direction without needing to rebuild the deque. |
| `timetable` | Timetable ref \| null | Present only if a control stand in this train holds one (§5, DESIGN.md §5.2). |
| `mode` | enum: MANUAL / AUTOMATED / IDLE | |

Trains live in a **server-side train manager** (a `SavedData`-backed registry), not as a single
entity — see §6 for how they're rendered.

---

## 3. Position & Orientation Math

### 3.1 The core problem
A train can physically span **multiple edges at once** (front carriage past a junction onto a new
edge while the rear carriage is still on the previous one). Every bogie's position needs to be
derivable relative to the train's single `leadDistance`, without re-walking the whole graph from
scratch every tick.

### 3.2 The occupied-path deque
- `occupiedPath` holds the ordered list of edges the train currently touches, tail (oldest/rearmost)
  to head (newest/lead).
- To find any point's position at "distance `d` behind the lead": walk backward from the lead
  edge through `occupiedPath`, subtracting edge lengths, until the remaining offset lands inside
  an edge's length — then evaluate that edge's LUT at the remaining offset.
- **Advancing**: each tick, `leadDistance += velocity * dt`. If it exceeds the current lead edge's
  length, the overflow carries into the *next* edge — determined by junction resolution (§3.4) —
  which gets pushed onto `occupiedPath`.
- **Trimming**: once the rearmost bogie's position moves past the tail edge entirely, pop it off
  `occupiedPath`. This keeps the deque bounded to roughly "edges the train's length currently
  spans," not the whole route history.
- **Reversing** just flips the sign of `velocity`; the same deque and math work in both directions
  since it's an ordered path, not an inherently one-directional structure.

### 3.3 Deriving carriage transform from bogies
For a 2-bogie carriage with world positions `posFront`, `posBack` (from §3.2):
```
center  = (posFront + posBack) / 2
forward = normalize(posFront - posBack)
yaw     = atan2(forward.x, forward.z)
pitch   = atan2(forward.y, horizontalLength(forward))
roll    = 0                                    // no super-elevation/banking in v1
```
A single-bogie carriage uses its one real bogie plus a **virtual second point** offset by
`-length` along the edge tangent at that bogie (or `+length` for a rear-facing single bogie) —
same formula, no special-cased math path.

### 3.4 Junction resolution (which edge does the lead enter?)
When the lead bogie's distance overflows into a junction node:
- **Automated train**: the router (DESIGN.md §5.3) has already committed a full edge-path for the
  current timetable leg; the next edge is simply read off that precomputed path.
- **Manual train**: read the player's current A/D steering input; if none given and the junction
  has a stored default direction, use that; otherwise stop the train short of the junction and
  prompt the driver (avoids silently picking a random branch).

---

## 4. Movement Physics (kept intentionally simple)

### 4.1 Per-tick integration
```
netForce   = tractiveForce(throttle, poweredBogieCount) - brakingForce(brakeInput) - drag(mass)
accel      = netForce / mass
velocity  += accel * dt   (clamped to topSpeed(mass, poweredBogieCount))
leadDistance += velocity * dt
```

### 4.2 Mass & tractive force
- `mass` = sum of carriage block counts (every block in every carriage's `blockStructure`
  contributes equally for v1 — no per-block weight table; simplicity over realism).
- `tractiveForce` scales with `throttle` (notched 0–N, DESIGN.md §4.2) and the count of `powered`
  bogies among all carriages — more powered bogies (i.e., more locomotive units) pull more mass,
  giving a concrete reason to build multi-locomotive trains for heavy consists.
- Fuel (if enabled, DESIGN.md §4.3): tractiveForce is zero unless the relevant locomotive
  carriage's firebox has fuel; fuel is consumed at a rate proportional to throttle, not distance.

### 4.3 Signals (forward reference)
Braking is also forced to zero net throttle when the next signal section ahead is reserved by
another train (DESIGN.md §5.4) — implemented as an input to `brakingForce` alongside the driver's
own brake lever. Manual and automated trains use the exact same check.

---

## 5. Assembly & Disassembly

### 5.1 Assembly algorithm
1. Player places **bogie blocks** directly on track segment blocks within a station's assembly
   zone (DESIGN.md §3.1). Each bogie block records its `edgeId`/`distance` immediately (read off
   the track segment it sits on).
2. Player builds freely above/around the bogies, then triggers assembly (interact with the
   station in assembly mode).
3. **Carriage boundary rule: require at least a 1-block gap between adjacent carriages' block
   footprints at assembly time.** This sidesteps a hard ambiguity — if two carriages' shells
   touch directly, a flood-fill scan can't tell where one carriage ends and the next begins. A
   mandatory gap (matching how real rolling stock has visible gaps between cars anyway) makes the
   scan unambiguous: flood-fill outward from each bogie pair's footprint, stop at the gap.
4. Each flood-filled region becomes one `Carriage.blockStructure`; original blocks are removed
   from the world and the train entity/manager entry is created.

### 5.2 Disassembly
- Only performable at a station in assembly mode, with the train stationary and aligned to a
  straight, level section (avoids re-placing blocks at fractional/rotated positions — keeps this
  simple rather than solving arbitrary-rotation block placement).
- Reverses assembly: each carriage's `blockStructure` is placed back into the world at its current
  aligned position, including block-entity data (chest contents, etc.).

### 5.3 Limits (tune during Phase 2 prototyping)
- Max blocks per carriage (prevents absurd single-carriage builds and caps render/collision cost).
- Max carriages per train (prevents runaway server cost from mega-trains).
- Both should be config values, not hardcoded, so server owners can tune for their hardware.

---

## 6. Rendering

- No literal per-block entities — a carriage renders as **one cached block-structure buffer**
  (built once at assembly time from `blockStructure`) drawn each frame at an interpolated
  transform (`center`, `yaw`, `pitch` from §3.3), conceptually the same technique Create uses for
  contraptions.
- Client receives `occupiedPath` head/lead distance + velocity from the server and **interpolates
  locally** between server updates rather than snapping every tick (DESIGN.md §6.2) — smooths out
  network tick jitter.
- Passenger/seat positions are fixed offsets within a carriage's local space, transformed by the
  same per-tick matrix as the block structure.

---

## 7. Coupling

- Adjacent carriages in a train's `carriages` list are implicitly coupled — no separate coupling
  data needed as long as they're in the same `Train` object.
- **Coupler tool**: interact with the gap between two adjacent carriages to split the train into
  two independent `Train` objects, each keeping its own trailing sub-list of carriages and a
  freshly computed `occupiedPath`/`leadDistance` derived from their existing bogie positions
  (no visible position jump — this is a bookkeeping split, not a physical move).
- Splitting is **only valid at a carriage boundary** (the mandatory gap from §5.1 doubles as the
  only valid split point) — never mid-carriage.
- A `timetable` stays with whichever resulting train still contains the control stand that held it.

---

## 8. Open Sub-Decisions (empirical — settle during Phase 2/3 prototyping)

- Minimum inter-carriage gap size (starting guess: 1 block).
- Max blocks per carriage / max carriages per train (config defaults).
- Exact tractive-force and topSpeed formulas as functions of mass + powered-bogie count
  (start rough, tune by feel once a locomotive + a few wagons can actually be driven).
- Whether reversing (loco pushing wagons backward) is fully supported in Phase 2 or deferred —
  the math in §3.2 supports it either way, so this is a scope/testing-time decision, not an
  architecture one.

---

## 9. Phase 2/3 Milestone Checklist

**Phase 2 (single carriage):**
- [x] Bogie block records `edgeId`/`distance` on placement (approximated from the
      `TrackSegmentBlockEntity` directly below it -- that segment's own representative distance,
      not the bogie's exact position; tighten later if needed). Must sit on a track segment,
      auto-breaks otherwise. Confirms via chat message on placement.
- [x] Assembly flood-fill scan → `blockStructure` (`CarriageAssembler`): right-click an empty-hand
      on a bogie to flood-fill whatever's built directly above it (stopping at air and at
      track/anchor/bogie blocks so it can't accidentally eat the track), capturing block state only
      -- **no block-entity data yet** (a chest's contents are not preserved). Removes the captured
      blocks and the bogie from the world and spawns a `CarriageEntity`.
- [x] Renderer (§6) for a single-bogie carriage, via `CarriageRenderer` (a `BlockRenderDispatcher
      .renderSingleBlock` call per captured block, not full in-world tesselation/AO) -- simple and
      reliable for a first pass. **Confirmed working in-game** after fixing two real bugs: an
      undersized culling box (`getBoundingBoxForCulling` needs to reflect the actual captured
      extent, not the tiny declared `EntityType` size) and a missing `getAddEntityPacket` override
      (implementing `IEntityAdditionalSpawnData` alone does nothing -- the entity must also opt
      into Forge's custom spawn packet, or the client never receives the captured blocks at all).
- [x] Single-bogie position/orientation from the track (§3.3, one-point case): `CarriageAssembler`
      reads the bogie's edge/distance *before* removing it, computes `curve.positionAt`/`yawAt` at
      that distance, and spawns the carriage there with that yaw instead of at the bogie's raw
      block position. **Renderer applies no rotation** -- tried whole-carriage rotation via
      `Axis.YP.rotationDegrees` first, but in-game testing showed it rotating structures *away*
      from correct alignment. Root realization: captured offsets come straight from the world,
      where the player necessarily built already aligned with the physical track ("coincident
      with the track") -- they're already correctly oriented, so rotating by the track's absolute
      yaw double-counts that alignment. Rotation only becomes meaningful once a carriage can move
      to a point with a *different* heading than where it was assembled, and even then what's
      needed is the *delta* between assembly-time and current yaw, not the raw angle. The entity
      still stores yaw (for that future use); the renderer just doesn't consult it yet. **Confirmed
      correct in-game** for a straight track section; on a curve, a rigid single-bogie build can
      only ever be as aligned as however precisely the player aimed while building relative to the
      curve's local tangent (a rigid body can't perfectly trace a continuous curve) -- confirmed
      as expected behavior, not a bug, and exactly the motivation for the two-bogie case below.
- [x] Two-bogie position/orientation (§3.3, full case): the flood-fill in `CarriageAssembler` now
      detects a *second* connected bogie (stopping the fill at it, same as it stops at track/
      anchor blocks) instead of only ever working from one. With two bogies found (both must
      reference the same track edge, or assembly is rejected), the carriage's position becomes the
      midpoint between their two curve positions, and yaw comes from the line between them --
      matching how a real railcar's orientation follows its two truck positions rather than a
      single freehand build direction. `CarriageEntity.CapturedBlock.relativeOffset` changed from
      integer `BlockPos` to fractional `Vec3` to support a midpoint that generally isn't
      block-aligned (NBT/network sync updated to match). Single-bogie carriages still work
      (degenerate one-point case). More than two connected bogies is rejected outright for now.
      **Confirmed working in-game.**
- [x] Unmanned movement proof (no player control yet -- deliberate sub-step before driving):
      `CarriageEntity.tick()` advances a stored `(edgeId, distance)` by a fixed test speed
      (~1 block/sec) every server tick and calls `setPos`/`setYRot` from `curve.positionAt`/
      `yawAt` at the new distance. Stops at the end of its edge (no junction-crossing logic yet,
      out of scope for this proof). **Confirmed working in-game**, including after fixing two
      real bugs:
      - Jittery motion: the base `Entity.lerpTo()` has no interpolation at all (just snaps) --
        only specific vanilla subclasses (`LivingEntity`, vehicles) override it to ease over
        several ticks. `CarriageEntity` now does too.
      - Rotation-while-moving was attempted (a delta between a `SynchedEntityData`-synced
        "current track yaw" and assembly-time yaw, since vanilla's generic rotation sync has its
        own ~1.4° change threshold that a slow, continuously-turning carriage might never trip)
        but produced a *worse* bug: confirmed via the entity's debug hitbox that position was
        correct while the rendered blocks were displaced far away, almost certainly a
        synchronization-timing mismatch between the two separately-synced yaw values.
        **Deliberately reverted** -- `CarriageRenderer` renders with no rotation at all for now
        (matching the already-confirmed-correct static case), and a moving carriage keeps its
        assembly-time visual orientation rather than turning to match the curve. Rotation-while-
        moving is deferred to when the real driving feature is built, where it can be designed
        more carefully instead of bolted onto this proof-of-concept.
- [ ] Control stand: manual throttle/brake, drives a single carriage along straight + curved +
      sloped track built in Phase 1
- [ ] Disassembly reverses assembly correctly, including a chest's contents

**Phase 3 (multi-carriage + junctions):**
- [ ] Multi-carriage assembly with mandatory gap rule
- [ ] `occupiedPath` deque correctly spans a junction mid-train
- [ ] Manual junction steering (A/D) at a switch, including the "stop and prompt" fallback
- [ ] Coupler tool: split a train into two at a valid gap, no position jump
- [ ] Manual test: a 3+ carriage train crossing a junction under player control end-to-end
