package com.example.alerts.client.model;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The case alert behind GET /alerts/{token} (reached through POST /alerts/id_lookup): outcome + lifecycle. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseAlert(
        String token,
        String id,
        String outcome,
        @JsonProperty("group_id") Long groupId,
        String rule,
        String description,
        String comments,
        @JsonProperty("review_tags") List<String> reviewTags,
        @JsonProperty("other_info") List<OtherInfo> otherInfo,
        @JsonProperty("lifecycle_events") List<LifecycleEvent> lifecycleEvents) {

    public CaseAlert {
        reviewTags = reviewTags == null ? List.of() : List.copyOf(reviewTags);
        otherInfo = otherInfo == null ? List.of() : List.copyOf(otherInfo);
        lifecycleEvents = lifecycleEvents == null ? List.of() : List.copyOf(lifecycleEvents);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OtherInfo(String label, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifecycleEvent(
            Instant timestamp,
            String event,
            @JsonProperty("case_token") String caseToken,
            @JsonProperty("review_token") String reviewToken,
            @JsonProperty("review_type_name") String reviewTypeName,
            Actor actor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(@JsonProperty("account_token") String accountToken, @JsonProperty("full_name") String fullName) {
    }
}
