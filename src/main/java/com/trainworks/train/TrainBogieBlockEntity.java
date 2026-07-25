package com.trainworks.train;

import com.trainworks.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A bogie marks a wheel position on the track -- carriages are assembled
 * around a pair of these. See design/trains.md §2.1/§5.1.
 *
 * <p>Placement (in {@code TrainBogieBlock}) reads {@code edgeId}/{@code
 * distance} straight off the {@code TrackSegmentBlockEntity} directly below
 * it -- an approximation using that segment's own representative distance,
 * not the bogie's exact position along the curve. Fine for the current
 * "does assembly/rendering work at all" milestone; worth tightening later if
 * bogie position needs to be more precise than one segment-slice's worth.</p>
 */
public class TrainBogieBlockEntity extends BlockEntity {
    private long edgeId = -1L;
    private double distance;

    public TrainBogieBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRAIN_BOGIE.get(), pos, state);
    }

    public boolean hasTrack() {
        return edgeId != -1L;
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
