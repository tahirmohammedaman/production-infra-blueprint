package dev.tahir.blueprint.api;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.tahir.blueprint.domain.DuplicateItemException;
import dev.tahir.blueprint.domain.ItemNotFoundException;
import dev.tahir.blueprint.platform.web.BaseExceptionHandler;
import dev.tahir.blueprint.platform.web.ProblemTypes;

/**
 * Domain-specific error mapping. Everything framework-level — unmapped paths, wrong
 * methods, unsupported media types, bean validation — is inherited from
 * {@link BaseExceptionHandler} so the API and the worker report those identically.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** How long a client is asked to wait before retrying a request refused for want of a dependency. */
    static final int RETRY_AFTER_SECONDS = 5;

    @ExceptionHandler(ItemNotFoundException.class)
    public ProblemDetail handleNotFound(ItemNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Item not found", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateItemException.class)
    public ProblemDetail handleDuplicate(DuplicateItemException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Duplicate item", ex.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                ProblemTypes.CONFLICT,
                "Concurrent modification",
                "The item was modified by another request; retry with a fresh read",
                request);
    }

    /**
     * Redis is a cache, not a system of record, so a Redis outage must degrade the read path
     * rather than fail it. This handler exists for the case where the failure escapes the
     * cache-aside fallback anyway — it returns 503 with a Retry-After rather than a 500, so
     * clients back off instead of hammering a service that is already struggling.
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ProblemDetail> handleCacheUnavailable(
            RedisConnectionFailureException ex, HttpServletRequest request) {
        return unavailable("Cache unavailable", request);
    }

    /**
     * No database connection could be had within the pool's connection timeout: every pooled
     * connection was busy, or the database could not be reached. The request did no work, and the
     * same request a moment later — quite possibly on another replica — will most likely succeed.
     *
     * <p>503 with Retry-After says exactly that. A 500 says the code is broken: to a client,
     * which then gives up or retries at once, and to whoever reads the error panel, who goes
     * looking for a bug that does not exist. Found by the zero-downtime drill on a saturated
     * node, where new pods warming up during a rollout took CPU from the pods still serving and
     * their pools ran dry.
     *
     * <p>It still counts against the availability objective, which counts every 5xx at the
     * gateway. The status changes what the client does next, not whether a user was affected.
     */
    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<ProblemDetail> handleNoDatabaseConnection(
            CannotCreateTransactionException ex, HttpServletRequest request) {
        // The pool's own message carries its occupancy — total, active, idle, waiting — which is
        // what tells overload from an unreachable database. It goes to the log, not the client.
        log.warn(
                "no database connection available path={} method={} cause={}",
                request.getRequestURI(),
                request.getMethod(),
                NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        return unavailable("Database unavailable", request);
    }

    /**
     * Retry-After as a header, which is what HTTP clients and proxies read, and in the body for
     * callers that only look there.
     */
    private ResponseEntity<ProblemDetail> unavailable(String title, HttpServletRequest request) {
        ProblemDetail detail = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.DEPENDENCY_UNAVAILABLE,
                title,
                "A dependency is temporarily unavailable; retry shortly",
                request);
        detail.setProperty("retryAfterSeconds", RETRY_AFTER_SECONDS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                .body(detail);
    }
}
