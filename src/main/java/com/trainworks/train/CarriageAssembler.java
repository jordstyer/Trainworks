package com.trainworks.train;

import com.trainworks.ModEntities;
import com.trainworks.track.BezierCurve;
import com.trainworks.track.Edge;
import com.trainworks.track.TrackAnchorBlock;
import com.trainworks.track.TrackGraph;
import com.trainworks.track.TrackGraphSavedData;
import com.trainworks.track.TrackSegmentBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Flood-fills whatever was built directly above/around a bogie, captures it
 * into a {@link CarriageEntity}, and clears the original blocks (including
 * any bogies involved) out of the world. See design/trains.md §5.1.
 *
 * <p>The fill also detects a <em>second</em> bogie if the built structure
 * reaches one -- that's what makes the two-bogie transform (design/
 * trains.md §3.3) possible: the carriage's position becomes the midpoint
 * between both bogies' curve positions, and its yaw comes from the line
 * between them, rather than a single point's tangent. Both bogies must
 * reference the same track edge (a simple, deliberate constraint for now --
 * spanning a junction is a later problem). A single connected bogie still
 * works exactly as before (degenerates to the one-point case). More than
 * two connected bogies is rejected outright.</p>
 *
 * <p>No mandatory-gap/multi-carriage logic (design/trains.md §5.1 point 3)
 * since there's only ever one carriage right now. No block-entity data
 * (chest contents etc.) is captured yet -- only the block state.</p>
 */
public final class CarriageAssembler {
    private static final int MAX_BLOCKS = 256;

    private CarriageAssembler() {
    }

    public record Result(boolean success, String message) {
        public static Result ok(int blockCount) {
            return new Result(true, "Assembled " + blockCount + " blocks.");
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private record BogieRef(long edgeId, double distance) {
    }

    public static Result assemble(ServerLevel level, BlockPos clickedBogiePos) {
        BlockPos seed = clickedBogiePos.above();
        if (level.getBlockState(seed).isAir()) {
            return Result.fail("Nothing to assemble -- build something above the bogie first.");
        }

        Set<BlockPos> foundBogies = new HashSet<>();
        foundBogies.add(clickedBogiePos);

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        List<BlockPos> capturedPositions = new ArrayList<>();
        List<BlockState> capturedStates = new ArrayList<>();

        while (!queue.isEmpty()) {
            if (capturedPositions.size() >= MAX_BLOCKS) {
                return Result.fail("Too large to assemble (max " + MAX_BLOCKS + " blocks).");
            }

            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.getBlock() instanceof TrainBogieBlock) {
                // A second bogie ends the fill on that branch -- it's captured as a bogie
                // reference, not as a normal block, and nothing beyond it is explored.
                foundBogies.add(pos);
                continue;
            }
            if (isTrackRelated(state)) {
                continue;
            }

            capturedPositions.add(pos);
            capturedStates.add(state);

            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (visited.add(next) && !level.getBlockState(next).isAir()) {
                    queue.add(next);
                }
            }
        }

        if (capturedPositions.isEmpty()) {
            return Result.fail("Nothing to assemble.");
        }
        if (foundBogies.size() > 2) {
            return Result.fail("Too many bogies connected -- carriages support at most 2 for now.");
        }

        List<BogieRef> bogieRefs = new ArrayList<>();
        for (BlockPos bogiePos : foundBogies) {
            if (level.getBlockEntity(bogiePos) instanceof TrainBogieBlockEntity entity && entity.hasTrack()) {
                bogieRefs.add(new BogieRef(entity.edgeId(), entity.distance()));
            }
        }

        Vec3 spawnPos = Vec3.atLowerCornerOf(clickedBogiePos);
        float spawnYaw = 0f;
        long refEdgeId = -1L;
        double refDistance = 0;

        if (bogieRefs.size() == 2) {
            BogieRef a = bogieRefs.get(0);
            BogieRef b = bogieRefs.get(1);
            if (a.edgeId() != b.edgeId()) {
                return Result.fail("Both bogies must be on the same track edge.");
            }
            Optional<BezierCurve> curve = curveFor(level, a.edgeId());
            if (curve.isEmpty()) {
                return Result.fail("Bogies reference a track edge that no longer exists.");
            }
            Vec3 posA = curve.get().positionAt(a.distance()).subtract(0.5, 0.5, 0.5);
            Vec3 posB = curve.get().positionAt(b.distance()).subtract(0.5, 0.5, 0.5);
            spawnPos = posA.add(posB).scale(0.5);
            spawnYaw = yawBetween(posA, posB);
            refEdgeId = a.edgeId();
            refDistance = (a.distance() + b.distance()) / 2.0;
        } else if (bogieRefs.size() == 1) {
            BogieRef only = bogieRefs.get(0);
            Optional<BezierCurve> curve = curveFor(level, only.edgeId());
            if (curve.isPresent()) {
                spawnPos = curve.get().positionAt(only.distance()).subtract(0.5, 0.5, 0.5);
                spawnYaw = curve.get().yawAt(only.distance());
                refEdgeId = only.edgeId();
                refDistance = only.distance();
            }
        }

        List<CarriageEntity.CapturedBlock> captured = new ArrayList<>(capturedPositions.size());
        for (int i = 0; i < capturedPositions.size(); i++) {
            Vec3 relative = Vec3.atLowerCornerOf(capturedPositions.get(i)).subtract(spawnPos);
            captured.add(new CarriageEntity.CapturedBlock(relative, capturedStates.get(i)));
        }

        for (BlockPos pos : capturedPositions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        for (BlockPos bogiePos : foundBogies) {
            level.setBlock(bogiePos, Blocks.AIR.defaultBlockState(), 3);
        }

        CarriageEntity carriage = new CarriageEntity(ModEntities.CARRIAGE.get(), level);
        carriage.setCapturedBlocks(captured);
        carriage.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, spawnYaw, 0f);
        carriage.setTrackReference(refEdgeId, refDistance, spawnYaw);
        level.addFreshEntity(carriage);

        return Result.ok(captured.size());
    }

    private static Optional<BezierCurve> curveFor(ServerLevel level, long edgeId) {
        TrackGraph graph = TrackGraphSavedData.get(level).graph();
        return graph.getEdge(edgeId).map(graph::curveOf);
    }

    private static float yawBetween(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static boolean isTrackRelated(BlockState state) {
        return state.getBlock() instanceof TrackAnchorBlock
                || state.getBlock() instanceof TrackSegmentBlock;
    }
}
