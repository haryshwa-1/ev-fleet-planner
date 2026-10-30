package com.moveinsync.evfleet.app.service;

import com.moveinsync.evfleet.app.domain.AppUser;
import com.moveinsync.evfleet.app.domain.Role;
import com.moveinsync.evfleet.app.domain.Shift;
import com.moveinsync.evfleet.app.domain.Tenant;
import com.moveinsync.evfleet.app.repository.TenantRepository;
import com.moveinsync.evfleet.app.repository.UserRepository;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Fills an empty database with two demo tenants: the 300-cab shift, and a small one to test isolation. */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ShiftService shifts;
    private final PasswordEncoder passwords;
    private final String password;

    public DemoSeeder(TenantRepository tenants, UserRepository users, ShiftService shifts, PasswordEncoder passwords,
                      @Value("${app.seed.password}") String password) {
        this.tenants = tenants;
        this.users = users;
        this.shifts = shifts;
        this.passwords = passwords;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenants.count() > 0) return;
        tenants.save(new Tenant("acme", "Acme Mobility"));
        tenants.save(new Tenant("globex", "Globex Transport"));
        String hash = passwords.encode(password);
        users.save(new AppUser("acme", "admin@acme", hash, Role.ADMIN, null));
        users.save(new AppUser("acme", "operator@acme", hash, Role.OPERATOR, null));
        users.save(new AppUser("acme", "employee@acme", hash, Role.EMPLOYEE, "T-0001"));
        users.save(new AppUser("globex", "admin@globex", hash, Role.ADMIN, null));

        Shift acme = shifts.create("acme", ScenarioGenerator.Params.standard());
        shifts.plan("acme", acme.getId());
        Shift globex = shifts.create("globex", new ScenarioGenerator.Params(30, 2, 2, 100, 7L));
        shifts.plan("globex", globex.getId());
        log.info("demo data ready: acme shift {}, globex shift {}", acme.getId(), globex.getId());
    }
}
