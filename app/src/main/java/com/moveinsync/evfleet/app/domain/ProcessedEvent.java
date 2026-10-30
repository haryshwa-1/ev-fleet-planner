package com.moveinsync.evfleet.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "shift_id")
    private Long shiftId;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    /** What the event said, so the same key on a different event can be refused. */
    private String fingerprint;
    @JdbcTypeCode(SqlTypes.JSON)
    private String response;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(Long shiftId, String idempotencyKey, String fingerprint, String response) {
        this.shiftId = shiftId;
        this.idempotencyKey = idempotencyKey;
        this.fingerprint = fingerprint;
        this.response = response;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getResponse() {
        return response;
    }
}
