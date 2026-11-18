package com.example.alerts.client.model;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GET /reviews: {@code {reviews:[...], next_page_token}}; pass the token as {@code page_token} for the next page. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewList(List<Review> reviews, @JsonProperty("next_page_token") String nextPageToken) {

    public ReviewList {
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(
            String token,
            @JsonProperty("case_token") String caseToken,
            String status,
            Stage stage,
            Queue queue,
            Assignee assignee,
            Cancellation cancellation,
            @JsonProperty("type_canonical_id") String typeCanonicalId,
            @JsonProperty("internal_control_number") String internalControlNumber,
            @JsonProperty("reference_id") String referenceId,
            @JsonProperty("review_due_at") Instant reviewDueAt,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("completed_at") Instant completedAt) {

        /** The status as an enum; null when absent or not one Hummingbird documents. */
        public ReviewStatus reviewStatus() {
            return ReviewStatus.fromApi(status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stage(String title, String token) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Queue(@JsonProperty("canonical_id") String canonicalId, String name, String token) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assignee(String token, String email, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancellation(@JsonProperty("cancelled_at") Instant cancelledAt, String reason) {
    }
}
