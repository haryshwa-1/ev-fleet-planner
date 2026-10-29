package com.moveinsync.evfleet.core.energy;

/**
 * Piecewise-linear charging curve.
 * <pre>
 *   0% .. knee (80%) : effective power = powerKW x efficiency            (fast, constant-current phase)
 *   knee .. 100%     : effective power = powerKW x efficiency x slowRatio (taper, constant-voltage phase)
 * </pre>
 * Real curves bend smoothly above 80%; a flat slow segment is a slightly pessimistic,
 * O(1), exactly invertible approximation, which is what a planner needs.
 */
public record TwoSegmentChargingCurve(double kneeFraction, double slowRatio, double efficiency) implements ChargingCurve {

    public TwoSegmentChargingCurve {
        if (kneeFraction <= 0 || kneeFraction > 1) throw new IllegalArgumentException("kneeFraction in (0,1]");
        if (slowRatio <= 0 || slowRatio > 1) throw new IllegalArgumentException("slowRatio in (0,1]");
        if (efficiency <= 0 || efficiency > 1) throw new IllegalArgumentException("efficiency in (0,1]");
    }

    public static TwoSegmentChargingCurve standard() {
        return new TwoSegmentChargingCurve(0.80, 0.30, 0.92);
    }

    @Override
    public double socAfter(double batteryKWh, double socKWh, double powerKW, double minutes) {
        double knee = batteryKWh * kneeFraction;
        double fastPerMin = powerKW * efficiency / 60.0;
        double slowPerMin = fastPerMin * slowRatio;
        double soc = socKWh;
        double left = minutes;
        if (soc < knee) {
            double minutesToKnee = (knee - soc) / fastPerMin;
            if (left <= minutesToKnee) return soc + left * fastPerMin;
            soc = knee;
            left -= minutesToKnee;
        }
        return Math.min(batteryKWh, soc + left * slowPerMin);
    }

    @Override
    public double minutesToReach(double batteryKWh, double fromKWh, double toKWh, double powerKW) {
        if (toKWh <= fromKWh) return 0;
        if (toKWh > batteryKWh + 1e-9) return Double.POSITIVE_INFINITY;
        double knee = batteryKWh * kneeFraction;
        double fastPerMin = powerKW * efficiency / 60.0;
        double slowPerMin = fastPerMin * slowRatio;
        double fastPart = Math.max(0, Math.min(toKWh, knee) - fromKWh);
        double slowPart = Math.max(0, toKWh - Math.max(fromKWh, knee));
        return fastPart / fastPerMin + slowPart / slowPerMin;
    }
}
