package com.moveinsync.evfleet.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

/** The highest event sequence number seen per cab, to drop out-of-order events. */
@Entity
@Table(name = "cab_event_seq")
@IdClass(CabEventSeq.Key.class)
public class CabEventSeq {

    public record Key(Long shiftId, String cabId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "shift_id")
    private Long shiftId;
    @Id
    @Column(name = "cab_id")
    private String cabId;
    @Column(name = "last_seq")
    private long lastSeq;

    protected CabEventSeq() {
    }

    public CabEventSeq(Long shiftId, String cabId, long lastSeq) {
        this.shiftId = shiftId;
        this.cabId = cabId;
        this.lastSeq = lastSeq;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }
}
