package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * A single track anchor -- one "port" in the track graph. {@code FACING_INDEX}
 * is a cosmetic 4-way (90°) snap of the node's precise facing, purely so the
 * player can see which way a connection will tend to leave the anchor before
 * committing to it (design/track-graph.md §7) -- the actual curve math always
 * uses the node's stored float yaw, never this snapped value.
 *
 * <p>4-way rather than the originally-planned 8-way: vanilla's blockstate
 * "variants" rotation only supports 0/90/180/270 (a fixed 16-entry lookup
 * table in {@code BlockModelRotation} -- anything else fails to resolve and
 * the block falls back to the missing-model checkerboard). A precise/8-way
 * indicator would need a custom BlockEntityRenderer instead; not worth the
 * extra complexity yet.</p>
 */
public class TrackAnchorBlock extends Block implements EntityBlock {
    public static final IntegerProperty FACING_INDEX = IntegerProperty.create("facing", 0, 3);

    public TrackAnchorBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING_INDEX, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING_INDEX);
    }

    /** Nearest 90° increment of {@code yaw}, as an index 0-3 (0 = south, matching the yaw=0 convention used throughout the track package). */
    public static int yawToIndex(float yaw) {
        float normalized = yaw % 360f;
        if (normalized < 0) {
            normalized += 360f;
        }
        return Math.round(normalized / 90f) % 4;
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
        float yaw = placer.getYRot();
        TrackGraph graph = TrackGraphSavedData.get((ServerLevel) level).graph();
        Node node = graph.addNode(pos.immutable(), yaw);
        if (level.getBlockEntity(pos) instanceof TrackAnchorBlockEntity anchor) {
            anchor.setNodeId(node.id());
        }
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(FACING_INDEX, yawToIndex(yaw)));
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof TrackAnchorBlockEntity anchor && anchor.hasNode()) {
                ServerLevel serverLevel = (ServerLevel) level;
                TrackGraph graph = TrackGraphSavedData.get(serverLevel).graph();

                // Capture each edge's curve (and id) before removeNode deletes the node data
                // that curveOf() needs -- otherwise there's nothing left to walk to find the
                // track segment blocks that need breaking too.
                graph.getNode(anchor.nodeId()).ifPresent(node -> {
                    for (long edgeId : new ArrayList<>(node.edgeIds())) {
                        graph.getEdge(edgeId).ifPresent(edge ->
                                TrackSegmentPlacer.remove(serverLevel, graph.curveOf(edge), edgeId));
                    }
                });

                graph.removeNode(anchor.nodeId());
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
