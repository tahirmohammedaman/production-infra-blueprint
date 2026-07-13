package dev.tahir.blueprint.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
