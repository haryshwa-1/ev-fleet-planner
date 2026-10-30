CREATE TABLE tenants (
    id   VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL REFERENCES tenants (id),
    username      VARCHAR(200) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    trip_id       VARCHAR(32)
);

-- A shift's input is generated deterministically, so its parameters are enough to rebuild it.
CREATE TABLE shifts (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL REFERENCES tenants (id),
    cabs             INT         NOT NULL,
    hubs             INT         NOT NULL,
    chargers_per_hub INT         NOT NULL,
    trips            INT         NOT NULL,
    seed             BIGINT      NOT NULL,
    current_version  INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shifts_tenant ON shifts (tenant_id);

-- Every plan ever published. Rows are never updated, so any version can be audited.
CREATE TABLE plan_versions (
    id              BIGSERIAL PRIMARY KEY,
    shift_id        BIGINT           NOT NULL REFERENCES shifts (id),
    version         INT              NOT NULL,
    cause           VARCHAR(200)     NOT NULL,
    state           JSONB            NOT NULL,
    backup_trips    INT              NOT NULL,
    charging_cost   DOUBLE PRECISION NOT NULL,
    lower_bound     INT              NOT NULL,
    planning_millis BIGINT           NOT NULL,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    UNIQUE (shift_id, version)
);

-- The stored response is replayed when a client retries with the same key.
CREATE TABLE processed_events (
    id              BIGSERIAL PRIMARY KEY,
    shift_id        BIGINT       NOT NULL REFERENCES shifts (id),
    idempotency_key VARCHAR(100) NOT NULL,
    response        JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (shift_id, idempotency_key)
);

CREATE TABLE cab_event_seq (
    shift_id BIGINT      NOT NULL REFERENCES shifts (id),
    cab_id   VARCHAR(32) NOT NULL,
    last_seq BIGINT      NOT NULL,
    PRIMARY KEY (shift_id, cab_id)
);
