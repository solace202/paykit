package dev.paykit.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.stripe.exception.StripeException;
import dev.paykit.webhook.InvalidSignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 9457 problem responses. */
@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String TYPE_BASE = "https://paykit.dev/problems/";

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidSignatureException.class)
    ProblemDetail signature(InvalidSignatureException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-signature", "Invalid webhook signature", e.getMessage());
    }

    @ExceptionHandler(StripeException.class)
    ProblemDetail stripe(StripeException e) {
        log.warn("Stripe API error: {} (request {})", e.getMessage(), e.getRequestId());
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe API error", e.getUserMessage() != null
                ? e.getUserMessage() : e.getMessage());
        if (e.getRequestId() != null) {
            pd.setProperty("stripeRequestId", e.getRequestId());
        }
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed",
                "One or more fields are invalid.");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", e.getMessage());
    }

    static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + type));
        pd.setTitle(title);
        return pd;
    }
}
