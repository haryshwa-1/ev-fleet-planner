package com.moveinsync.evfleet.app.repository;

import com.moveinsync.evfleet.app.domain.CabEventSeq;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CabEventSeqRepository extends JpaRepository<CabEventSeq, CabEventSeq.Key> {
}
