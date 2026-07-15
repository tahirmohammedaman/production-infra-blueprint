package dev.tahir.blueprint.platform.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Error handling every service inherits. Services subclass this and add handlers for their
 * own domain exceptions; the framework-level behaviour is identical everywhere so a client
 * talking to two services does not get two different error shapes.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is load-bearing rather than cosmetic.
 * Spring MVC raises its own exceptions for an unmapped path, a wrong method and an
 * unsupported media type; with only a catch-all {@code @ExceptionHandler(Exception.class)}
 * those are swallowed and returned as 500, which turns routine client mistakes into pages
 * and burns SLO error budget. The base class maps them to their real status codes, and the
 * catch-all here is left for genuinely unexpected failures.
 */
public abstract class BaseExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BaseExceptionHandler.class);

    /**
     * Overridden rather than declared as a second {@code @ExceptionHandler}: the base class
     * already maps this type, and two mappings for one exception in the same advice fails
     * context startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "See 'errors'");
        detail.setType(ProblemTypes.VALIDATION);
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
     * Raised by {@code @Validated} constraints on request parameters, as opposed to request
     * bodies which surface as {@link MethodArgumentNotValidException}. Without this handler
     * an out-of-range page size falls through to the catch-all and is reported as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "Request validation failed", "See 'errors'", request);
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(lastPathSegment(violation), violation.getMessage());
        }
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception path={} method={}", request.getRequestURI(), request.getMethod(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL,
                "Internal server error",
                "The request could not be completed",
                request);
    }

    /**
     * Builds a problem document. Detail text is written for the caller: it never carries a
     * stack trace, a driver message or a column name, because those describe the schema to
     * anyone who can send a malformed request.
     */
    protected ProblemDetail problem(
            HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private static String lastPathSegment(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }
}
