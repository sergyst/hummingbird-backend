package com.example.alerts.service;

import com.example.alerts.client.HummingbirdClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Read-only access to the cases and reviews Hummingbird created from our alerts.
 * Responses are passed through unchanged, so callers see exactly what Hummingbird returns
 * (including its pagination fields).
 */
@Service
public class CaseService {

    private final HummingbirdClient client;

    public CaseService(HummingbirdClient client) {
        this.client = client;
    }

    /** GET /cases. {@code params} are forwarded as query parameters (first, workflow, cursor, ...). */
    public Map<String, Object> listCases(Map<String, String> params) {
        return client.listCases(withDefault(params, "first", "25"));
    }

    public Map<String, Object> getCase(String caseToken) {
        return client.getCase(caseToken);
    }

    /** GET /reviews. {@code params} are forwarded (case_token, page_size, ...). */
    public Map<String, Object> listReviews(Map<String, String> params) {
        return client.listReviews(withDefault(params, "page_size", "25"));
    }

    private static Map<String, String> withDefault(Map<String, String> params, String key, String value) {
        Map<String, String> q = new LinkedHashMap<>(params);
        q.putIfAbsent(key, value);
        return q;
    }
}
