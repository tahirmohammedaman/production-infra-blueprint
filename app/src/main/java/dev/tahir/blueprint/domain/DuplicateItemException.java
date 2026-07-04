package dev.tahir.blueprint.domain;

public class DuplicateItemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateItemException(String name) {
        super("An item named '%s' already exists".formatted(name));
    }
}
