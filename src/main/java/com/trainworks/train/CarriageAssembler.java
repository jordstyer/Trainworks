package com.trainworks.train;

import com.trainworks.ModEntities;
import com.trainworks.track.TrackAnchorBlock;
import com.trainworks.track.TrackSegmentBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flood-fills whatever was built directly above a bogie, captures it into a
 * {@link CarriageEntity}, and clears the original blocks (including the
 * bogie itself) out of the world. See design/trains.md §5.1.
 *
 * <p>First-pass scope: single bogie, no mandatory-gap/multi-carriage logic
 * (design/trains.md §5.1 point 3) since there's only ever one carriage right
 * now. No block-entity data (chest contents etc.) is captured yet -- only
 * the block state.</p>
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

    public static Result assemble(ServerLevel level, BlockPos bogiePos) {
        BlockPos seed = bogiePos.above();
        if (level.getBlockState(seed).isAir()) {
            return Result.fail("Nothing to assemble -- build something above the bogie first.");
        }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        List<CarriageEntity.CapturedBlock> captured = new ArrayList<>();

        while (!queue.isEmpty()) {
            if (captured.size() >= MAX_BLOCKS) {
                return Result.fail("Too large to assemble (max " + MAX_BLOCKS + " blocks).");
            }

            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || isTrackRelated(state)) {
                continue;
            }

            BlockPos relative = new BlockPos(
                    pos.getX() - bogiePos.getX(),
                    pos.getY() - bogiePos.getY(),
                    pos.getZ() - bogiePos.getZ());
            captured.add(new CarriageEntity.CapturedBlock(relative, state));

            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (visited.add(next)) {
                    BlockState nextState = level.getBlockState(next);
                    if (!nextState.isAir() && !isTrackRelated(nextState)) {
                        queue.add(next);
                    }
                }
            }
        }

        if (captured.isEmpty()) {
            return Result.fail("Nothing to assemble.");
        }

        for (CarriageEntity.CapturedBlock block : captured) {
            BlockPos worldPos = bogiePos.offset(block.relativeOffset());
            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(bogiePos, Blocks.AIR.defaultBlockState(), 3);

        CarriageEntity carriage = new CarriageEntity(ModEntities.CARRIAGE.get(), level);
        carriage.setCapturedBlocks(captured);
        carriage.moveTo(bogiePos.getX(), bogiePos.getY(), bogiePos.getZ(), 0f, 0f);
        level.addFreshEntity(carriage);

        return Result.ok(captured.size());
    }

    private static boolean isTrackRelated(BlockState state) {
        return state.getBlock() instanceof TrackAnchorBlock
                || state.getBlock() instanceof TrackSegmentBlock
                || state.getBlock() instanceof TrainBogieBlock;
    }
}
