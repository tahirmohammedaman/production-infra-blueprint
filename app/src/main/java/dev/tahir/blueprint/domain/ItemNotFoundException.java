package dev.tahir.blueprint.domain;

import java.util.UUID;

public class ItemNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID id;

    public ItemNotFoundException(UUID id) {
        super("Item %s does not exist".formatted(id));
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
