package com.example.alerts.mock;

import org.springframework.http.HttpStatus;

/** Rendered as Hummingbird's error envelope: {"error":{code,message,param},"trace_id":"..."}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String param;

    public ApiException(HttpStatus status, String code, String message, String param) {
        super(message);
        this.status = status;
        this.code = code;
        this.param = param;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String param() { return param; }
}
