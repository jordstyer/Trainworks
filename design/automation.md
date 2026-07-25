# Automation — Technical Design (Phase 4/5)

> Stations, the timetable item, pathfinding, and the reservation/signal system that lets trains
> run themselves safely. Builds on [track-graph.md](track-graph.md) (the graph trains path over)
> and [trains.md](trains.md) (the `Train`/`occupiedPath` model that consumes a computed route).

Companion to [../DESIGN.md](../DESIGN.md) §5.

---

## 1. Scope

Phase 4: a train can be given a timetable, computes a route between named stations, and drives
itself there, switching junctions correctly. Phase 5: trains sharing a network can't collide —
reservation-based signaling — plus the unloaded-chunk simulation model. This doc covers both,
since the routing design in Phase 4 needs the reservation system's shape decided up front (they
interact tightly, per §4 below).

---

## 2. Station

| Field | Type | Notes |
|---|---|---|
| `id` | long | |
| `name` | string | Display label. Not required to be unique (see §9), but the timetable UI shows coordinates alongside the name to disambiguate duplicates. |
| `edgeId`, `distance` | long, double | Bound to the nearest track edge at placement time, exactly like a bogie (track-graph.md §2.1). This is the **stop point**: an arriving automated train aligns its lead bogie to this distance. |
| `assemblyModeEnabled` | bool | Toggles the assembly zone behavior on the adjoining straight section (DESIGN.md §3.1). |

Stations deliberately do **not** know anything about platform length, train length, or which
carriage ends up where — that's what Cargo Loader blocks are for (§3). A station is just a name
and a stop point.

### 2.1 Station registry
A `StationSavedData` (one per dimension, alongside `TrackGraphSavedData`) holding
`Map<Long, Station>` plus a monotonic id counter — the timetable references stations by `id`,
resolved to a live `Station` at route-compute time (so renaming or slightly repositioning a
station never invalidates existing timetables).

---

## 3. Cargo Loader (separate from the station block)

Trains are arbitrary length, so a single station block can't know where a specific train's
storage carriage will physically end up when stopped. Instead:

- A **Cargo Loader** block is placed anywhere along the platform, adjacent to a chest/barrel/
  hopper, and binds to its own `(edgeId, distance)` at placement — same mechanism as a station or
  bogie.
- While a train is stationary and *some carriage's bogie-derived footprint* (trains.md §3.2/§3.3)
  contains the loader's distance, it transfers items between the adjacent inventory and that
  carriage's exposed storage blocks.
- This requires **no train-length metadata anywhere** — whether a loader lines up with a storage
  carriage is a pure consequence of where the player physically built things, exactly like real
  platform loading points.
- Transfer rate is a config value (instant vs. N items/tick) — see §9.

---

## 4. Reservation Model (Signals) — decided before routing, because routing depends on it

### 4.1 Sections
The graph is divided into **signal sections**: a run of edges between two signal blocks, with
every **junction node also acting as an implicit section boundary** (DESIGN.md §2.5/§5.4) — so a
network with zero player-placed signals still can't let two trains collide at a switch.

| Field | Type | Notes |
|---|---|---|
| `id` | long | |
| `edgeIds` | ordered list | Edges making up this section. |
| `owner` | trainId \| null | Which train currently holds this section, if any. |

### 4.2 v1 approach: reserve the whole path at once
Rather than incremental lookahead reservation (claim the next section only as you approach it),
**v1 reserves every section along a train's computed route atomically at departure time**:

1. Router computes the edge path (§5).
2. Path is mapped to the ordered list of sections it crosses.
3. Attempt to reserve all of them: if every section is free, claim them all and depart; if **any**
   is already owned by another train, reservation fails — the train stays at its station and
   retries (either on a timer, or immediately when notified that a blocking section was released).
4. As the train's tail physically clears a section (all carriages past it), that section is
   released — which can wake up another train that was waiting on it.

This trades some throughput (a train reserves sections it won't reach for a while) for a much
simpler mental model and implementation — consistent with the "no ATC, no dwell optimization"
philosophy in DESIGN.md §5.4. Incremental/lookahead reservation is a plausible future optimization
if a server's network gets busy enough to need it, not a v1 requirement.

### 4.3 Manual trains use the same registry
A manually-driven train doesn't have a precomputed path, so it reserves just its current section
and looks one section ahead as it approaches a signal:
- Next section free → signal is green, no action needed.
- Next section owned by someone else → forced brake (DESIGN.md §4.2/§4.3), same mechanism an
  automated train's brake input uses.

Manual and automated trains share **one** `SectionOwnership` registry, so a manual train can never
conflict with an automated one or vice versa — there's only one source of truth for who owns what.

### 4.4 Junction switching is a side effect of reservation
Because a departing automated train reserves its *entire* path before moving, every junction along
that path can have its `JunctionState` (track-graph.md §2.4) set to the correct direction
**immediately at departure**, with no risk of another train needing that same junction mid-transit
— the section covering the junction is already exclusively owned. This is what makes "the router
sets switches automatically" (DESIGN.md §5.3) safe rather than racy.

---

## 5. Router

1. Timetable's current entry names a target station → resolve to `(edgeId, distance)` (§2.1).
2. Run **Dijkstra/A\*** over the track graph from the train's current position to the target,
   weighted by edge length. Standard graph search — no train-specific logic here.
3. Result is an ordered edge list, stored as the train's `committedPath` (trains.md §3 — distinct
   from `occupiedPath`, which is only the subset of edges *currently physically spanned*; edges
   move from `committedPath` into `occupiedPath` as the train advances, matching trains.md's
   junction-resolution hook in §3.4).
