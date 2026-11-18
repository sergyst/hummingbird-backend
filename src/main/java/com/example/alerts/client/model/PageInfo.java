package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Cursor paging of GET /cases: pass {@code endCursor} as {@code after} to get the next page. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageInfo(
        @JsonProperty("has_next_page") Boolean hasNextPage,
        @JsonProperty("has_previous_page") Boolean hasPreviousPage,
        @JsonProperty("start_cursor") String startCursor,
        @JsonProperty("end_cursor") String endCursor) {

    public boolean hasNext() {
        return Boolean.TRUE.equals(hasNextPage);
    }
}
