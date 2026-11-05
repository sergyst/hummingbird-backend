package com.example.alerts.web;

import com.example.alerts.client.HummingbirdApiException;
import com.example.alerts.service.AlertService.DuplicateAlertException;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HummingbirdApiException.class)
    public ProblemDetail hummingbird(HummingbirdApiException e) {
        // 4xx from Hummingbird (bad payload etc.) -> 422 for the UI; 5xx/unreachable -> 502; not configured -> 503
        HttpStatus status = switch (e.getStatus()) {
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 401, 403 -> HttpStatus.BAD_GATEWAY; // our credentials were rejected: not the browser user's problem
            default -> e.getStatus() >= 400 && e.getStatus() < 500 ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setTitle("Hummingbird: " + e.getCode());
        pd.setProperty("code", e.getCode());
        pd.setProperty("upstreamStatus", e.getStatus());
        if (e.getTraceId() != null) {
            pd.setProperty("traceId", e.getTraceId());
        }
        return pd;
    }

    @ExceptionHandler(DuplicateAlertException.class)
    public ProblemDetail duplicate(DuplicateAlertException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Duplicate alert id");
        return pd;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation failed");
        return pd;
    }
}
