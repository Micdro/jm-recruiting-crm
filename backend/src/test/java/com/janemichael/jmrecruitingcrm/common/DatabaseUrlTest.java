package com.janemichael.jmrecruitingcrm.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlTest {

    @Test
    void translatesAHostingPlatformUrl() {
        Map<String, String> properties = DatabaseUrl.toSpringProperties(Map.of(
                "DATABASE_URL", "postgresql://jmcrm:s%40cret@dpg-abc123-a.virginia-postgres.render.com:6543/jmcrm?sslmode=require"));

        assertThat(properties).containsExactlyInAnyOrderEntriesOf(Map.of(
                "spring.datasource.url", "jdbc:postgresql://dpg-abc123-a.virginia-postgres.render.com:6543/jmcrm?sslmode=require",
                "spring.datasource.username", "jmcrm",
                "spring.datasource.password", "s@cret"));
    }

    @Test
    void defaultsThePortForInternalHostnames() {
        Map<String, String> properties = DatabaseUrl.toSpringProperties(Map.of(
                "DATABASE_URL", "postgres://user:pass@dpg-abc123-a/jmcrm"));

        assertThat(properties).containsEntry("spring.datasource.url", "jdbc:postgresql://dpg-abc123-a:5432/jmcrm");
    }

    @Test
    void addsTheRequestedSslModeUnlessTheUrlHasOne() {
        assertThat(DatabaseUrl.toSpringProperties(Map.of(
                "DATABASE_URL", "postgres://user:pass@db.example.com/jmcrm",
                "DB_SSLMODE", "verify-full")))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/jmcrm?sslmode=verify-full");

        assertThat(DatabaseUrl.toSpringProperties(Map.of(
                "DATABASE_URL", "postgres://user:pass@db.example.com/jmcrm?sslmode=require",
                "DB_SSLMODE", "verify-full")))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/jmcrm?sslmode=require");

        assertThat(DatabaseUrl.withSslMode("connectTimeout=10", "require")).isEqualTo("connectTimeout=10&sslmode=require");
        assertThat(DatabaseUrl.withSslMode(null, "")).isNull();
    }

    @Test
    void explicitSpringSettingsWin() {
        assertThat(DatabaseUrl.toSpringProperties(Map.of(
                "DATABASE_URL", "postgres://user:pass@host/db",
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/jmcrm"))).isEmpty();

        assertThat(DatabaseUrl.toSpringProperties(Map.of(
                "DATABASE_URL", "postgres://user:pass@host/db",
                "SPRING_DATASOURCE_PASSWORD", "other")))
                .doesNotContainKey("spring.datasource.password")
                .containsEntry("spring.datasource.username", "user");
    }

    @Test
    void ignoresMissingOrOtherDatabases() {
        assertThat(DatabaseUrl.toSpringProperties(Map.of())).isEmpty();
        assertThat(DatabaseUrl.toSpringProperties(Map.of("DATABASE_URL", "mysql://user:pass@host/db"))).isEmpty();
    }
}
