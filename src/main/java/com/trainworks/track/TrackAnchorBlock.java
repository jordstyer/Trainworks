package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * A single track anchor -- one "port" in the track graph. The rendered model
 * doesn't yet snap to placement direction (design/track-graph.md §7 notes this
 * as a later cosmetic pass); the precise facing used for curve math is read
 * from the placer's look angle and stored on the {@link Node}, independent of
 * the block's appearance.
 */
public class TrackAnchorBlock extends Block implements EntityBlock {

    public TrackAnchorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrackAnchorBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || placer == null) {
            return;
        }
        TrackGraph graph = TrackGraphSavedData.get((ServerLevel) level).graph();
        Node node = graph.addNode(pos.immutable(), placer.getYRot());
        if (level.getBlockEntity(pos) instanceof TrackAnchorBlockEntity anchor) {
            anchor.setNodeId(node.id());
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof TrackAnchorBlockEntity anchor && anchor.hasNode()) {
                TrackGraphSavedData.get((ServerLevel) level).graph().removeNode(anchor.nodeId());
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
