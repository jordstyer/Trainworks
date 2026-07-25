package com.trainworks.track;

import com.trainworks.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds the id of the {@link Node} this anchor block created in the track
 * graph. See design/track-graph.md §7 (anchor item/block).
 */
public class TrackAnchorBlockEntity extends BlockEntity {
    private static final long UNASSIGNED = -1L;

    private long nodeId = UNASSIGNED;

    public TrackAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRACK_ANCHOR.get(), pos, state);
    }

    public long nodeId() {
        return nodeId;
    }

    public boolean hasNode() {
        return nodeId != UNASSIGNED;
    }

    public void setNodeId(long nodeId) {
        this.nodeId = nodeId;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("NodeId", nodeId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        nodeId = tag.getLong("NodeId");
    }
}
