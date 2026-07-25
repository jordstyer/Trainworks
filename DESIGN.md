# Trainworks — Design Document

> Working title. A standalone Forge 1.20.1 mod: Create-style buildable trains on custom wide-gauge
> tracks, with lightweight MTR-style automation. Survival- and vanilla-friendly; no dependency on
> Create or any other mod.

**Status:** Planning — no code yet.
**Target:** Minecraft 1.20.1, Forge (JDK 17).

---

## 1. Vision & Goals

- Trains you **build out of normal blocks** and drive yourself, Create-style.
- Trains that can also **run on their own** — following routes between stations, switching tracks
  at junctions automatically, on a schedule.
- **Custom wide-gauge tracks** (not vanilla rails), placed as checkpoints/anchors that connect
  with natural curves and slopes, like Create's track system.
- **Simpler than MTR**: no depots, no sidings-as-a-concept, no real-time-minute scheduling.
  Stations + a timetable item + automatic signaling covers 90% of what a server wants.
- Survival-friendly: reasonable recipes, fuel as a coal sink (config-toggleable), no creative-only
  requirements.

### Non-goals
- Not a Create addon and no Create-style rotational power / kinetics.
- No MTR-style real-world train models or GTFS-like scheduling.
- No boarding fees, rail company simulation, or passenger NPC systems (maybe far future).

---

## 2. Track System

> Full technical data model, curve math, and persistence layout for this section now live in
> [design/track-graph.md](design/track-graph.md) — the detail below is the summary.

### 2.1 The graph is the source of truth
The track network is a **graph of nodes and edges** stored in world `SavedData` — not derived
from scanning blocks. Trains, routing, and signals all operate on this graph. Everything logical
about a train's position is 1-dimensional: `(edge id, distance along edge)`.

- **Node** = a placed track anchor: position + facing (tangent direction).
- **Edge** = a connection between two nodes: straight line or **cubic bezier** using each node's
  facing as the curve tangent. This is what produces natural curves and elevation changes from
  just two checkpoints.

### 2.2 Placement flow
1. Place a **track anchor** block (crafted item). Its facing sets the curve tangent.
2. Use the **linking tool** (track hammer / wrench) on anchor A, then anchor B → the mod generates
   the connecting curve.
3. Validation happens at connect time — reject bad geometry early rather than handle it later:
   - Max grade (proposal: 1 block rise per 4 blocks run).
   - Minimum curve radius.
   - Obstruction check along the curve (auto-clear replaceables like grass/snow).

### 2.3 Ghost / occupancy blocks
The generated curve passes through block space, so the mod places lightweight **track segment
blocks** along the path. They provide:
- Collision and a visual for the rail bed.
- Right-click interaction (mapped back to nearest point on the edge).
- A world-presence for the graph: if a segment is destroyed (explosion etc.), the edge breaks
  cleanly and drops materials.
- Chunk→edge lookup for signals, stations, and train materialization.

### 2.4 Wide gauge
- Tracks render as a **3-block-wide** double-rail model — odd width centers better under builds
  (a single center block + one rail on each side), which makes carriage construction cleaner.
- Curve/grade limits (§2.2) should be tuned around this width so curves don't demand unreasonably
  large radii.
- Gauge is visual/collision only — train logic stays 1D along the edge spline.

### 2.5 Switches (turnouts) & crossings
- A junction is just a **node with 3+ edges**. Each junction stores its current direction.
- Manual control: right-click or an attached lever.
- Automatic control: the router sets junctions ahead of an automated train (see §5.3).
- Flat crossings (two tracks crossing without connecting) are two edges that intersect; they
  share an implicit signal boundary so two trains can't occupy the crossing at once (§5.4).

---

## 3. Trains Built From Blocks

> Full technical data model (bogie/carriage/train structures, position math, assembly algorithm,
> coupling) lives in [design/trains.md](design/trains.md) — the detail below is the summary.

### 3.1 Assembly (contraption-style)
1. Designate a straight track section as an **assembly zone**: right-click a **station block**
   to toggle assembly mode for the straight section beside it. No separate workbench block —
   assembly is tied to a place you'll build near anyway, and it's one less block/recipe to learn.
2. Build carriages out of any blocks on/over the track. Place **bogie blocks** on the track to
   define wheel positions. A carriage = the connected blocks above 1–2 bogies.
