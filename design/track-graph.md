# Track Graph — Technical Design (Phase 1)

> Detailed data model for the track network: the foundation everything else (trains, signals,
> routing) is built on. No code yet — this defines structures, math, and persistence so Phase 1
> implementation has a clear target.

Companion to [../DESIGN.md](../DESIGN.md) §2. Read that first for the overall vision.

---

## 1. Scope for Phase 1

Deliver: place anchors → link them into curved/sloped track → see it rendered and collidable in
world → persists through a save/reload. No trains, no switching logic yet (junctions are
representable in the data model now, but switch *behavior* is Phase 3).

---

## 2. Core Data Model

### 2.1 Node — one "port" of the network
A Node is a single directional anchor point, not necessarily a whole junction.

| Field | Type | Notes |
|---|---|---|
| `id` | long | Assigned from a saved monotonic counter, not UUID — this is world-local data, no need for global uniqueness. |
| `pos` | BlockPos | Integer block position. Anchors are block-aligned. |
| `facing` | float (yaw, degrees) | Precise horizontal direction, sampled from the player's look angle at placement — **not** snapped to 45°/90°. Decoupled from the block's rendered orientation (see §7). |
| `edgeIds` | list\<long\> | Edges currently using this node as an endpoint. |

**Key design choice: multiple Nodes may share the same `pos`.** A junction/switch isn't a special
node type — it's a block position where 2+ independently-facing Nodes happen to coincide, each
contributing one "port" direction. This avoids inventing separate switch geometry: a 3-way switch
is just 3 co-located Nodes, each with its own facing, each the endpoint of one Edge leaving in a
different direction. A **Junction** (§2.4) is a thin lookup grouping co-located nodes together.

### 2.2 Edge — a connection between two nodes
| Field | Type | Notes |
|---|---|---|
| `id` | long | |
| `nodeA`, `nodeB` | long | Node ids at each end. |
| `tangentA`, `tangentB` | Vec2 (horizontal unit vector) | Locked in at creation time from each node's `facing`, oriented toward the other node. Stored on the edge (not re-read from the node live) so a node's facing can't silently warp existing curves. |
| `length` | double | Cached total arc length (see §3.2), recomputed only if the edge is ever rebuilt. |
| `lutHash` / sample count | — | The LUT itself (§3.2) is **not persisted** — cheap to regenerate on load from the fields above; avoids bloating save data. |

### 2.3 Curve construction (cubic Bézier)
Given `nodeA` (`posA`, `tangentA`) and `nodeB` (`posB`, `tangentB`):

```
d              = horizontal distance between posA and posB
controlLength  = d * k                      // k ≈ 1/3 default, tunable — see §9
P0             = posA                        (full 3D, includes y)
P1             = posA + tangentA * controlLength     // P1.y = P0.y (flat)
P2             = posB - tangentB * controlLength     // P2.y = P3.y (flat)
P3             = posB
Bezier(t) = (1-t)³P0 + 3(1-t)²t·P1 + 3(1-t)t²·P2 + t³P3,   t ∈ [0,1]
```

Keeping `P1.y = P0.y` and `P2.y = P3.y` (rather than interpolating height into the control points)
is what produces an **eased slope** — the grade ramps in and out smoothly near each node instead
of kinking straight to a fixed angle. This is the same visual effect Create's sloped track has,
and it falls out of the math for free once elevation differs between the two nodes.

### 2.4 Junction (derived, not stored)
A `Junction` is computed on demand (and cached) as: all Nodes sharing the same `pos`, plus the
edges attached to each. If the total edge count at a position is ≥ 3, it's a switch point.
Junction state (which outgoing edge is currently "active" for a given approach direction) is
tracked separately in a small `JunctionState` map — this is what Phase 3 switching logic reads
and writes. Keeping it separate from the graph means junction *state* can change constantly
(trains switching it) without touching the graph structure itself.

---

## 3. Curve Sampling & Movement Math

### 3.1 Why sample at all
Trains need "distance along edge → world position + rotation" and interactions need
"world position → nearest (edge, distance)". Both are painful to solve analytically for a cubic
Bézier, so we precompute a **lookup table (LUT)** per edge once, at creation/load time.

### 3.2 Building the LUT
- Sample the Bézier at a fixed resolution (e.g. every ~0.25 blocks of estimated arc length, or a
  fixed subdivision count like 32–64 depending on edge length — tune in prototyping).
- Walk the samples, accumulate straight-line distance between consecutive samples → cumulative
  arc length at each sample index.
- Store as a small array of `(t, cumulativeLength)`. Total length = last entry.

**Distance → position:** binary-search the cumulative-length array for the bracketing samples,
linearly interpolate `t` between them, evaluate the Bézier (and its derivative, for facing/pitch
rotation) at that `t`.

**Position → nearest edge/distance:** for right-click interaction, check the track segment block
entity under the cursor first (§4 — O(1), no search needed). A general nearby-point search over
the chunk index (§5) is the fallback only if needed (e.g. tooling/debug).

---

## 4. World Representation

- Generating an edge places lightweight **track segment blocks** along the LUT sample points
  (deduplicated to one block per integer position), matching §2.3 of the main design doc.
- Each track segment's **block entity stores `edgeId` + approximate `distance`** along that edge.
  This makes right-click lookups and "which edge is under this block" queries O(1) instead of a
  spatial search.
