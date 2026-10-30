package com.moveinsync.evfleet.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private String tenantId;
    private int cabs;
    private int hubs;
    @Column(name = "chargers_per_hub")
    private int chargersPerHub;
    private int trips;
    private long seed;
    @Column(name = "current_version")
    private int currentVersion;

    protected Shift() {
    }

    public Shift(String tenantId, int cabs, int hubs, int chargersPerHub, int trips, long seed) {
        this.tenantId = tenantId;
        this.cabs = cabs;
        this.hubs = hubs;
        this.chargersPerHub = chargersPerHub;
        this.trips = trips;
        this.seed = seed;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getCabs() {
        return cabs;
    }

    public int getHubs() {
        return hubs;
    }

    public int getChargersPerHub() {
        return chargersPerHub;
    }

    public int getTrips() {
        return trips;
    }

    public long getSeed() {
        return seed;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }
}
