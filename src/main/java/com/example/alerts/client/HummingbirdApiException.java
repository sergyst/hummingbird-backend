package com.example.alerts.client;

/** Error returned by (or while calling) the Hummingbird API. */
public class HummingbirdApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String traceId;

    public HummingbirdApiException(int status, String code, String message, String traceId) {
        super(message);
        this.status = status;
        this.code = code;
        this.traceId = traceId;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** Hummingbird "trace_id" - quote it to their support when something looks wrong. */
    public String getTraceId() {
        return traceId;
    }
}
