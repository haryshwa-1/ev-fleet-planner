package com.moveinsync.evfleet.core.validate;

import java.util.List;
import java.util.Map;

/**
 * Result of validating one plan. Only the first {@link PlanValidator#MAX_LISTED} violations are
 * listed; {@code violationCount} is the true total.
 */
public record ValidationReport(
        boolean valid,
        int violationCount,
        List<Violation> violations,
        Map<Rule, Long> violationsByRule,
        long checksPerformed,
        Stats stats) {

    public record Stats(
            int tripsTotal,
            int tripsAssigned,
            int tripsUnassigned,
            int chargingSessions,
            double recomputedChargingCostRupees,
            double lowestSocPercent,
            Map<String, Integer> peakChargersInUseByHub) {
    }

    public String summary() {
        return String.format("%s | checks=%d violations=%d | trips assigned=%d/%d unassigned=%d | sessions=%d "
                        + "cost=Rs %.2f | lowest SoC=%.1f%% | peak chargers in use=%s",
                valid ? "VALID" : "INVALID", checksPerformed, violationCount,
                stats.tripsAssigned(), stats.tripsTotal(), stats.tripsUnassigned(), stats.chargingSessions(),
                stats.recomputedChargingCostRupees(), stats.lowestSocPercent(), stats.peakChargersInUseByHub());
    }
}
