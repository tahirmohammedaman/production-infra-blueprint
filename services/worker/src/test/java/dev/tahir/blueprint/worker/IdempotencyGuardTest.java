package dev.tahir.blueprint.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.tahir.blueprint.worker.processing.IdempotencyGuard;

class IdempotencyGuardTest extends WorkerIntegrationTestBase {

    @Autowired
    private IdempotencyGuard guard;

    @Test
    @DisplayName("the first claim wins and every redelivery is rejected")
    void claimIsExactlyOnce() {
        UUID eventId = UUID.randomUUID();

        assertThat(guard.claim(eventId)).isTrue();
        assertThat(guard.claim(eventId)).isFalse();
        assertThat(guard.claim(eventId)).isFalse();
    }

    @Test
    @DisplayName("releasing a claim allows the event to be retried")
    void releaseAllowsRetry() {
        UUID eventId = UUID.randomUUID();

        assertThat(guard.claim(eventId)).isTrue();
        // Without release, a handler that threw after claiming would have its retry rejected
        // as a duplicate and the event would be lost. This is the difference between
        // at-least-once and at-most-once.
        guard.release(eventId);

        assertThat(guard.claim(eventId)).isTrue();
    }

    @Test
    @DisplayName("distinct events do not interfere")
    void claimsAreScopedToTheEventId() {
        assertThat(guard.claim(UUID.randomUUID())).isTrue();
        assertThat(guard.claim(UUID.randomUUID())).isTrue();
    }
}
