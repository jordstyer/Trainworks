package com.trainworks.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * A single assembled carriage: the blocks a player built around one or two
 * bogies, captured and moved out of the world into this entity. See
 * design/trains.md §2.2/§3.
 *
 * <p>{@code CapturedBlock.relativeOffset} is a fractional {@link Vec3}, not
 * an integer {@link BlockPos}: for a two-bogie carriage the entity's
 * position is the midpoint between the two bogies' curve positions, which
 * is generally not block-aligned, so offsets relative to it aren't either
 * (design/trains.md §3.3). A single-bogie carriage is the degenerate case
 * where that midpoint happens to sit at one specific point instead.</p>
 *
 * <p>No rotation is applied at render time (see {@code CarriageRenderer}) --
 * captured offsets come straight from the world, where the player
 * necessarily built already aligned with the physical track, so they're
 * already correctly oriented. The entity's yaw is still computed and stored
 * (from the bogie(s)' track position) for future use once movement exists
 * and the carriage's heading can actually change from what it was
 * assembled at.</p>
 *
 * <p>Block states are network-synced (via {@link IEntityAdditionalSpawnData})
 * and saved to disk as raw block-state registry ids rather than the fuller
 * descriptive NBT format ({@code NbtUtils.writeBlockState}/{@code
 * readBlockState}) -- simpler to implement, at the cost of not being
 * resilient to block-state id renumbering across game/mod updates. Worth
 * revisiting before this is depended on for real saves.</p>
 */
public class CarriageEntity extends Entity implements IEntityAdditionalSpawnData {

    public record CapturedBlock(Vec3 relativeOffset, BlockState state) {
    }

    private List<CapturedBlock> capturedBlocks = List.of();
    // Bounds of the captured blocks, relative to this entity's position -- used to build a
    // culling box that actually covers the rendered content (see getBoundingBoxForCulling).
    // The declared EntityType size (1x1x1) does not; leaving the default culling box would let
    // the renderer get skipped for any carriage taller/wider than that, with no error or crash,
    // just nothing drawn -- exactly what happened before this was added.
    private Vec3 boundsMin = Vec3.ZERO;
    private Vec3 boundsMax = Vec3.ZERO;

    public CarriageEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public void setCapturedBlocks(List<CapturedBlock> blocks) {
        applyCapturedBlocks(blocks);
    }

    public List<CapturedBlock> capturedBlocks() {
        return capturedBlocks;
    }

    private void applyCapturedBlocks(List<CapturedBlock> blocks) {
        this.capturedBlocks = List.copyOf(blocks);

        double minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        for (CapturedBlock block : this.capturedBlocks) {
            Vec3 pos = block.relativeOffset();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }
        this.boundsMin = new Vec3(minX, minY, minZ);
        this.boundsMax = new Vec3(maxX, maxY, maxZ);
    }

    /**
     * Implementing {@link IEntityAdditionalSpawnData} does nothing by itself --
     * this override is what actually makes the server send Forge's custom spawn
     * packet (carrying {@link #writeSpawnData}'s payload) instead of the plain
     * vanilla one. Without it, {@link #readSpawnData} is never called on the
     * client and {@code capturedBlocks} silently stays empty forever.
     */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(
                getX() + boundsMin.x, getY() + boundsMin.y, getZ() + boundsMin.z,
                getX() + boundsMax.x + 1, getY() + boundsMax.y + 1, getZ() + boundsMax.z + 1);
    }

    @Override
    protected void defineSynchedData() {
        // No SynchedEntityData fields -- the captured block list is variable-length and
        // syncs via IEntityAdditionalSpawnData instead.
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ListTag list = tag.getList("Blocks", Tag.TAG_COMPOUND);
        List<CapturedBlock> blocks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Vec3 relative = new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(entry.getInt("State"));
            blocks.add(new CapturedBlock(relative, state));
        }
        applyCapturedBlocks(blocks);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CapturedBlock block : capturedBlocks) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", block.relativeOffset().x);
            entry.putDouble("Y", block.relativeOffset().y);
            entry.putDouble("Z", block.relativeOffset().z);
            entry.putInt("State", Block.BLOCK_STATE_REGISTRY.getId(block.state()));
            list.add(entry);
        }
        tag.put("Blocks", list);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeVarInt(capturedBlocks.size());
        for (CapturedBlock block : capturedBlocks) {
            Vec3 relative = block.relativeOffset();
            buffer.writeDouble(relative.x);
            buffer.writeDouble(relative.y);
            buffer.writeDouble(relative.z);
            buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<CapturedBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Vec3 relative = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buffer.readVarInt());
            blocks.add(new CapturedBlock(relative, state));
        }
        applyCapturedBlocks(blocks);
    }
}
