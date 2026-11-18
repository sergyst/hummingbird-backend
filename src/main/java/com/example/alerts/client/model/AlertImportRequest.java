package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * POST /alert_imports request body. Only the parts this app sends are modelled; empty or null sections are left
 * out of the JSON. Timestamps are ISO-8601 strings, money is in minor units.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AlertImportRequest(List<Alert> alerts, String workflow, Data data) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Alert(String id,
                        @JsonProperty("triggered_at") String triggeredAt,
                        String rule,
                        String comments,
                        @JsonProperty("review_tags") List<String> reviewTags,
                        @JsonProperty("other_info") List<OtherInfo> otherInfo,
                        List<Reference> references) {
    }

    public record OtherInfo(String label, String value) {
    }

    /** Points an alert at an entry in {@link Data}; {@code type} is crypto_address, individual or transaction. */
    public record Reference(String type, String id) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Data(@JsonProperty("crypto_addresses") List<CryptoAddress> cryptoAddresses,
                       List<Individual> individuals,
                       List<Transaction> transactions) {

        /** True when there is nothing to send. */
        public boolean isBlank() {
            return (cryptoAddresses == null || cryptoAddresses.isEmpty())
                    && (individuals == null || individuals.isEmpty())
                    && (transactions == null || transactions.isEmpty());
        }
    }

    public record CryptoAddress(String id, String address, String blockchain) {
    }

    public record Individual(String id, List<Name> names) {
    }

    public record Name(String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Transaction(String id,
                              Money amount,
                              @JsonProperty("amount_local") Money amountLocal,
                              @JsonProperty("initiated_at") String initiatedAt,
                              String direction,
                              String description) {
    }

    public record Money(long amount, @JsonProperty("currency_code") String currencyCode) {
    }
}
