-- Baseline schema.
--
-- Every migration in this directory must be safe to apply while the previous
-- application version is still serving traffic (expand/contract). Additive changes
-- only; destructive changes ship one release after the code that stopped using the
-- column. See docs/runbooks/zero-downtime-migration.md.

CREATE TABLE items (
    id          UUID         NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    quantity    INTEGER      NOT NULL DEFAULT 0,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_items PRIMARY KEY (id),
    CONSTRAINT ck_items_quantity_non_negative CHECK (quantity >= 0)
);

-- Enforces the uniqueness the service checks in ItemService.create. The application
-- check is a friendly 409; this index is the actual guarantee under concurrency.
CREATE UNIQUE INDEX ux_items_name_lower ON items (lower(name));

-- Supports the default listing, which is ordered by created_at DESC.
CREATE INDEX ix_items_created_at_desc ON items (created_at DESC);
