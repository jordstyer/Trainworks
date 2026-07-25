package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A single track-graph edge is rendered in-world as a run of these, placed
 * along its Bezier LUT (design/track-graph.md §4). Placeholder single-block-
 * wide shape for now -- the 3-wide gauge model (design/track-graph.md §2.4)
 * is a later rendering pass.
 */
public class TrackSegmentBlock extends Block implements EntityBlock {
    // A thin slab roughly centered on the curve sample height, matching the
    // vertical center-line anchors use (TrackGraph uses Vec3.atCenterOf).
    private static final VoxelShape SHAPE = Block.box(0, 6, 0, 16, 10, 16);

    public TrackSegmentBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrackSegmentBlockEntity(pos, state);
    }
}
