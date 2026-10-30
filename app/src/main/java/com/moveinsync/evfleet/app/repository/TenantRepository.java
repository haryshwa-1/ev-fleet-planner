package com.moveinsync.evfleet.app.repository;

import com.moveinsync.evfleet.app.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
}
