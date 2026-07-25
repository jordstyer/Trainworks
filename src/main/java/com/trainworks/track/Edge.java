package com.trainworks.track;

import net.minecraft.nbt.CompoundTag;

/**
 * A connection between two nodes. Tangents are locked in at creation time
 * and never re-read from the node live -- see design/track-graph.md §2.2.
 */
public class Edge {
    private final long id;
    private final long nodeA;
    private final long nodeB;
    private final float tangentAYaw;
    private final float tangentBYaw;
    private final double length;

    public Edge(long id, long nodeA, long nodeB, float tangentAYaw, float tangentBYaw, double length) {
        this.id = id;
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.tangentAYaw = tangentAYaw;
        this.tangentBYaw = tangentBYaw;
        this.length = length;
    }

    public long id() {
        return id;
    }

    public long nodeA() {
        return nodeA;
    }

    public long nodeB() {
        return nodeB;
    }

    public float tangentAYaw() {
        return tangentAYaw;
    }

    public float tangentBYaw() {
        return tangentBYaw;
    }

    /** Cached total arc length, computed once at creation from the Bezier LUT. */
    public double length() {
        return length;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putLong("NodeA", nodeA);
        tag.putLong("NodeB", nodeB);
        tag.putFloat("TangentA", tangentAYaw);
        tag.putFloat("TangentB", tangentBYaw);
        tag.putDouble("Length", length);
        return tag;
    }

    public static Edge load(CompoundTag tag) {
        return new Edge(
                tag.getLong("Id"),
                tag.getLong("NodeA"),
                tag.getLong("NodeB"),
                tag.getFloat("TangentA"),
                tag.getFloat("TangentB"),
                tag.getDouble("Length")
        );
    }
}