3. Assemble → blocks are scanned into a stored structure; a train entity spawns.
4. Disassemble (at any station in assembly mode) → blocks return to the world exactly as built.

### 3.2 Entity & data model
- **One entity per carriage**, linked into a logical **Train** object that lives in a server-side
  train manager (the Train itself is not an entity).
- Carriage position/orientation is **derived from its bogies' positions on the track spline** —
  carriages lean into curves and pitch on slopes for free.
- Carriage block data stored like a structure template; rendered as a moving block collection.

### 3.3 Functional blocks
| Block | Role |
|---|---|
| Control stand / driver's seat | Required to drive; holds the timetable for automation |
| Bogie | Wheels; defines carriage ends; powered vs unpowered variants |
| Firebox / boiler | Fuel consumption (if fuel enabled); defines a "locomotive" |
| Storage blocks (chests, barrels) | Keep working as inventories; used by cargo loading |
| Whistle / horn | Flavor + crossing warning |
| Doors | Open at stations (polish phase) |

Everything else is cosmetic — players build whatever shell they want.

### 3.4 Coupling
- Carriages assembled together are coupled into one train.
- A **coupler tool** splits/joins trains at stations (phase 3+). Enables locomotive swaps and
  wagon drop-off gameplay.

---

## 4. Movement & Driving

> See [design/trains.md](design/trains.md) §3–4 for the per-tick integration math, junction
> resolution, and mass/tractive-force formulas.

### 4.1 Physics (kept deliberately simple)
- Train state: speed, acceleration, brake force. Position advances along the graph each tick.
- **Top speed & acceleration scale with weight (block count) vs. powered bogies** — rewards
  building proper locomotives without simulating real physics.
- Optional speed limits per edge (tight curves slow trains — nice for realism, evaluate later).

### 4.2 Manual driving
- Sit in the control stand. **W/S = notched throttle up/down** (not hold-to-move),
  **Space = brake**, horn key.
- **A/D steers junction choice** when approaching a switch — a QoL win over Create's
  manual mode.
- Manual trains **always obey signals** (red forces brake) so player-driven and automated
  trains can safely share one network — a manual train can never plow into an automated one
  at a junction or shared section. No config override; this is a safety invariant, not a
  style choice.

### 4.3 Fuel
- Locomotive firebox consumes furnace fuel (coal sink, vanilla-flavored progression).
- **Config toggle** lets server owners disable fuel consumption for free locomotion if desired.

---

## 5. Automation (MTR-lite)

> Full technical design (station/registry model, Cargo Loader mechanism, reservation system,
> router, timetable data model, dispatch board) lives in
> [design/automation.md](design/automation.md) — the detail below is the summary.

### 5.1 Station block
- Placed beside track, binds to the nearest edge, given a name. That's it — no platform zones,
  no MTR dimensions.
- Doubles as the assembly/disassembly point (see Open Decisions).

### 5.2 Timetable item (routes live in an item, not a global registry)
- A written-book-style **Timetable** item with a UI: an ordered, looping list of entries:
  - `Go to <Station>` → then a wait condition: fixed time / cargo full / cargo empty /
    redstone signal / player interaction.
- Put the timetable in the control stand → train runs it autonomously.
- Copyable, so many trains can run one route. No central route registry to administrate.
- Flavor option: a hired **conductor villager** (with a hat) sits in the seat instead —
  very vanilla-friendly. (Polish phase; the slot-in-control-stand version ships first.)

### 5.3 Routing & automatic switching
- On departure toward station X, run **Dijkstra/A\*** over the track graph to find the path.
- The train sets each junction as it approaches. Players never hand-configure switches for
  automated routes — this is the main complexity-killer vs. MTR.
- No path found → train waits at a red "no route" state and the control stand shows why.

### 5.4 Signaling — reservation based
- **Signal blocks** placed beside track divide it into sections; a train **reserves** the
  section(s) ahead of it as it moves.
- Red = section held by another train → approaching train brakes and queues.
- **Junctions are always implicit signal boundaries** — two trains can never enter a crossing
  simultaneously even with zero player-placed signals.
- Deliberately minimal: no ATC, no dwell optimization, no priority tiers (at least in v1).

