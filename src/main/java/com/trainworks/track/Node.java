package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * One directional track anchor ("port"). Multiple nodes may share the same
 * {@code pos} -- that's how junctions emerge, see design/track-graph.md §2.1/§2.4.
 */
public class Node {
    private final long id;
    private final BlockPos pos;
    private float facingYaw;
    private final List<Long> edgeIds = new ArrayList<>();

    public Node(long id, BlockPos pos, float facingYaw) {
        this.id = id;
        this.pos = pos;
        this.facingYaw = facingYaw;
    }

    public long id() {
        return id;
    }

    public BlockPos pos() {
        return pos;
    }

    public float facingYaw() {
        return facingYaw;
    }

    /**
     * Re-orients an unconnected anchor. Once an edge exists its tangent is
     * locked in permanently (design/track-graph.md §2.2/§7), so this refuses
     * to touch a node that has any edges -- reconnecting would silently warp
     * an existing curve out from under whatever's already built along it.
     */
    public void setFacingYaw(float facingYaw) {
        if (!edgeIds.isEmpty()) {
            throw new IllegalStateException("Cannot re-face a node that already has edges");
        }
        this.facingYaw = facingYaw;
    }

    public List<Long> edgeIds() {
        return edgeIds;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putLong("Pos", pos.asLong());
        tag.putFloat("Facing", facingYaw);
        ListTag edgesTag = new ListTag();
        for (long edgeId : edgeIds) {
            edgesTag.add(LongTag.valueOf(edgeId));
        }
        tag.put("Edges", edgesTag);
        return tag;
    }

    public static Node load(CompoundTag tag) {
        Node node = new Node(tag.getLong("Id"), BlockPos.of(tag.getLong("Pos")), tag.getFloat("Facing"));
        ListTag edgesTag = tag.getList("Edges", Tag.TAG_LONG);
        for (int i = 0; i < edgesTag.size(); i++) {
            node.edgeIds.add(((LongTag) edgesTag.get(i)).getAsLong());
        }
        return node;
    }
}
