package com.example.alerts.client.model;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /cases/{token}. Only the fields this service reads are modelled; the reviews carry their workflow, stage
 * (a plain string here, unlike GET /reviews where it is an object) and status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseDetailResponse(Boolean success, @JsonProperty("case") Case kase) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(
            String token,
            String id,
            String name,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            List<Review> reviews) {

        public Case {
            reviews = reviews == null ? List.of() : List.copyOf(reviews);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(String token, String workflow, String stage, String status) {

        /** The status as an enum; null when absent or not one Hummingbird documents. */
        public ReviewStatus reviewStatus() {
            return ReviewStatus.fromApi(status);
        }
    }
}
