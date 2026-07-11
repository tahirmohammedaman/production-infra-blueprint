package dev.tahir.blueprint.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.tahir.blueprint.domain.DuplicateItemException;
import dev.tahir.blueprint.domain.ItemNotFoundException;

/**
 * Every error leaves the service as RFC 7807 {@code application/problem+json}.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is load-bearing rather than
 * cosmetic. Spring MVC raises its own exceptions for an unmapped path, a wrong method and
 * an unsupported media type; with only a catch-all {@code @ExceptionHandler(Exception)}
 * those are swallowed and returned as 500, which turns routine client mistakes into
 * pages and poisons the SLO error budget. The base class maps them to their real status
 * codes and the catch-all below is left for genuinely unexpected failures.
 *
 * <p>Internal failures are logged with the correlation id and returned without a stack
 * trace or driver message, so nothing about the schema leaks to callers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://blueprint.tahir.dev/problems/";
    private static final URI TYPE_NOT_FOUND = URI.create(PROBLEM_BASE + "not-found");
    private static final URI TYPE_CONFLICT = URI.create(PROBLEM_BASE + "conflict");
    private static final URI TYPE_VALIDATION = URI.create(PROBLEM_BASE + "validation");
    private static final URI TYPE_INTERNAL = URI.create(PROBLEM_BASE + "internal");

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

    /**
     * Overridden rather than declared as a second {@code @ExceptionHandler}: the base
     * class already maps this type, and two mappings for one exception in the same advice
     * fails context startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "See 'errors'");
        detail.setType(TYPE_VALIDATION);
        detail.setTitle("Request validation failed");
        detail.setInstance(URI.create(request.getDescription(false).replaceFirst("^uri=", "")));

        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        detail.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(detail);
    }

    /**
     * Raised by {@code @Validated} constraints on request parameters (as opposed to
     * request bodies, which surface as {@link MethodArgumentNotValidException}). Without
     * this handler an out-of-range page size falls through to the catch-all and is
     * reported as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        ProblemDetail detail =
                problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Request validation failed", "See 'errors'", request);
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(lastPathSegment(violation), violation.getMessage());
        }
        detail.setProperty("errors", errors);
        return detail;
    }

    private static String lastPathSegment(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
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
