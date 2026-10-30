package com.moveinsync.evfleet.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private String tenantId;
    private String username;
    @Column(name = "password_hash")
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    private Role role;
    @Column(name = "trip_id")
    private String tripId;

    protected AppUser() {
    }

    public AppUser(String tenantId, String username, String passwordHash, Role role, String tripId) {
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.tripId = tripId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getTripId() {
        return tripId;
    }
}
