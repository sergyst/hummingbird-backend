package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
/** Applies only to the embedded mock's own controllers; the backend's handler covers everything else. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {HummingbirdController.class, ControlController.class})
public class MockExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status());
        if (e.status().value() == 429) b.header("Retry-After", "2");
        return b.body(envelope(e.status(), e.code(), e.getMessage(), e.param()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(envelope(HttpStatus.BAD_REQUEST, "invalid_json", "Request body is not valid JSON", null));
    }

    /**
     * Documented error body {@code {success:false, error:{type, message}}}, plus the {@code code}/{@code param}/
     * {@code trace_id} fields older clients of this mock read.
     */
    public static Map<String, Object> envelope(HttpStatus status, String code, String message, String param) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "AUTH_ERROR";
            case 403 -> "UNAUTHORIZED_ACCESS";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "INVALID_REQUEST";
            case 504 -> "TIMEOUT";
            default -> "GENERIC";
        });
        err.put("code", code);
        err.put("message", message);
        err.put("param", param);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", err);
        body.put("trace_id", UUID.randomUUID().toString());
        return body;
    }
}
