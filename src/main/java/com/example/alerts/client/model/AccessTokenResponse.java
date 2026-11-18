package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** POST /oauth/token (client credentials). {@code expiresIn} is in seconds. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn) {
}
