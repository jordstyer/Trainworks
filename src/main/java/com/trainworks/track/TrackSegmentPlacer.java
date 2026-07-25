package com.trainworks.track;

import com.trainworks.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Places and removes the {@link TrackSegmentBlock}s that render an edge's
 * Bezier curve in-world, per design/track-graph.md §4. No obstruction/grade/
 * radius validation yet (§6 is a follow-up) -- {@link #place} only refuses to
 * overwrite existing solid, non-replaceable blocks so it doesn't wreck a
 * player's build.
 *
 * <p>The curve is divided into {@link Slice}s, one per unique block position
 * it passes through, in order. Each slice gets a short local polyline (a few
 * sub-samples across that slice's distance range, converted to block-local
 * coordinates) stored on its {@link TrackSegmentBlockEntity} -- that's what
 * the client-side renderer draws as a continuous ribbon instead of a static
 * cube, without the client ever needing the curve math itself.</p>
 */
public final class TrackSegmentPlacer {
    private static final double SAMPLE_SPACING = 0.5;
    private static final int POLYLINE_SUBSAMPLES = 4;

    private TrackSegmentPlacer() {
    }

    public static void place(ServerLevel level, TrackGraph graph, Edge edge) {
        BezierCurve curve = graph.curveOf(edge);
        Node nodeA = graph.getNode(edge.nodeA()).orElseThrow();
        Node nodeB = graph.getNode(edge.nodeB()).orElseThrow();

        Set<BlockPos> skip = Set.of(nodeA.pos(), nodeB.pos());

        for (Slice slice : computeSlices(curve)) {
            if (skip.contains(slice.pos())) {
                continue;
            }

            BlockState existing = level.getBlockState(slice.pos());
            if (!existing.isAir() && !existing.canBeReplaced()) {
                continue;
            }

            level.setBlockAndUpdate(slice.pos(), ModBlocks.TRACK_SEGMENT.get().defaultBlockState());
            if (level.getBlockEntity(slice.pos()) instanceof TrackSegmentBlockEntity segment) {
                segment.set(edge.id(), slice.startDistance(), samplePolyline(curve, slice));
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
        for (Slice slice : computeSlices(curve)) {
            if (level.getBlockEntity(slice.pos()) instanceof TrackSegmentBlockEntity segment
                    && segment.edgeId() == edgeId) {
                level.removeBlock(slice.pos(), false);
            }
        }
    }

    private record Slice(BlockPos pos, double startDistance, double endDistance) {
    }

    /**
     * Partitions the curve into contiguous, non-overlapping slices, one per
     * run of consecutive fine samples that land in the same block position.
     */
    private static List<Slice> computeSlices(BezierCurve curve) {
        double length = curve.length();
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_SPACING));

        List<Slice> slices = new ArrayList<>();
        BlockPos currentPos = null;
        double currentStart = 0;

        for (int i = 0; i <= steps; i++) {
            double distance = length * i / steps;
            Vec3 point = curve.positionAt(distance);
            BlockPos pos = BlockPos.containing(point.x, point.y, point.z);

            if (currentPos == null) {
                currentPos = pos;
                currentStart = distance;
            } else if (!pos.equals(currentPos)) {
                slices.add(new Slice(currentPos, currentStart, distance));
                currentPos = pos;
                currentStart = distance;
            }
        }
        if (currentPos != null) {
            slices.add(new Slice(currentPos, currentStart, length));
        }
        return slices;
    }

    private static List<Vec3> samplePolyline(BezierCurve curve, Slice slice) {
        Vec3 origin = new Vec3(slice.pos().getX(), slice.pos().getY(), slice.pos().getZ());
        List<Vec3> points = new ArrayList<>(POLYLINE_SUBSAMPLES + 1);
        for (int i = 0; i <= POLYLINE_SUBSAMPLES; i++) {
            double d = slice.startDistance()
                    + (slice.endDistance() - slice.startDistance()) * i / POLYLINE_SUBSAMPLES;
            points.add(curve.positionAt(d).subtract(origin));
        }
        return points;
    }
}
