package com.trainworks.track;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Connect-time validation for a proposed edge: max grade, minimum curve
 * radius, and obstruction -- see design/track-graph.md §6. Runs against a
 * {@link BezierCurve} that hasn't been committed to the graph yet
 * ({@link TrackGraph#previewCurve}), so a rejected connection never creates
 * a node/edge or places any blocks.
 */
public final class TrackConnectionValidator {
    /** Max rise per horizontal run, e.g. 0.25 = 1 block up per 4 blocks across. Tune per §8. */
    public static final double MAX_GRADE = 0.25;
    /** Minimum curve radius, in blocks, before a bend is rejected as too sharp. Tune per §8. */
    public static final double MIN_CURVE_RADIUS = 3.0;
    private static final double SAMPLE_STEP = 0.25;

    private TrackConnectionValidator() {
    }

    public record Result(boolean valid, String reason) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * @param anchorAPos the anchor block position at one end of the curve, skipped by the
     *                    obstruction check since it's legitimately occupied by that anchor
     * @param anchorBPos the anchor block position at the other end, same reason
     */
    public static Result validate(ServerLevel level, BezierCurve curve, BlockPos anchorAPos, BlockPos anchorBPos) {
        double length = curve.length();
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_STEP));

        Result obstructionResult = checkObstructions(level, curve, length, steps, anchorAPos, anchorBPos);
        if (!obstructionResult.valid()) {
            return obstructionResult;
        }

        Result gradeResult = checkGrade(curve, length, steps);
        if (!gradeResult.valid()) {
            return gradeResult;
        }

        return checkCurveRadius(curve, length, steps);
    }

    private static Result checkObstructions(ServerLevel level, BezierCurve curve, double length, int steps,
                                             BlockPos anchorAPos, BlockPos anchorBPos) {
        for (int i = 0; i <= steps; i++) {
            double distance = length * i / steps;
            Vec3 point = curve.positionAt(distance);
            BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
            if (pos.equals(anchorAPos) || pos.equals(anchorBPos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                return Result.fail(String.format("Obstructed roughly %.1f blocks along the path", distance));
            }
        }
        return Result.ok();
    }

    private static Result checkGrade(BezierCurve curve, double length, int steps) {
        Vec3 previous = curve.positionAt(0);
        for (int i = 1; i <= steps; i++) {
            double distance = length * i / steps;
            Vec3 point = curve.positionAt(distance);

            double dx = point.x - previous.x;
            double dy = point.y - previous.y;
            double dz = point.z - previous.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);

            if (horiz > 1.0e-6) {
                double grade = Math.abs(dy) / horiz;
                if (grade > MAX_GRADE) {
                    return Result.fail(String.format(
                            "Too steep (%.0f%% grade around %.1f blocks in, max %.0f%%)",
                            grade * 100, distance, MAX_GRADE * 100));
                }
            }
            previous = point;
        }
        return Result.ok();
    }

    private static Result checkCurveRadius(BezierCurve curve, double length, int steps) {
        double stepLength = length / steps;
        double maxTurnRatePerBlock = 1.0 / MIN_CURVE_RADIUS;

        float previousYaw = curve.yawAt(0);
        for (int i = 1; i <= steps; i++) {
            double distance = length * i / steps;
            float yaw = curve.yawAt(distance);
            double turnDegrees = Math.abs(Mth.wrapDegrees(yaw - previousYaw));
            double turnRatePerBlock = Math.toRadians(turnDegrees) / Math.max(stepLength, 1.0e-6);

            if (turnRatePerBlock > maxTurnRatePerBlock) {
                return Result.fail(String.format(
                        "Curve too sharp (roughly %.1f block radius around %.1f blocks in, min %.1f)",
                        1.0 / turnRatePerBlock, distance, MIN_CURVE_RADIUS));
            }
            previousYaw = yaw;
        }
        return Result.ok();
    }
}
