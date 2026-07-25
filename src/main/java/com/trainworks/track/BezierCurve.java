package com.trainworks.track;

import net.minecraft.world.phys.Vec3;

/**
 * Cubic Bezier construction + arc-length sampling for a single track edge.
 * See design/track-graph.md §2.3 and §3.
 */
public final class BezierCurve {
    /** Fraction of the horizontal node-to-node distance used for control-point offset. Tune per design/track-graph.md §8. */
    public static final double CONTROL_LENGTH_FACTOR = 1.0 / 3.0;
    private static final int LUT_SAMPLES = 48;

    private final Vec3 p0, p1, p2, p3;
    private final double[] tValues = new double[LUT_SAMPLES + 1];
    private final double[] cumulativeLength = new double[LUT_SAMPLES + 1];
    private final double totalLength;

    private BezierCurve(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
        this.p0 = p0;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
        buildLut();
        this.totalLength = cumulativeLength[LUT_SAMPLES];
    }

    /**
     * @param posA        world position of node A
     * @param tangentAYaw node A's locked-in tangent yaw, oriented toward node B (degrees)
     * @param posB        world position of node B
     * @param tangentBYaw node B's locked-in tangent yaw, oriented toward node A (degrees)
     */
    public static BezierCurve create(Vec3 posA, float tangentAYaw, Vec3 posB, float tangentBYaw) {
        double horizontalDistance = Math.sqrt(square(posB.x - posA.x) + square(posB.z - posA.z));
        double controlLength = horizontalDistance * CONTROL_LENGTH_FACTOR;

        Vec3 dirA = yawToHorizontalVec(tangentAYaw);
        Vec3 dirB = yawToHorizontalVec(tangentBYaw);

        Vec3 rawP1 = posA.add(dirA.scale(controlLength));
        Vec3 rawP2 = posB.subtract(dirB.scale(controlLength));

        // Control points stay flat at their end's height -- this is what produces the eased
        // slope transition (design/track-graph.md §2.3) instead of a kinked straight ramp.
        Vec3 p1 = new Vec3(rawP1.x, posA.y, rawP1.z);
        Vec3 p2 = new Vec3(rawP2.x, posB.y, rawP2.z);

        return new BezierCurve(posA, p1, p2, posB);
    }

    private static double square(double v) {
        return v * v;
    }

    private static Vec3 yawToHorizontalVec(float yawDegrees) {
        double rad = Math.toRadians(yawDegrees);
        // Matches Minecraft's yaw convention: 0 = +Z, 90 = -X.
        return new Vec3(-Math.sin(rad), 0, Math.cos(rad));
    }

    private void buildLut() {
        Vec3 previous = evaluateRaw(0);
        tValues[0] = 0;
        cumulativeLength[0] = 0;
        double accumulated = 0;
        for (int i = 1; i <= LUT_SAMPLES; i++) {
            double t = (double) i / LUT_SAMPLES;
            Vec3 point = evaluateRaw(t);
            accumulated += point.distanceTo(previous);
            tValues[i] = t;
            cumulativeLength[i] = accumulated;
            previous = point;
        }
    }

    private Vec3 evaluateRaw(double t) {
        double u = 1 - t;
        double a = u * u * u;
        double b = 3 * u * u * t;
        double c = 3 * u * t * t;
        double d = t * t * t;
        return new Vec3(
                a * p0.x + b * p1.x + c * p2.x + d * p3.x,
                a * p0.y + b * p1.y + c * p2.y + d * p3.y,
                a * p0.z + b * p1.z + c * p2.z + d * p3.z
        );
    }

    public double length() {
        return totalLength;
    }

    /** Position at the given distance along the curve, clamped to [0, length()]. */
    public Vec3 positionAt(double distance) {
        return evaluateRaw(tAtDistance(distance));
    }

    /** Horizontal yaw (degrees) of the curve's direction of travel at the given distance. */
    public float yawAt(double distance) {
        double t = tAtDistance(distance);
        double dt = 0.001;
        Vec3 a = evaluateRaw(Math.max(0, t - dt));
        Vec3 b = evaluateRaw(Math.min(1, t + dt));
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private double tAtDistance(double distance) {
        distance = Math.max(0, Math.min(totalLength, distance));
        int lo = 0, hi = LUT_SAMPLES;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (cumulativeLength[mid] < distance) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo == 0) {
            return 0;
        }
        double segStart = cumulativeLength[lo - 1];
        double segEnd = cumulativeLength[lo];
        double segFrac = segEnd > segStart ? (distance - segStart) / (segEnd - segStart) : 0;
        return tValues[lo - 1] + (tValues[lo] - tValues[lo - 1]) * segFrac;
    }
}
