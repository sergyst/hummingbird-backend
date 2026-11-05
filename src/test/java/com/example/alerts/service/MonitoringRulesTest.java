package com.example.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.alerts.web.Dtos.TxView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private static TxView tx(long amountMinor, String direction, String channel, long hoursAgo) {
        return new TxView("T", "t", amountMinor, "USD", direction, channel, NOW.minus(Duration.ofHours(hoursAgo)));
    }

    @Test
    void normalActivityFiresNothing() {
        var txs = List.of(tx(2_500_00, "CREDIT", "WIRE", 6), tx(300_00, "DEBIT", "CASH", 2));
        assertTrue(MonitoringRules.evaluate(txs, 3_000_00).isEmpty());
    }

    @Test
    void largeWireWellAboveNormalFiresVolumeSpike() {
        var hit = MonitoringRules.evaluate(List.of(tx(18_000_00, "CREDIT", "WIRE", 1)), 3_000_00);
        assertTrue(hit.isPresent());
        assertEquals("Inbound wire far above customer monthly volume", hit.get().rule());
    }

    @Test
    void largeWireForCustomerWhoNormallyReceivesLargeAmountsDoesNotFire() {
        assertTrue(MonitoringRules.evaluate(List.of(tx(18_000_00, "CREDIT", "WIRE", 1)), 20_000_00).isEmpty());
    }

    @Test
    void threeCashDepositsJustUnderThresholdInTwoDaysIsStructuring() {
        var txs = List.of(tx(9_400_00, "CREDIT", "CASH", 30), tx(9_100_00, "CREDIT", "CASH", 26), tx(9_700_00, "CREDIT", "CASH", 20));
        var hit = MonitoringRules.evaluate(txs, 2_000_00);
        assertTrue(hit.isPresent());
        assertTrue(hit.get().rule().startsWith("Structuring"));
    }

    @Test
    void depositsSpreadOverMoreThan48HoursAreNotStructuring() {
        var txs = List.of(tx(9_400_00, "CREDIT", "CASH", 120), tx(9_100_00, "CREDIT", "CASH", 70), tx(9_700_00, "CREDIT", "CASH", 10));
        assertTrue(MonitoringRules.evaluate(txs, 2_000_00).isEmpty());
    }

    @Test
    void depositsAtOrAboveTheThresholdAreNotCounted() {
        var txs = List.of(tx(10_000_00, "CREDIT", "CASH", 3), tx(12_000_00, "CREDIT", "CASH", 2), tx(15_000_00, "CREDIT", "CASH", 1));
        assertTrue(MonitoringRules.evaluate(txs, 2_000_00).isEmpty());
    }
}
