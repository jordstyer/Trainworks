package com.trainworks.track;

import com.trainworks.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries {@code edgeId} + this block's slice of the edge's curve, both as a
 * representative {@code distance} (for future non-rendering uses -- signals,
 * cargo loaders) and as {@code localPoints}: a short polyline, in block-local
 * space, that the client-side renderer draws as a smooth ribbon instead of a
 * static cube model. See design/track-graph.md §4.
 *
 * <p>The client has no access to {@code TrackGraph} (that's server-only
 * SavedData), so the polyline is computed once server-side and synced down
 * via the standard BlockEntity update-packet mechanism -- the client never
 * needs to know about nodes, edges, or Bezier math to render this.</p>
 */
public class TrackSegmentBlockEntity extends BlockEntity {
    private long edgeId = -1L;
    private double distance;
    private List<Vec3> localPoints = List.of();

    public TrackSegmentBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRACK_SEGMENT.get(), pos, state);
    }

    public long edgeId() {
        return edgeId;
    }

    public double distance() {
        return distance;
    }

    public List<Vec3> localPoints() {
        return localPoints;
    }

    public void set(long edgeId, double distance, List<Vec3> localPoints) {
        this.edgeId = edgeId;
        this.distance = distance;
        this.localPoints = List.copyOf(localPoints);
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("EdgeId", edgeId);
        tag.putDouble("Distance", distance);
        ListTag pointsTag = new ListTag();
        for (Vec3 point : localPoints) {
            pointsTag.add(DoubleTag.valueOf(point.x));
            pointsTag.add(DoubleTag.valueOf(point.y));
            pointsTag.add(DoubleTag.valueOf(point.z));
        }
        tag.put("Points", pointsTag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        edgeId = tag.getLong("EdgeId");
        distance = tag.getDouble("Distance");
        ListTag pointsTag = tag.getList("Points", Tag.TAG_DOUBLE);
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i + 2 < pointsTag.size(); i += 3) {
            points.add(new Vec3(
                    ((DoubleTag) pointsTag.get(i)).getAsDouble(),
                    ((DoubleTag) pointsTag.get(i + 1)).getAsDouble(),
                    ((DoubleTag) pointsTag.get(i + 2)).getAsDouble()
            ));
        }
        localPoints = points;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