4. Attempt reservation (§4.2). On success, depart. On failure, wait.
5. **No path exists** (disconnected track, station removed, etc.): train enters a `NO_ROUTE` state,
   surfaced at its control stand and on the dispatch board (§7), and retries periodically rather
   than silently idling forever.
6. **Waits too long** (path exists but stays reserved by others past a configurable threshold):
   log a server-side warning so an admin notices a developing gridlock — v1 does not attempt
   deadlock detection/resolution beyond this visibility.

---

## 6. Timetable Item

- A written-book-style item holding an **ordered, looping list of entries**:
  `{ targetStationId, waitCondition }`.
- `waitCondition` (single condition per entry in v1 — no AND/OR combinations, keep it simple):
  `FIXED_TIME(seconds)` / `CARGO_FULL` / `CARGO_EMPTY` / `REDSTONE_SIGNAL` /
  `PLAYER_INTERACT` (manual dispatch button) / `NONE` (pass-through waypoint, depart immediately).
- Edited via a custom item screen. QoL addition: **shift-clicking a station block with a
  timetable in hand appends that station** as the next entry — avoids typing/selecting names from
  a list for the common case.
- Placed in a train's control stand to switch that train into `AUTOMATED` mode; removing it (or a
  redstone "manual override" signal at the stand) returns the train to `MANUAL`.
- References stations by `id` (§2.1), so renaming/moving a station doesn't invalidate saved
  timetables; deleting a referenced station does — surfaced the same way as `NO_ROUTE` (§5.5).

---

## 7. Dispatch Board (optional, purely additive)

- A block UI that reads the existing train manager (trains.md §2.3) and station registry (§2.1) —
  **no new persisted state beyond a per-train `held: bool` flag**, checked before a train is
  allowed to compute/depart a route.
- Shows: every train, its current mode/status (`MANUAL` / en route to X / `NO_ROUTE` / held), and
  every station. Lets an operator hold or release any train.
- Entirely optional — every automation feature above works without one ever being placed.

---

## 8. Unloaded-Chunk Simulation (Phase 5, ties to trains.md)

- A train whose chunks are all unloaded still advances `leadDistance`/`occupiedPath` by pure math
  each tick (no entities ticking, no chunks force-loaded) — see DESIGN.md §6.1.
- Section reservation/release (§4) and junction switching (§4.4) still function identically while
  unloaded, since they're pure data operations on the graph, not renderer/entity-dependent.
- Cargo transfer at an unloaded station's Cargo Loader **queues** rather than executing (DESIGN.md
  §5.6) — applied once that chunk loads.
- The train "materializes" (spawns real carriage entities) once a player comes near enough for
  normal chunk loading to kick in; despawns back to pure math when everyone leaves.

---

## 9. Open Sub-Decisions (empirical/config — settle during Phase 4/5 prototyping)

- Retry cadence for a train blocked on reservation, and the "warn about possible gridlock" time
  threshold.
- Cargo Loader transfer rate: instant vs. rate-limited items/tick (rate-limited probably reads
  better but check in practice).
- Whether station names must be unique (leaning **no** — coordinates in the UI disambiguate, and
  forcing uniqueness adds friction for large servers with many builders).
- Default retry behavior on `NO_ROUTE`: fixed interval vs. exponential backoff.

---

## 10. Phase 4/5 Milestone Checklist

**Phase 4 (stations + timetable + autopilot):**
- [ ] Station block + registry (bind to nearest edge, name, assembly-mode toggle)
- [ ] Cargo Loader block bound to its own point, transfers items while a carriage overlaps it
- [ ] Timetable item + edit screen + shift-click-to-append QoL
- [ ] Router: Dijkstra/A* over the graph, produces `committedPath`
- [ ] Automated train departs, follows `committedPath`, switches junctions correctly, arrives and
      aligns to the next station's stop point
- [ ] `NO_ROUTE` state surfaced at the control stand

**Phase 5 (signals + unloaded simulation):**
- [ ] `SignalSection` model + implicit junction boundaries
- [ ] Whole-path reservation at departure; release on tail-clear; retry-on-release wake-up
- [ ] Manual trains obey one-section-ahead reservation checks (shared registry with automated)
- [ ] Gridlock-wait warning logging
- [ ] Unloaded-chunk pure-math simulation; materialize/despawn on player proximity
- [ ] Cargo queueing at unloaded stations, applied on chunk load
- [ ] Dispatch board: read-only view + per-train hold/release
- [ ] Manual test: two automated trains sharing one junction on different timetables, verify no
      collision and correct queuing when one blocks the other
