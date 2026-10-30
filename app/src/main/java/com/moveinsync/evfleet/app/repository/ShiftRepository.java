package com.moveinsync.evfleet.app.repository;

import com.moveinsync.evfleet.app.domain.Shift;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/** Every lookup takes the tenant, so another tenant's shift simply does not exist (404). */
public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByIdAndTenantId(Long id, String tenantId);

    Optional<Shift> findFirstByTenantIdOrderByIdDesc(String tenantId);

    /** Takes the shift's row lock: the single-writer guarantee, also across app instances. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shift s where s.id = :id and s.tenantId = :tenantId")
    Optional<Shift> lockByIdAndTenantId(Long id, String tenantId);
}
