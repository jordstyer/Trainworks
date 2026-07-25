package com.trainworks.track;

import net.minecraft.world.phys.Vec3;

/**
 * Piecewise curve for a single track edge: a short straight lead-in at node
 * A, a cubic Bezier doing all the actual bending in the middle, and a short
 * straight lead-out into node B. See design/track-graph.md §2.3 and §3.
 *
 * <p>A single whole-span Bezier is only exactly tangent to each anchor's
 * facing at a single infinitesimal point (t=0 or t=1) -- anything just
 * before that can still visibly curve, so track immediately touching an
 * anchor wouldn't reliably line up with whatever connects to that anchor's
 * other side. The straight leads guarantee an exact, finite-length straight
 * section at each end that matches the anchor's axis exactly.</p>
 *
 * <p>The leads are purely horizontal (no slope) since anchor tangents are
 * horizontal-only throughout this package -- for an edge with elevation
 * change, all of it happens across the middle Bezier, which is a minor
 * simplification worth revisiting if it looks wrong with steep grades.</p>
 */
public final class BezierCurve {
    /** Fraction of the *middle segment's* horizontal distance used for control-point offset. */
    public static final double CONTROL_LENGTH_FACTOR = 1.0 / 3.0;
    /** Target length of the straight lead-in/lead-out at each anchor, in blocks. */
    public static final double STRAIGHT_LEAD_LENGTH = 1.0;
    private static final int LUT_SAMPLES = 48;

    private final Vec3 posA;
    private final Vec3 dirA;
    private final Vec3 posB;
    private final Vec3 dirB;
    private final double leadLength;

    // Middle Bezier: mP0..mP3, sampled into an arc-length LUT exactly like before, just scoped
    // to the shortened middle span rather than the whole edge.
    private final Vec3 mP0, mP1, mP2, mP3;
    private final double[] tValues = new double[LUT_SAMPLES + 1];
    private final double[] cumulativeLength = new double[LUT_SAMPLES + 1];
    private final double middleLength;

    private BezierCurve(Vec3 posA, Vec3 dirA, Vec3 posB, Vec3 dirB, double leadLength,
                         Vec3 mP0, Vec3 mP1, Vec3 mP2, Vec3 mP3) {
        this.posA = posA;
        this.dirA = dirA;
        this.posB = posB;
        this.dirB = dirB;
        this.leadLength = leadLength;
        this.mP0 = mP0;
        this.mP1 = mP1;
        this.mP2 = mP2;
        this.mP3 = mP3;
        buildLut();
        this.middleLength = cumulativeLength[LUT_SAMPLES];
    }

    /**
     * @param posA        world position of node A
     * @param tangentAYaw node A's locked-in tangent yaw, oriented toward node B (degrees)
     * @param posB        world position of node B
     * @param tangentBYaw node B's locked-in tangent yaw, oriented toward node A (degrees)
     */
    public static BezierCurve create(Vec3 posA, float tangentAYaw, Vec3 posB, float tangentBYaw) {
        Vec3 dirA = yawToHorizontalVec(tangentAYaw);
        Vec3 dirB = yawToHorizontalVec(tangentBYaw);

        double horizontalDistance = Math.sqrt(square(posB.x - posA.x) + square(posB.z - posA.z));
        // Cap the lead so two very close anchors can't produce a negative-length middle span.
        double lead = Math.min(STRAIGHT_LEAD_LENGTH, horizontalDistance * 0.4);

        Vec3 midStart = posA.add(dirA.scale(lead));
        Vec3 midEnd = posB.subtract(dirB.scale(lead));

        double midHorizontalDistance = Math.sqrt(square(midEnd.x - midStart.x) + square(midEnd.z - midStart.z));
        double controlLength = midHorizontalDistance * CONTROL_LENGTH_FACTOR;

        Vec3 rawP1 = midStart.add(dirA.scale(controlLength));
        Vec3 rawP2 = midEnd.subtract(dirB.scale(controlLength));

        // Control points stay flat at their end's height -- this is what produces the eased
        // slope transition (design/track-graph.md §2.3) instead of a kinked straight ramp.
        Vec3 mP1 = new Vec3(rawP1.x, midStart.y, rawP1.z);
        Vec3 mP2 = new Vec3(rawP2.x, midEnd.y, rawP2.z);

        return new BezierCurve(posA, dirA, posB, dirB, lead, midStart, mP1, mP2, midEnd);
    }

    private static double square(double v) {
        return v * v;
    }

    private static Vec3 yawToHorizontalVec(float yawDegrees) {
        double rad = Math.toRadians(yawDegrees);
        // Matches Minecraft's yaw convention: 0 = +Z, 90 = -X.
        return new Vec3(-Math.sin(rad), 0, Math.cos(rad));
    }

    private static float horizontalVecToYaw(Vec3 dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    private void buildLut() {
        Vec3 previous = evaluateMiddleRaw(0);
        tValues[0] = 0;
        cumulativeLength[0] = 0;
        double accumulated = 0;
        for (int i = 1; i <= LUT_SAMPLES; i++) {
            double t = (double) i / LUT_SAMPLES;
            Vec3 point = evaluateMiddleRaw(t);
            accumulated += point.distanceTo(previous);
            tValues[i] = t;
            cumulativeLength[i] = accumulated;
            previous = point;
        }
    }

    private Vec3 evaluateMiddleRaw(double t) {
        double u = 1 - t;
        double a = u * u * u;
        double b = 3 * u * u * t;
        double c = 3 * u * t * t;
        double d = t * t * t;
        return new Vec3(
                a * mP0.x + b * mP1.x + c * mP2.x + d * mP3.x,
                a * mP0.y + b * mP1.y + c * mP2.y + d * mP3.y,
                a * mP0.z + b * mP1.z + c * mP2.z + d * mP3.z
        );
    }

    /** Total length: straight lead-in + middle Bezier + straight lead-out. */
    public double length() {
        return leadLength + middleLength + leadLength;
    }

    /** Position at the given distance along the curve, clamped to [0, length()]. */
    public Vec3 positionAt(double distance) {
        distance = Math.max(0, Math.min(length(), distance));

        if (distance <= leadLength) {
            return posA.add(dirA.scale(distance));
        }
        double afterLead = distance - leadLength;
        if (afterLead <= middleLength) {
            return evaluateMiddleRaw(tAtMiddleDistance(afterLead));
        }
        double intoEndLead = afterLead - middleLength;
        Vec3 endLeadStart = posB.subtract(dirB.scale(leadLength));
        return endLeadStart.add(dirB.scale(intoEndLead));
    }

    /** Horizontal yaw (degrees) of the curve's direction of travel at the given distance. */
    public float yawAt(double distance) {
        distance = Math.max(0, Math.min(length(), distance));

        if (distance <= leadLength) {
            return horizontalVecToYaw(dirA);
        }
        double afterLead = distance - leadLength;
        if (afterLead <= middleLength) {
            double t = tAtMiddleDistance(afterLead);
            double dt = 0.001;
            Vec3 a = evaluateMiddleRaw(Math.max(0, t - dt));
            Vec3 b = evaluateMiddleRaw(Math.min(1, t + dt));
            return horizontalVecToYaw(b.subtract(a));
        }
        return horizontalVecToYaw(dirB);
    }

    private double tAtMiddleDistance(double distance) {
        distance = Math.max(0, Math.min(middleLength, distance));
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
