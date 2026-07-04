package dev.tahir.blueprint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;

/**
 * Inventory item. Identifiers are client-independent UUIDs so that writes never
 * depend on a database sequence round-trip, and {@code version} gives us optimistic
 * locking instead of row locks under concurrent updates.
 */
@Entity
@Table(name = "items")
@EntityListeners(AuditingEntityListener.class)
public class Item {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private int quantity;

    @Version
    @Column(nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Item() {
        // required by JPA
    }

    public Item(UUID id, String name, String description, int quantity) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.quantity = quantity;
    }

    public static Item create(String name, String description, int quantity) {
        return new Item(UUID.randomUUID(), name, description, quantity);
    }

    public void update(String name, String description, int quantity) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Item item)) {
            return false;
        }
        return id != null && id.equals(item.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
