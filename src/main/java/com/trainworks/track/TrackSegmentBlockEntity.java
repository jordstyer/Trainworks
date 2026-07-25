package com.trainworks.track;

import com.trainworks.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Carries {@code edgeId} + an approximate {@code distance} along that edge, so
 * interacting with a piece of visible track is an O(1) lookup instead of a
 * spatial search. See design/track-graph.md §4.
 */
public class TrackSegmentBlockEntity extends BlockEntity {
    private long edgeId = -1L;
    private double distance;

    public TrackSegmentBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRACK_SEGMENT.get(), pos, state);
    }

    public long edgeId() {
        return edgeId;
    }

    public double distance() {
        return distance;
    }

    public void set(long edgeId, double distance) {
        this.edgeId = edgeId;
        this.distance = distance;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("EdgeId", edgeId);
        tag.putDouble("Distance", distance);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        edgeId = tag.getLong("EdgeId");
        distance = tag.getDouble("Distance");
    }
}
