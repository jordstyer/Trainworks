package com.trainworks.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;

import java.util.ArrayList;
import java.util.List;

/**
 * A single assembled carriage: the blocks a player built above a bogie,
 * captured and moved out of the world into this entity. See
 * design/trains.md §2.2/§3.
 *
 * <p>First-pass scope: renders exactly where it was assembled, with no
 * rotation applied (captured offsets render exactly as built) and no
 * movement yet -- see {@code CarriageAssembler} and
 * {@code com.trainworks.client.CarriageRenderer}. Bogie-derived position/
 * orientation math (design/trains.md §3.3) and actual movement are later
 * steps once this is confirmed rendering correctly.</p>
 *
 * <p>Block states are network-synced (via {@link IEntityAdditionalSpawnData})
 * and saved to disk as raw block-state registry ids rather than the fuller
 * descriptive NBT format ({@code NbtUtils.writeBlockState}/{@code
 * readBlockState}) -- simpler to implement, at the cost of not being
 * resilient to block-state id renumbering across game/mod updates. Worth
 * revisiting before this is depended on for real saves.</p>
 */
public class CarriageEntity extends Entity implements IEntityAdditionalSpawnData {

    public record CapturedBlock(BlockPos relativeOffset, BlockState state) {
    }

    private List<CapturedBlock> capturedBlocks = List.of();

    public CarriageEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public void setCapturedBlocks(List<CapturedBlock> blocks) {
        this.capturedBlocks = List.copyOf(blocks);
    }

    public List<CapturedBlock> capturedBlocks() {
        return capturedBlocks;
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
            BlockPos relative = BlockPos.of(entry.getLong("Pos"));
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(entry.getInt("State"));
            blocks.add(new CapturedBlock(relative, state));
        }
        this.capturedBlocks = blocks;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CapturedBlock block : capturedBlocks) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", block.relativeOffset().asLong());
            entry.putInt("State", Block.BLOCK_STATE_REGISTRY.getId(block.state()));
            list.add(entry);
        }
        tag.put("Blocks", list);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeVarInt(capturedBlocks.size());
        for (CapturedBlock block : capturedBlocks) {
            buffer.writeBlockPos(block.relativeOffset());
            buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<CapturedBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos relative = buffer.readBlockPos();
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buffer.readVarInt());
            blocks.add(new CapturedBlock(relative, state));
        }
        this.capturedBlocks = blocks;
    }
}
