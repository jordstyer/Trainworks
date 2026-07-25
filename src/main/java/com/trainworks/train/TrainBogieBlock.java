package com.trainworks.train;

import com.trainworks.track.TrackSegmentBlock;
import com.trainworks.track.TrackSegmentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Must sit directly on a {@link TrackSegmentBlock} -- auto-breaks otherwise,
 * same pattern vanilla uses for flowers/torches needing specific support.
 * See design/trains.md §2.1.
 */
public class TrainBogieBlock extends Block implements EntityBlock {
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 4, 14);

    public TrainBogieBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof TrackSegmentBlock;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrainBogieBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos.below()) instanceof TrackSegmentBlockEntity segment
                && level.getBlockEntity(pos) instanceof TrainBogieBlockEntity bogie) {
            bogie.set(segment.edgeId(), segment.distance());
            if (placer instanceof Player player) {
                player.displayClientMessage(Component.literal(String.format(
                        "Bogie on edge #%d at ~%.1f blocks in.", segment.edgeId(), segment.distance())), true);
            }
        }
    }
}
