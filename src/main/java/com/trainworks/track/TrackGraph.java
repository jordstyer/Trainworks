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

        Vec3 posA = Vec3.atCenterOf(nodeA.pos());
        Vec3 posB = Vec3.atCenterOf(nodeB.pos());

        float tangentAYaw = orientToward(nodeA.facingYaw(), posA, posB);
        float tangentBYaw = orientToward(nodeB.facingYaw(), posB, posA);

        BezierCurve curve = BezierCurve.create(posA, tangentAYaw, posB, tangentBYaw);

        Edge edge = new Edge(nextEdgeId++, nodeAId, nodeBId, tangentAYaw, tangentBYaw, curve.length());
        edges.put(edge.id(), edge);
        nodeA.edgeIds().add(edge.id());
        nodeB.edgeIds().add(edge.id());
        markDirty.run();
        return edge;
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
