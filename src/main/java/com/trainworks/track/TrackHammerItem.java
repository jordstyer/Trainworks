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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The linking tool: right-click one anchor to select it, right-click a second
 * anchor to connect them. See design/track-graph.md §7. No grade/radius/
 * obstruction validation yet (§6 of that doc) -- that's a follow-up pass;
 * this only rejects connecting an anchor to itself or to one it's already
 * directly connected to.
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

        TrackGraph graph = TrackGraphSavedData.get((ServerLevel) level).graph();
        SELECTIONS.remove(playerId);

        if (alreadyConnected(graph, previous.nodeId(), anchor.nodeId())) {
            player.displayClientMessage(Component.literal("Those anchors are already connected."), true);
            return InteractionResult.CONSUME;
        }

        Edge edge = graph.connect(previous.nodeId(), anchor.nodeId());
        player.displayClientMessage(Component.literal(String.format("Connected -- edge length %.1f blocks.", edge.length())), true);
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
