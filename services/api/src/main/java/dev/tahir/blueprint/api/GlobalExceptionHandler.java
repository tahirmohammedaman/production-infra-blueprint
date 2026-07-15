package dev.tahir.blueprint.api;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    public ProblemDetail handleCacheUnavailable(RedisConnectionFailureException ex, HttpServletRequest request) {
        ProblemDetail detail = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.DEPENDENCY_UNAVAILABLE,
                "Cache unavailable",
                "A dependency is temporarily unavailable; retry shortly",
                request);
        detail.setProperty("retryAfterSeconds", 5);
        return detail;
    }
}
