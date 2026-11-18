package com.example.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.alerts.client.model.AlertImportRequest;
import com.example.alerts.web.Dtos.CryptoAddressInput;
import com.example.alerts.web.Dtos.OtherInfoInput;
import com.example.alerts.web.Dtos.SubmitAlertRequest;
import com.example.alerts.web.Dtos.TransactionInput;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlertPayloadTest {

    private final AlertService service = new AlertService(null, null, null);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Instant at = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void buildsWhitelistAlertWithWorkflowCryptoAddressAndOtherInfo() {
        SubmitAlertRequest req = new SubmitAlertRequest(
                "WL-ADD-20261110-0001", "Whitelist: address added", "Whitelist change requested", at,
                List.of("whitelist", "address-added"), null, null, null, null, null,
                "triage",
                List.of(new CryptoAddressInput("ADDR-0x1234", "0x1234567890abcdef1234567890abcdef12345678", "ethereum")),
                List.of(new OtherInfoInput("whitelist_action", "ADDED")));

        AlertImportRequest payload = service.buildPayload(req, List.of(), "WL-ADD-20261110-0001", at);

        assertEquals("triage", payload.workflow());
        AlertImportRequest.Alert alert = payload.alerts().get(0);
        assertEquals(List.of(new AlertImportRequest.OtherInfo("whitelist_action", "ADDED")), alert.otherInfo());
        assertEquals(List.of(new AlertImportRequest.Reference("crypto_address", "ADDR-0x1234")), alert.references());
        assertEquals(List.of(new AlertImportRequest.CryptoAddress("ADDR-0x1234",
                "0x1234567890abcdef1234567890abcdef12345678", "ETHEREUM")), payload.data().cryptoAddresses());
    }

    @Test
    void omitsOptionalSectionsWhenNotGiven() {
        SubmitAlertRequest req = new SubmitAlertRequest(
                "A1", "rule", null, at, null, null, null, null, null, null, " ", null, null);

        AlertImportRequest payload = service.buildPayload(req, List.of(), "A1", at);

        assertNull(payload.workflow());
        assertNull(payload.data());
        JsonNode body = json.valueToTree(payload);
        assertFalse(body.has("workflow"));
        assertFalse(body.has("data"));
        JsonNode alert = body.get("alerts").get(0);
        for (String absent : new String[] {"comments", "review_tags", "other_info", "references"}) {
            assertFalse(alert.has(absent), absent + " should be omitted");
        }
    }

    @Test
    void serialisesToTheDocumentedSnakeCaseJson() {
        SubmitAlertRequest req = new SubmitAlertRequest(
                "A2", " Structuring ", " note ", at, List.of(" t1 ", " "), "Jane Doe", null, null, null, null,
                null, null, null);
        List<TransactionInput> txs = List.of(new TransactionInput(12_345L, "eur", "credit", "wire", null));

        JsonNode body = json.valueToTree(service.buildPayload(req, txs, "A2", at));

        JsonNode alert = body.get("alerts").get(0);
        assertEquals("A2", alert.get("id").asText());
        assertEquals("2026-11-10T12:00:00Z", alert.get("triggered_at").asText());
        assertEquals("Structuring", alert.get("rule").asText());
        assertEquals("note", alert.get("comments").asText());
        assertEquals(1, alert.get("review_tags").size());
        assertEquals("t1", alert.get("review_tags").get(0).asText());
        assertEquals("individual", alert.get("references").get(0).get("type").asText());
        assertEquals("transaction", alert.get("references").get(1).get("type").asText());

        JsonNode data = body.get("data");
        assertEquals("Jane Doe", data.get("individuals").get(0).get("names").get(0).get("name").asText());
        JsonNode tx = data.get("transactions").get(0);
        assertEquals("TX-A2-1", tx.get("id").asText());
        assertEquals(12345, tx.get("amount").get("amount").asLong());
        assertEquals("EUR", tx.get("amount").get("currency_code").asText());
        assertEquals(tx.get("amount"), tx.get("amount_local"));
        assertEquals("CREDIT", tx.get("direction").asText());
        assertEquals("wire", tx.get("description").asText());
        assertEquals("2026-11-10T12:00:00Z", tx.get("initiated_at").asText());
        assertFalse(data.has("crypto_addresses"));
    }
}
