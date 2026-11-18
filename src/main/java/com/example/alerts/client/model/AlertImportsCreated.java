package com.example.alerts.client.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** POST /alert_imports (201): {@code {success, alerts:[{token, id}]}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertImportsCreated(Boolean success, List<ImportedAlert> alerts) {

    public AlertImportsCreated {
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
    }

    /** The processing token Hummingbird assigned to the alert we sent under {@code alertId}, or null. */
    public String processingTokenFor(String alertId) {
        return alerts.stream()
                .filter(a -> alertId.equals(a.id()) && a.token() != null)
                .map(ImportedAlert::token)
                .findFirst()
                .orElse(null);
    }
}
