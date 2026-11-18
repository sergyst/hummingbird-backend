package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Bearer-token, apiVersion and (optional) rate-limit checks for the data endpoints. */
@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
@Component
public class ApiInterceptor implements HandlerInterceptor {

    private final MockProperties props;
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();

    public ApiInterceptor(MockProperties props) {
        this.props = props;
    }

    void register(String token, Instant expiry) {
        tokens.put(token, expiry);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing bearer token");
        }
        Instant expiry = tokens.get(auth.substring(7).trim());
        if (expiry == null || expiry.isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid or expired access token");
        }
        String version = req.getParameter("apiVersion");
        if (version == null || version.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_parameter", "apiVersion is required", "apiVersion");
        }
        if (!version.equals(props.apiVersion())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_api_version",
                    "Unsupported apiVersion " + version + " (mock supports " + props.apiVersion() + ")", "apiVersion");
        }
        int every = props.rateLimitEvery();
        if (every > 0 && requests.incrementAndGet() % every == 0) {
            res.setHeader("Retry-After", "2");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests");
        }
        return true;
    }
}
