package com.moveinsync.evfleet.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One published plan; never changed after it is written. */
@Entity
@Table(name = "plan_versions")
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "shift_id")
    private Long shiftId;
    private int version;
    private String cause;
    @JdbcTypeCode(SqlTypes.JSON)
    private String state;
    @Column(name = "backup_trips")
    private int backupTrips;
    @Column(name = "charging_cost")
    private double chargingCost;
    @Column(name = "lower_bound")
    private int lowerBound;
    @Column(name = "planning_millis")
    private long planningMillis;

    protected PlanVersion() {
    }

    public PlanVersion(Long shiftId, int version, String cause, String state, int backupTrips, double chargingCost,
                       int lowerBound, long planningMillis) {
        this.shiftId = shiftId;
        this.version = version;
        this.cause = cause;
        this.state = state;
        this.backupTrips = backupTrips;
        this.chargingCost = chargingCost;
        this.lowerBound = lowerBound;
        this.planningMillis = planningMillis;
    }

    public int getVersion() {
        return version;
    }

    public String getCause() {
        return cause;
    }

    public String getState() {
        return state;
    }

    public int getBackupTrips() {
        return backupTrips;
    }

    public double getChargingCost() {
        return chargingCost;
    }

    public int getLowerBound() {
        return lowerBound;
    }

    public long getPlanningMillis() {
        return planningMillis;
    }
}
