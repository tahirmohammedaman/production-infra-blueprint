package dev.tahir.blueprint.outbox;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of unpublished events for this relay instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes it safe to run the relay in every API
     * replica: each instance takes rows nobody else holds instead of blocking on them, so
     * throughput scales with replicas and no event is published twice concurrently. Without
     * {@code SKIP LOCKED} the replicas serialise behind one another and the relay becomes a
     * bottleneck that gets slower as you scale out.
     *
     * <p>Ordered by {@code created_at} so events for one aggregate keep their causal order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null and e.attempts < :maxAttempts"
            + " order by e.createdAt asc")
    List<OutboxEvent> claimUnpublished(@Param("maxAttempts") int maxAttempts, Limit limit);

    long countByPublishedAtIsNull();

    @Query("select count(e) from OutboxEvent e where e.publishedAt is null and e.attempts >= :maxAttempts")
    long countStuck(@Param("maxAttempts") int maxAttempts);
}
