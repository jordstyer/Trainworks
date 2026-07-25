package com.trainworks.track;

import com.trainworks.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Places and removes the {@link TrackSegmentBlock}s that render an edge's
 * Bezier curve in-world, per design/track-graph.md §4. No obstruction/grade/
 * radius validation yet (§6 is a follow-up) -- {@link #place} only refuses to
 * overwrite existing solid, non-replaceable blocks so it doesn't wreck a
 * player's build.
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

        for (PosSample sample : samplePositions(curve)) {
            if (skip.contains(sample.pos())) {
                continue;
            }

            BlockState existing = level.getBlockState(sample.pos());
            if (!existing.isAir() && !existing.canBeReplaced()) {
                continue;
            }

            level.setBlockAndUpdate(sample.pos(), ModBlocks.TRACK_SEGMENT.get().defaultBlockState());
            if (level.getBlockEntity(sample.pos()) instanceof TrackSegmentBlockEntity segment) {
                segment.set(edge.id(), sample.distance());
            }
        }
    }

    /**
     * Breaks any {@link TrackSegmentBlock}s that belong to {@code edgeId}
     * along {@code curve}. Called when an edge is going away (an endpoint
     * anchor was broken) so those blocks don't linger as orphaned decoration
     * with a block entity pointing at a now-nonexistent edge.
     */
    public static void remove(ServerLevel level, BezierCurve curve, long edgeId) {
        for (PosSample sample : samplePositions(curve)) {
            if (level.getBlockEntity(sample.pos()) instanceof TrackSegmentBlockEntity segment
                    && segment.edgeId() == edgeId) {
                level.removeBlock(sample.pos(), false);
            }
        }
    }

    private record PosSample(BlockPos pos, double distance) {
    }

    private static List<PosSample> samplePositions(BezierCurve curve) {
        double length = curve.length();
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_SPACING));
        List<PosSample> result = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int i = 0; i <= steps; i++) {
            double distance = length * i / steps;
            Vec3 point = curve.positionAt(distance);
            BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
            if (seen.add(pos)) {
                result.add(new PosSample(pos, distance));
            }
        }
        return result;
    }
}
