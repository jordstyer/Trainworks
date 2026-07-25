# Polish — Technical Design (Phase 6)

> Redstone integration, crossing gates, the network map item, and sound/particle design. Lower
> architectural risk than Phases 1–5 — everything here reads from systems that already exist
> ([track-graph.md](track-graph.md), [trains.md](trains.md), [automation.md](automation.md))
> rather than introducing new core state. The one piece worth real design care is the crossing
> gate's trigger condition (§2), since a naive version breaks under the whole-path reservation
> model from automation.md §4.2.

Companion to [../DESIGN.md](../DESIGN.md) §7.

---

## 1. Redstone Hooks

All three hooks are read-only projections of state that already exists — no new persisted data.

| Hook | Source | Behavior |
|---|---|---|
| **Signal → comparator output** | `SignalSection.owner` (automation.md §4.1) | Emits 15 while the section is occupied/reserved, 0 while free. Lets players wire lamps, gates (see §2), or logic directly off existing signal state. |
| **Station → arrival pulse** | Train-manager arrival event (a train's `velocity` hits 0 at a station's stop point) | Station block emits a short pulse (comparator or direct, 1–2 ticks) on arrival. Same event also triggers the station bell sound (§4). |
| **Redstone input → hold train** | Station block's input side | While powered, a train stopped at that station won't compute/depart its next timetable leg — same underlying mechanism as the dispatch board's per-train `held` flag (automation.md §7), just scoped to "held while at this specific station" instead of held globally. |

---

## 2. Crossing Gates

### 2.1 The naive version doesn't work
A crossing gate placed over a road, bound to the section that covers that point, closing whenever
`SignalSection.owner != null` seems obvious — but automation.md §4.2 reserves a train's **entire
route atomically at departure**. That section could be reserved for a long time before the train
is anywhere near the physical crossing, so the gate would close far too early and stay closed
needlessly.

### 2.2 Proximity-based trigger instead
A Crossing Gate binds to a specific `(edgeId, distance)` point, same as a bogie or station. Each
tick, it checks nearby trains (using the chunk→edge spatial index, track-graph.md §5, to avoid
scanning every train on the server) for any whose current lead position is:
- on an edge that's part of that train's active path, **and**
- within a configurable trigger distance of the gate's point (default a handful of blocks — tune
  by feel), approaching (not receding).

Gate closes when a train enters that radius, opens once the train's tail has fully passed the
gate's point. This is a pure read of existing position data — no new global state, just a
per-tick proximity check scoped to the (small) set of trains near that edge.

---

## 3. Network Map Item

- In-hand item opens a top-down view built by querying `TrackGraphSavedData` (nodes/edges),
  the station registry, and the train manager for live positions — all already-existing data
  (track-graph.md §5, automation.md §2.1, trains.md §2.3).
- Recommended to build a **bare-bones version early, during Phase 1**, purely as a debugging tool
  for verifying the graph looks right — then polish it here in Phase 6: pan/zoom, station labels,
  click a station to see trains currently timetabled through it, color-code sections by
  reservation state.
- No new data model — this is a renderer over existing registries.

---

## 4. Sound & Particle Design

Reuse distance/state tracking that already exists on `Train`/`Carriage` rather than adding new
timers where possible:

| Effect | Trigger | Notes |
|---|---|---|
| **Chuff (steam sound)** | Accumulate distance traveled per powered bogie; emit a chuff every `wheelCircumference` blocks (config default) | Naturally scales chuff rate with speed for free — no separate speed→rate formula needed, just distance already tracked for movement (trains.md §4.1). |
| **Flange squeal** | Yaw-delta-per-tick (or curvature from track-graph.md §6's curvature estimate) exceeds a threshold | Reuses the same curvature math already written for connect-time validation. |
| **Brake hiss** | Brake input transitions from 0 → active, or sustained high braking force | |
| **Station bell** | Same arrival event as the redstone pulse (§1) | One event, two listeners. |
| **Funnel smoke particles** | Spawn rate scaled by current throttle level | Client-side only, purely cosmetic. |

## 5. Doors

- Carriage door blocks open automatically when the train is stationary at a station's stop point
  (reusing the "train stationary at station" state already needed for cargo/wait-condition logic,
  automation.md §3/§6) and close on departure.

---

## 6. Open Sub-Decisions

- Crossing gate trigger distance default (needs in-world tuning against realistic train speeds).
- Redstone polarity convention: occupied=15/free=0 chosen above for "more power = more caution,"
  but flip if playtesting says the opposite reads better with typical lamp/gate wiring.
- Chuff wheel-circumference default and whether it should vary per bogie type later.

---

## 7. Phase 6 Milestone Checklist

- [ ] Signal comparator output wired to `SignalSection.owner`
- [ ] Station arrival pulse + bell sound (shared event)
- [ ] Redstone-hold input on station blocks
- [ ] Crossing gate: proximity-based trigger (not naive section-ownership), open/close animation
- [ ] Network map item: polished pan/zoom/labels version (bare-bones debug version should already
      exist from Phase 1)
- [ ] Chuff/squeal/hiss sounds wired to existing distance/curvature/brake state
- [ ] Funnel smoke particles scaled by throttle
- [ ] Carriage doors open/close on station stop
