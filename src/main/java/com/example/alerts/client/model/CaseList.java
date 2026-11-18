package com.example.alerts.client.model;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GET /cases: {@code {success, cases:[{cursor, case:{token, created_at, updated_at}}], page_info}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseList(Boolean success, List<Item> cases, @JsonProperty("page_info") PageInfo pageInfo) {

    public CaseList {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String cursor, @JsonProperty("case") Ref kase) {
    }

    /** The list carries only the identity and timestamps of a case; use GET /cases/{token} for the rest. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(
            String token,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {
    }
}
