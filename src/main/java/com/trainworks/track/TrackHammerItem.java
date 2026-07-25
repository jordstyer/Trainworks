package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The linking tool. Right-click one anchor to select it, right-click a
 * second anchor to connect them -- see design/track-graph.md §7. No grade/
 * radius/obstruction validation yet (§6) -- that's a follow-up pass; this
 * only rejects connecting an anchor to itself or to one it's already
 * directly connected to.
 *
 * <p>Sneak-right-click an anchor that has no edges yet to re-face it toward
 * your current look direction instead of selecting it -- lets you fix a
 * badly-facing anchor (see the facing indicator on the block) before you
 * commit to a connection.</p>
 */
public class TrackHammerItem extends Item {

    // Selection is deliberately transient (not persisted) -- it's tool state, not world data,
    // and resetting on relog/restart is an acceptable trade-off for the simplicity it buys.
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    public TrackHammerItem(Properties properties) {
        super(properties);
    }

    private record Selection(ResourceKey<Level> dimension, BlockPos pos, long nodeId) {
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TrackAnchorBlockEntity anchor) || !anchor.hasNode()) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        TrackGraph graph = TrackGraphSavedData.get(serverLevel).graph();

        if (context.isSecondaryUseActive()) {
            return reface(serverLevel, graph, pos, anchor, player);
        }

        UUID playerId = player.getUUID();
        Selection previous = SELECTIONS.get(playerId);

        if (previous == null || !previous.dimension().equals(level.dimension())) {
            SELECTIONS.put(playerId, new Selection(level.dimension(), pos.immutable(), anchor.nodeId()));
            player.displayClientMessage(Component.literal("Track anchor selected -- right-click another anchor to connect."), true);
            return InteractionResult.CONSUME;
        }

        if (previous.pos().equals(pos)) {
            SELECTIONS.remove(playerId);
            player.displayClientMessage(Component.literal("Selection cleared."), true);
            return InteractionResult.CONSUME;
        }

        SELECTIONS.remove(playerId);

        if (alreadyConnected(graph, previous.nodeId(), anchor.nodeId())) {
            player.displayClientMessage(Component.literal("Those anchors are already connected."), true);
            return InteractionResult.CONSUME;
        }

        Edge edge = graph.connect(previous.nodeId(), anchor.nodeId());
        TrackSegmentPlacer.place(serverLevel, graph, edge);
        player.displayClientMessage(Component.literal(String.format("Connected -- edge length %.1f blocks.", edge.length())), true);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult reface(ServerLevel level, TrackGraph graph, BlockPos pos,
                                             TrackAnchorBlockEntity anchor, Player player) {
        float yaw = player.getYRot();
        if (!graph.refaceNode(anchor.nodeId(), yaw)) {
            player.displayClientMessage(Component.literal("Can't re-face an anchor that's already connected."), true);
            return InteractionResult.CONSUME;
        }
        BlockState state = level.getBlockState(pos);
        level.setBlockAndUpdate(pos, state.setValue(TrackAnchorBlock.FACING_INDEX, TrackAnchorBlock.yawToIndex(yaw)));
        player.displayClientMessage(Component.literal("Anchor re-faced."), true);
        return InteractionResult.CONSUME;
    }

    private static boolean alreadyConnected(TrackGraph graph, long nodeAId, long nodeBId) {
        Optional<Node> nodeA = graph.getNode(nodeAId);
        if (nodeA.isEmpty()) {
            return false;
        }
        for (long edgeId : nodeA.get().edgeIds()) {
            Optional<Edge> edge = graph.getEdge(edgeId);
            if (edge.isPresent() && (edge.get().nodeA() == nodeBId || edge.get().nodeB() == nodeBId)) {
                return true;
            }
        }
        return false;
    }
}
