package com.trainworks.track;

import com.trainworks.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Walks a freshly-created edge's Bezier LUT and places {@link TrackSegmentBlock}s
 * along it. See design/track-graph.md §4. No obstruction/grade/radius
 * validation yet (§6 is a follow-up) -- this only refuses to overwrite
 * existing solid, non-replaceable blocks so it doesn't wreck a player's build.
 */
public final class TrackSegmentPlacer {
    private static final double SAMPLE_SPACING = 0.5;

    private TrackSegmentPlacer() {
    }

    public static void place(ServerLevel level, TrackGraph graph, Edge edge) {
        BezierCurve curve = graph.curveOf(edge);
        Node nodeA = graph.getNode(edge.nodeA()).orElseThrow();
        Node nodeB = graph.getNode(edge.nodeB()).orElseThrow();

        Set<BlockPos> skip = new HashSet<>();
        skip.add(nodeA.pos());
        skip.add(nodeB.pos());

        double length = curve.length();
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_SPACING));
        Set<BlockPos> placedHere = new HashSet<>();

        for (int i = 0; i <= steps; i++) {
            double distance = length * i / steps;
            Vec3 point = curve.positionAt(distance);
            BlockPos pos = BlockPos.containing(point.x, point.y, point.z);

            if (skip.contains(pos) || !placedHere.add(pos)) {
                continue;
            }

            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir() && !existing.canBeReplaced()) {
                continue;
            }

            level.setBlockAndUpdate(pos, ModBlocks.TRACK_SEGMENT.get().defaultBlockState());
            if (level.getBlockEntity(pos) instanceof TrackSegmentBlockEntity segment) {
                segment.set(edge.id(), distance);
            }
        }
    }
}
