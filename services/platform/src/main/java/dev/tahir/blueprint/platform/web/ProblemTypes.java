package dev.tahir.blueprint.platform.web;

import java.net.URI;

/** Stable problem type URIs. They are part of the API contract, so they live in one place. */
public final class ProblemTypes {

    private static final String BASE = "https://blueprint.tahir.dev/problems/";

    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI VALIDATION = URI.create(BASE + "validation");
    public static final URI INTERNAL = URI.create(BASE + "internal");
    public static final URI DEPENDENCY_UNAVAILABLE = URI.create(BASE + "dependency-unavailable");

    private ProblemTypes() {}
}
