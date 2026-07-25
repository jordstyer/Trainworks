package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory node/edge graph -- the source of truth described in
 * design/track-graph.md §2.1. Wrapped for persistence by {@link TrackGraphSavedData}.
 */
public class TrackGraph {
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, Edge> edges = new HashMap<>();
    private long nextNodeId;
    private long nextEdgeId;
    private final Runnable markDirty;

    public TrackGraph(Runnable markDirty) {
        this.markDirty = markDirty;
    }

    public Node addNode(BlockPos pos, float facingYaw) {
        Node node = new Node(nextNodeId++, pos, facingYaw);
        nodes.put(node.id(), node);
        markDirty.run();
        return node;
    }

    public Optional<Node> getNode(long id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Optional<Edge> getEdge(long id) {
        return Optional.ofNullable(edges.get(id));
    }

    /**
     * Connects two existing, currently-unconnected-to-each-other nodes with a
     * new edge. Tangents are derived from each node's facing, oriented toward
     * the other node, and locked into the edge permanently (design/track-graph.md §2.2).
     *
     * <p>Both anchors' facing axes are resolved against the <em>same</em>
     * A→B direction -- i.e. both tangents get chosen so they "point
     * downstream," continuing the overall direction of travel, not each
     * anchor's own perspective toward the other. This matters: resolving B's
     * axis against the reverse (B→A) direction would pick whichever half of
     * B's axis points <em>back</em> toward A, which fights the direction of
     * travel instead of continuing it -- producing a curve that ignores B's
     * actual facing and always bows toward whatever the fallback happens to
     * be. It coincidentally still looks right when both anchors are aimed
     * directly at each other (the two half-circle choices collapse to the
     * same direction either way), which is why this stayed hidden until
     * tested with genuinely different facings.</p>
     *
     * <p>No validation (grade/radius/obstruction, design/track-graph.md §6) is
     * performed here -- that belongs to the linking tool, which should run
     * those checks before calling this.</p>
     */
    public Edge connect(long nodeAId, long nodeBId) {
        Node nodeA = nodes.get(nodeAId);
        Node nodeB = nodes.get(nodeBId);
        if (nodeA == null || nodeB == null) {
            throw new IllegalArgumentException("Cannot connect unknown node id");
        }

        CurveResult result = computeCurve(nodeA, nodeB);

        Edge edge = new Edge(nextEdgeId++, nodeAId, nodeBId, result.tangentAYaw(), result.tangentBYaw(), result.curve().length());
        edges.put(edge.id(), edge);
        nodeA.edgeIds().add(edge.id());
        nodeB.edgeIds().add(edge.id());
        markDirty.run();
        return edge;
    }

    /**
     * Computes what the curve between two nodes *would* look like without
     * creating anything -- used by the linking tool to validate a connection
     * (design/track-graph.md §6) before committing to it.
     */
    public Optional<BezierCurve> previewCurve(long nodeAId, long nodeBId) {
        Node nodeA = nodes.get(nodeAId);
        Node nodeB = nodes.get(nodeBId);
        if (nodeA == null || nodeB == null) {
            return Optional.empty();
        }
        return Optional.of(computeCurve(nodeA, nodeB).curve());
    }

    private record CurveResult(float tangentAYaw, float tangentBYaw, BezierCurve curve) {
    }

    private static CurveResult computeCurve(Node nodeA, Node nodeB) {
        Vec3 posA = Vec3.atCenterOf(nodeA.pos());
        Vec3 posB = Vec3.atCenterOf(nodeB.pos());

        float tangentAYaw = orientToward(nodeA.facingYaw(), posA, posB);
        float tangentBYaw = orientToward(nodeB.facingYaw(), posA, posB);

        BezierCurve curve = BezierCurve.create(posA, tangentAYaw, posB, tangentBYaw);
        return new CurveResult(tangentAYaw, tangentBYaw, curve);
    }

    /**
     * Re-orients an anchor before it's been connected to anything. Returns
     * false (no-op) if the node doesn't exist or already has an edge --
     * see {@link Node#setFacingYaw}.
     */
    public boolean refaceNode(long nodeId, float facingYaw) {
        Node node = nodes.get(nodeId);
        if (node == null || !node.edgeIds().isEmpty()) {
            return false;
        }
        node.setFacingYaw(facingYaw);
        markDirty.run();
        return true;
    }

    /** Picks {@code facingYaw} or {@code facingYaw + 180} -- whichever points toward {@code to}. */
    private static float orientToward(float facingYaw, Vec3 from, Vec3 to) {
        double rad = Math.toRadians(facingYaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double tx = to.x - from.x;
        double tz = to.z - from.z;
        double dot = fx * tx + fz * tz;
        return dot >= 0 ? facingYaw : facingYaw + 180f;
    }

    /**
     * Removes a node and cascades: every edge touching it is also removed, and
     * the edge id is cleaned out of whichever other node it was attached to.
     * Track segment blocks along those edges aren't handled here yet (no such
     * blocks exist in-world as of this pass) -- see design/track-graph.md §4.
     */
    public void removeNode(long nodeId) {
        Node node = nodes.remove(nodeId);
        if (node == null) {
            return;
        }
        for (long edgeId : new java.util.ArrayList<>(node.edgeIds())) {
            Edge edge = edges.remove(edgeId);
            if (edge == null) {
                continue;
            }
            long otherNodeId = edge.nodeA() == nodeId ? edge.nodeB() : edge.nodeA();
            Node otherNode = nodes.get(otherNodeId);
            if (otherNode != null) {
                otherNode.edgeIds().remove(edgeId);
            }
        }
        markDirty.run();
    }

    /** Rebuilds the Bezier curve for a live edge -- the curve itself is never persisted (design/track-graph.md §5). */
    public BezierCurve curveOf(Edge edge) {
        Node nodeA = nodes.get(edge.nodeA());
        Node nodeB = nodes.get(edge.nodeB());
        return BezierCurve.create(
                Vec3.atCenterOf(nodeA.pos()), edge.tangentAYaw(),
                Vec3.atCenterOf(nodeB.pos()), edge.tangentBYaw()
        );
    }

    public Map<Long, Node> nodes() {
        return nodes;
    }

    public Map<Long, Edge> edges() {
        return edges;
    }

    long nextNodeId() {
        return nextNodeId;
    }

    long nextEdgeId() {
        return nextEdgeId;
    }

    void setNextNodeId(long value) {
        this.nextNodeId = value;
    }

    void setNextEdgeId(long value) {
        this.nextEdgeId = value;
    }
}
