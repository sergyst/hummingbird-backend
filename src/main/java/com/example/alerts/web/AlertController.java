package com.example.alerts.web;

import com.example.alerts.config.HummingbirdProperties;
import com.example.alerts.domain.AlertRecord;
import com.example.alerts.domain.AlertState;
import com.example.alerts.service.AlertService;
import com.example.alerts.web.Dtos.AlertDetail;
import com.example.alerts.web.Dtos.AlertView;
import com.example.alerts.web.Dtos.AppConfig;
import com.example.alerts.web.Dtos.EventView;
import com.example.alerts.web.Dtos.PageDto;
import com.example.alerts.web.Dtos.RefreshSummary;
import com.example.alerts.web.Dtos.Stats;
import com.example.alerts.web.Dtos.SubmitAlertRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AlertController {

    private final AlertService service;
    private final HummingbirdProperties props;

    public AlertController(AlertService service, HummingbirdProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/config")
    public AppConfig config() {
        String dashboard = props.sandbox() ? "https://sandbox.hummingbird.co" : "https://app.hummingbird.co";
        return new AppConfig(props.baseUrl(), props.sandbox(), props.hasCredentials(),
                !props.webhookSecret().isBlank(), props.apiVersion(), dashboard);
    }

    @GetMapping("/stats")
    public Stats stats() {
        return service.stats();
    }

    @GetMapping("/alerts")
    public PageDto<AlertView> list(@RequestParam(required = false) String state,
                                   @RequestParam(required = false, defaultValue = "") String q,
                                   @RequestParam(required = false, defaultValue = "") String outcome,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        AlertState parsed = null;
        if (state != null && !state.isBlank() && !"ALL".equalsIgnoreCase(state)) {
            parsed = AlertState.valueOf(state.trim().toUpperCase(Locale.ROOT));
        }
        Page<AlertRecord> p = service.search(parsed, q, outcome, page, size);
        return new PageDto<>(p.getContent().stream().map(AlertView::of).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/alerts/{id}")
    public AlertDetail detail(@PathVariable Long id) {
        return new AlertDetail(AlertView.of(service.get(id)),
                service.eventsFor(id).stream().map(EventView::of).toList());
    }

    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertView submit(@Valid @RequestBody SubmitAlertRequest request) {
        return AlertView.of(service.submit(request));
    }

    @PostMapping("/alerts/{id}/refresh")
    public AlertView refresh(@PathVariable Long id) {
        return AlertView.of(service.refresh(id));
    }

    @PostMapping("/alerts/refresh-pending")
    public RefreshSummary refreshPending() {
        return service.refreshAllPending();
    }

    /** Pass-through of Hummingbird's case alert (outcome + lifecycle events). */
    @GetMapping("/alerts/{id}/lifecycle")
    public Map<String, Object> lifecycle(@PathVariable Long id) {
        return service.lifecycle(id);
    }

    @DeleteMapping("/alerts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocal(@PathVariable Long id) {
        service.deleteLocal(id);
    }
}
