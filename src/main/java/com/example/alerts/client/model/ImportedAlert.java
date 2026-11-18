package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of the POST /alert_imports response. {@code token} is the processing token; {@code id} is our alert id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportedAlert(String token, String id) {
}
