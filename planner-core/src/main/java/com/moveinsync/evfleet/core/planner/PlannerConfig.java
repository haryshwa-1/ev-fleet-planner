package com.moveinsync.evfleet.core.planner;

/**
 * Tuning knobs of the heuristic. None of them can make a plan break a hard rule;
 * they only change which feasible option is preferred.
 *
 * @param minChargeMinutes   shortest session worth plugging in for
 * @param chargeTargetFraction charge up to this share when time allows (the fast part of the curve)
 * @param peakTargetFraction the same for a session that starts at a peak tariff: charge little more
 *                           than the next trip needs and top up when power is cheaper
 * @param hubsToTry          nearest hubs considered for a charge-then-serve option
 * @param lowSocFraction     a cab ending a trip below this share is nudged to charge first
 * @param lowSocPenaltyKm    how strongly (in km-equivalents) that nudge applies
 * @param chargeDetourPenaltyKm fixed km-equivalent cost of stopping to charge at all
 * @param rupeesPerKmEquivalent converts charging cost into the same unit as empty km
 * @param idleKmPerHour      km-equivalent cost of a cab waiting idle for an hour before the trip
 *                           (best-fit: keep early-free cabs for trips that need them)
 */
public record PlannerConfig(
        int minChargeMinutes,
        double chargeTargetFraction,
        double peakTargetFraction,
        int hubsToTry,
        double lowSocFraction,
        double lowSocPenaltyKm,
        double chargeDetourPenaltyKm,
        double rupeesPerKmEquivalent,
        double idleKmPerHour) {

    /** Option names accepted by {@link #with}, e.g. from the command line. */
    public static final java.util.List<String> OPTIONS =
            java.util.List.of("lowSoc", "lowSocPenalty", "target", "peakTarget", "hubsToTry", "idle");

    public PlannerConfig with(String key, double value) {
        return switch (key) {
            case "lowSoc" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, peakTargetFraction, hubsToTry,
                    value, lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "lowSocPenalty" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, peakTargetFraction,
                    hubsToTry, lowSocFraction, value, chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "target" -> new PlannerConfig(minChargeMinutes, value, peakTargetFraction, hubsToTry, lowSocFraction,
                    lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "hubsToTry" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, peakTargetFraction,
                    (int) value, lowSocFraction, lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent,
                    idleKmPerHour);
            case "idle" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, peakTargetFraction, hubsToTry,
                    lowSocFraction, lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent, value);
            case "peakTarget" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, value, hubsToTry,
                    lowSocFraction, lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            default -> throw new IllegalArgumentException("unknown planner option " + key);
        };
    }

    public static PlannerConfig defaults() {
        return new PlannerConfig(10, 0.80, 0.50, 2, 0.50, 15.0, 3.0, 20.0, 2.0);
    }
}
