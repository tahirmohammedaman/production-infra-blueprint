package dev.tahir.blueprint.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    boolean existsByNameIgnoreCase(String name);

    /**
     * Aggregate computed by the database rather than by loading every row into the heap.
     * COALESCE because SUM over an empty table returns NULL, which would unbox to a
     * NullPointerException on the first request against a fresh deployment.
     */
    @Query("select coalesce(sum(i.quantity), 0) from Item i")
    long sumQuantity();
}