- Removing/exploding a segment block invalidates the edge: split it, drop the corresponding
  materials, and update both nodes' `edgeIds`. (Exact split-vs-delete behavior is an implementation
  detail to settle during Phase 1 build, not a blocking design question.)

---

## 5. Persistence (SavedData)

One `TrackGraphSavedData` per dimension (trains don't cross dimensions, so no need for a global
store):

```
TrackGraphSavedData
├── nextNodeId: long          (monotonic counter)
├── nextEdgeId: long
├── nodes: Map<Long, Node>
└── edges: Map<Long, Edge>
```

- **Chunk → edge-id spatial index is NOT persisted.** It's rebuilt in memory at load time by
  walking every edge's regenerated LUT and bucketing sample points by chunk. This is a one-time
  cost per world load and keeps the saved data simple and desync-proof (no risk of the index
  drifting from the source-of-truth graph).
- LUTs themselves are also not persisted, for the same reason — regenerated from `tangentA`/
  `tangentB`/positions, which are the only real source of truth.

---

## 6. Validation at Connect Time

Run when the linking tool attempts to join two nodes, **before** any Edge/blocks are created:

| Check | Method | Failure behavior |
|---|---|---|
| Max grade | Walk LUT samples, check `dy / horizontal-dxz` between consecutive samples against a configured max (default ≈ 1:4, tune in playtesting) | Reject, show reason in action bar |
| Min curve radius | Estimate curvature per sample via `\|r' × r''\| / \|r'\|³` (or a simpler max-turn-angle-per-sample proxy) and compare to a configured minimum | Reject, show reason |
| Obstruction | For each sample, check the swept 3-wide gauge footprint for solid non-replaceable blocks | Auto-clear replaceable blocks (grass/snow/etc.); reject only on solid obstructions |
| Duplicate/degenerate edge | Check nodeA/nodeB aren't already directly connected | Reject silently or with a clear message |

Flat crossings (two edges crossing in space without joining) are explicitly **allowed** — they're
just two independent edges that happen to intersect; §2.4/Junction logic and future signal logic
handle safety there, not the connect-time validator.

---

## 7. Player-Facing Tools & UX Flow

1. **Anchor item**: placed like a block. `Node.facing` is set from the player's precise look yaw
   at placement time. The **rendered block model snaps to the nearest 45°** for a sane visual,
   but the underlying stored `facing` stays precise — this is what lets curves read as smooth
   rather than snapping to fixed angles, same trick Create uses.
2. **Linking tool** (wrench/hammer): right-click anchor A to select it (client feedback: outline/
   particle), right-click anchor B to attempt the connection. Runs §6 validation; on success,
   generates the Edge + LUT + track segment blocks; on failure, reports why via action bar.
3. **Re-facing an unconnected anchor**: sneak-right-click with the linking tool rotates a node's
   `facing` before it has any edges. Once an edge exists, `tangentA/B` are locked (§2.2) so
   existing curves can't be silently altered.

---

## 8. Open Sub-Decisions (empirical, not architectural — settle during Phase 1 prototyping)

These don't need to be answered before starting; they're tuning constants best chosen by building
a test track in-world and eyeballing/feeling it:

- Bézier control-length factor `k` (starting guess 1/3).
- LUT sampling resolution (fixed subdivision count vs. arc-length-target).
- Default max grade and minimum curve radius numbers (starting guesses: 1:4 grade, and a radius
  that reads as "wide-gauge realistic" without being annoying to build — try a few sizes).
- Exact edge-split behavior when a track segment block is destroyed mid-edge.

---

## 9. Phase 1 Milestone Checklist

- [x] Node/Edge data structures + `TrackGraphSavedData` (load/save round-trip test)
- [x] Bézier construction + LUT generation from two nodes
- [x] Anchor item + block (placement sets precise facing from look angle; model snaps to the
      nearest 90° -- 45° isn't possible through vanilla blockstate rotation, see
      `TrackAnchorBlock`'s class doc -- with a rotated indicator stripe and placeholder art)
- [x] Linking tool: select → connect → generate edge (validation from §6 not yet implemented --
      only a duplicate-connection check exists so far); sneak-right-click an unconnected anchor
      to re-face it toward your current look direction
- [x] Track segment block + block entity (`edgeId`, `distance`, local polyline) placed along the
      LUT, with orphaned-block cleanup when an anchor is broken (placeholder art; single-block-wide,
      not the 3-wide gauge model yet)
- [x] Continuous curved rendering: `TrackSegmentRenderer` (com.trainworks.client) draws each
      segment's stored local polyline as a flat textured ribbon via a custom
      `BlockEntityRenderer`, instead of a static per-block cube model -- this is what makes a whole
      edge read as one smooth curve (Create-style) rather than a staircase of blocks. First-draft,
      unverified in a running client -- see the risk note in that class's javadoc regarding quad
      winding (geometry is deliberately double-emitted to guarantee visibility either way)
- [ ] Chunk → edge-id index built at load, used for basic render culling
- [ ] Validation rules (§6: grade, curve radius, obstruction) implemented and tuned against a
      test track
- [ ] Manual test: build a curved + sloped track section, save, reload world, confirm it persists
      and renders identically
