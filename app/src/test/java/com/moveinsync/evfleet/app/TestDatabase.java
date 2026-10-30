package com.moveinsync.evfleet.app;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;

/** Runs tests against a real PostgreSQL started from a library, so no Docker is needed. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

    private static EmbeddedPostgres postgres;

    @Bean
    @Primary
    DataSource dataSource() throws IOException {
        synchronized (TestDatabase.class) {
            if (postgres == null) postgres = EmbeddedPostgres.builder().start();
        }
        return postgres.getPostgresDatabase();
    }
}
