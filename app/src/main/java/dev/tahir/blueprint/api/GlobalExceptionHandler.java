package dev.tahir.blueprint.api;

import dev.tahir.blueprint.domain.DuplicateItemException;
import dev.tahir.blueprint.domain.ItemNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error leaves the service as RFC 7807 {@code application/problem+json}.
 * Internal failures are logged with the correlation id and returned without a stack
 * trace or driver message, so nothing about the schema leaks to callers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI TYPE_NOT_FOUND = URI.create("https://blueprint.tahir.dev/problems/not-found");
    private static final URI TYPE_CONFLICT = URI.create("https://blueprint.tahir.dev/problems/conflict");
    private static final URI TYPE_VALIDATION = URI.create("https://blueprint.tahir.dev/problems/validation");
    private static final URI TYPE_INTERNAL = URI.create("https://blueprint.tahir.dev/problems/internal");

    @ExceptionHandler(ItemNotFoundException.class)
    public ProblemDetail handleNotFound(ItemNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Item not found", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateItemException.class)
    public ProblemDetail handleDuplicate(DuplicateItemException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Duplicate item", ex.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                TYPE_CONFLICT,
                "Concurrent modification",
                "The item was modified by another request; retry with a fresh read",
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Request validation failed", "See 'errors'", request);
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception path={} method={}", request.getRequestURI(), request.getMethod(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                TYPE_INTERNAL,
                "Internal server error",
                "The request could not be completed",
                request);
    }

    private ProblemDetail problem(HttpStatus status, URI type, String title, String detail, HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(req.getRequestURI()));
        return problem;
    }
}
