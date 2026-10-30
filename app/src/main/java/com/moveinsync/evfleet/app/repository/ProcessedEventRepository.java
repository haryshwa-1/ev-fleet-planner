package com.moveinsync.evfleet.app.repository;

import com.moveinsync.evfleet.app.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    Optional<ProcessedEvent> findByShiftIdAndIdempotencyKey(Long shiftId, String idempotencyKey);
}
