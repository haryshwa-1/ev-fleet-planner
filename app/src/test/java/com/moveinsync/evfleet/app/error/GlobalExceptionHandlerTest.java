package com.moveinsync.evfleet.app.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void aDatabaseThatCannotStartATransactionIsUnavailableNotAnInternalError() {
        ProblemDetail p = new GlobalExceptionHandler().handleDatabase(new CannotCreateTransactionException("no connection"));
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getProperties()).containsEntry("code", "DB_UNAVAILABLE");
        assertThat(p.getDetail()).doesNotContain("no connection");
    }
}
