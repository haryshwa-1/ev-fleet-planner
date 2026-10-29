package com.moveinsync.evfleet.core.planner;

/**
 * Tuning knobs of the heuristic. None of them can make a plan break a hard rule;
 * they only change which feasible option is preferred.
 *
 * @param minChargeMinutes   shortest session worth plugging in for
 * @param chargeTargetFraction charge up to this share when time allows (the fast part of the curve)
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
        int hubsToTry,
        double lowSocFraction,
        double lowSocPenaltyKm,
        double chargeDetourPenaltyKm,
        double rupeesPerKmEquivalent,
        double idleKmPerHour) {

    public PlannerConfig with(String key, double value) {
        return switch (key) {
            case "lowSoc" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, hubsToTry, value, lowSocPenaltyKm,
                    chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "lowSocPenalty" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, hubsToTry, lowSocFraction,
                    value, chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "target" -> new PlannerConfig(minChargeMinutes, value, hubsToTry, lowSocFraction, lowSocPenaltyKm,
                    chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "hubs" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, (int) value, lowSocFraction,
                    lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent, idleKmPerHour);
            case "idle" -> new PlannerConfig(minChargeMinutes, chargeTargetFraction, hubsToTry, lowSocFraction,
                    lowSocPenaltyKm, chargeDetourPenaltyKm, rupeesPerKmEquivalent, value);
            default -> throw new IllegalArgumentException("unknown planner option " + key);
        };
    }

    public static PlannerConfig defaults() {
        return new PlannerConfig(10, 0.80, 2, 0.50, 15.0, 3.0, 20.0, 2.0);
    }
}
