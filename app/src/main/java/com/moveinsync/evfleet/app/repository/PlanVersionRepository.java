package com.moveinsync.evfleet.app.repository;

import com.moveinsync.evfleet.app.domain.PlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {

    Optional<PlanVersion> findByShiftIdAndVersion(Long shiftId, int version);

    /** The audit trail without the (large) plan bodies. */
    @Query("""
            select new com.moveinsync.evfleet.app.repository.PlanVersionRepository$Info(
                p.version, p.cause, p.backupTrips, p.chargingCost, p.planningMillis)
            from PlanVersion p where p.shiftId = :shiftId order by p.version""")
    List<Info> history(Long shiftId);

    record Info(int version, String cause, int backupTrips, double chargingCost, long planningMillis) {
    }
}