### 5.6 Cargo at unloaded stations
- If an automated train reaches a station whose chunk isn't loaded, cargo transfer is **queued**
  rather than simulated or skipped: the pending load/unload is recorded and applied the next time
  the station's chunk loads (player nearby, chunk loader, etc.). Simple, no desync risk — the
  cost is that the train appears to "wait" at the station with no visible activity until the
  chunk loads. Acceptable tradeoff for v1; revisit only if it proves annoying in practice.

### 5.5 Dispatch board (optional master controller)
- A block showing all stations, trains, and live positions; can hold/release trains.
- Purely additive — networks fully function without it. Late phase.

---

## 6. Server & Performance Decisions

### 6.1 Unloaded chunks — simulate, don't chunkload
- Trains tick **abstractly through unloaded chunks**: position advances on the graph (pure 1D
  math), no chunks loaded, entities despawned. The train **materializes** when a player is near.
- This is only possible because position is 1D graph math — and it's the single biggest
  server-friendliness decision in the mod. No per-train chunkloaders.
- Consequences to design around: fuel still consumed abstractly; cargo loading at unloaded
  stations queues until the chunk loads (§5.6).

### 6.2 Multiplayer sync
- Client-side interpolation of carriage positions along the spline (send graph position +
  speed, let clients extrapolate; correct on desync).
- Seat/passenger sync, junction state sync, signal state sync.

---

## 7. Extras (polish phase)

> Full technical design lives in [design/polish.md](design/polish.md) — notably the crossing
> gate's proximity-based trigger, which a naive version gets wrong under the whole-path
> reservation model from §5.4/automation.md §4.2.

- **Cargo loading block**: at a station, pushes/pulls items between adjacent chests and train
  inventories while the train waits. With "wait until full" → full item logistics, no new systems.
- **Redstone hooks**: signal → comparator level; station → pulse on arrival; redstone input can
  hold a train. Enables crossing gates, bells, player-built logic for free.
- **Crossing gate block**: closes automatically when a train reserves the section.
- **Network map item**: in-hand map of the track graph + live train positions. Build it early
  as a dev/debug tool, polish it later as a player item.
- **Sound design**: chuff rate tied to speed, flange squeal on tight curves, brake hiss,
  station bell.
- **Particles**: funnel smoke scaled with throttle.

---

## 8. Build Order / Milestones

| Phase | Deliverable | Notes |
|---|---|---|
| 1 | **Track graph prototype** — anchors, linking tool, bezier generation, curve rendering, SavedData persistence | Hardest rendering work; prove it first. See [design/track-graph.md](design/track-graph.md) for the full spec and milestone checklist. |
| 2 | **Single carriage** — assembly/disassembly, bogie-on-spline math, ride + drive manually | *The mod is already fun here*. See [design/trains.md](design/trains.md) |
| 3 | **Junctions** + manual switching + multi-carriage trains + coupling | See [design/trains.md](design/trains.md) §3.4, §7 |
| 4 | **Stations + timetable + autopilot** — pathfinding, auto-switching | First automation payoff. See [design/automation.md](design/automation.md) |
| 5 | **Signals/reservations** + unloaded-chunk simulation | Server-readiness phase. See [design/automation.md](design/automation.md) §4, §8 |
| 6 | **Polish** — cargo loading, redstone, crossings, dispatch board, sounds, map item | See [design/polish.md](design/polish.md) |

---

## 9. Decisions (locked in)

| # | Decision | Resolution |
|---|---|---|
| a | Fuel-based or free locomotion | **Fuel-based**, config toggle to disable |
| b | Track gauge: 2 or 3 blocks wide | **3 blocks** — odd width centers builds better under a train |
| c | Do manual drivers respect signals? | **Yes, always** — safety invariant, no config override |
| d | One mod vs. core + automation addon | **One mod** |
| e | Assembly zone mechanism | **Station block assembly mode** (right-click to toggle) |
| f | Mod name | **Trainworks** (placeholder, revisit later if desired) |
| g | Cargo behavior at unloaded stations | **Queue until chunk loads** |

All open questions from the initial draft are now resolved. Revisit any of these only if
prototyping in Phase 1–2 surfaces a concrete problem with the choice.

---

## 10. Reference Points

- **Create** — track anchors/beziers, contraption assembly, schedule item, signal sections.
  We are reimplementing these *ideas* independently, not depending on or copying Create's code.
- **MTR** — station/route automation inspiration; explicitly avoiding its complexity.
- **Immersive Railroading** — what to avoid for this project: fixed rolling stock models,
  heavy realism.
