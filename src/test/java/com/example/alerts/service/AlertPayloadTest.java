package com.example.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.alerts.web.Dtos.CryptoAddressInput;
import com.example.alerts.web.Dtos.OtherInfoInput;
import com.example.alerts.web.Dtos.SubmitAlertRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertPayloadTest {

    private final AlertService service = new AlertService(null, null, null);
    private final Instant at = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void buildsWhitelistAlertWithWorkflowCryptoAddressAndOtherInfo() {
        SubmitAlertRequest req = new SubmitAlertRequest(
                "WL-ADD-20261110-0001", "Whitelist: address added", "Whitelist change requested", at,
                List.of("whitelist", "address-added"), null, null, null, null, null,
                "triage",
                List.of(new CryptoAddressInput("ADDR-0x1234", "0x1234567890abcdef1234567890abcdef12345678", "ethereum")),
                List.of(new OtherInfoInput("whitelist_action", "ADDED")));

        Map<String, Object> payload = service.buildPayload(req, List.of(), "WL-ADD-20261110-0001", at);

        assertEquals("triage", payload.get("workflow"));
        Map<String, Object> alert = ((List<Map<String, Object>>) payload.get("alerts")).get(0);
        assertEquals(List.of(Map.of("label", "whitelist_action", "value", "ADDED")), alert.get("other_info"));
        assertEquals(List.of(Map.of("type", "crypto_address", "id", "ADDR-0x1234")), alert.get("references"));
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertEquals(List.of(Map.of(
                "id", "ADDR-0x1234",
                "address", "0x1234567890abcdef1234567890abcdef12345678",
                "blockchain", "ETHEREUM")), data.get("crypto_addresses"));
    }

    @Test
    void omitsOptionalSectionsWhenNotGiven() {
        SubmitAlertRequest req = new SubmitAlertRequest(
                "A1", "rule", null, at, null, null, null, null, null, null, " ", null, null);

        Map<String, Object> payload = service.buildPayload(req, List.of(), "A1", at);

        assertFalse(payload.containsKey("workflow"));
        assertFalse(payload.containsKey("data"));
    }
}
