package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mock-only knobs for poking the simulated Hummingbird (no auth). */
@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
@RestController
@RequestMapping(MockProperties.BASE_PATH + "/mock")
public class ControlController {

    private final MockWorld world;
    private final WebhookSender webhooks;

    public ControlController(MockWorld world, WebhookSender webhooks) {
        this.world = world;
        this.webhooks = webhooks;
    }

    /** The commands an alert can carry (in comments / rule / review_tags / other_info) to steer the mock. */
    @GetMapping("/directives")
    public List<Map<String, String>> directives() {
        return Directives.HELP;
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        return world.debugView();
    }

    /** Run the alert's next lifecycle step immediately. */
    @PostMapping("/alerts/{id}/advance")
    public Map<String, Object> advance(@PathVariable String id) {
        return Map.of("advanced", world.advance(id));
    }

    /** Run all remaining steps immediately. */
    @PostMapping("/alerts/{id}/fast-forward")
    public Map<String, Object> fastForward(@PathVariable String id) {
        return Map.of("steps_run", world.fastForward(id));
    }

    /** Body: {"target":"cas_...","sources":["cas_...","cas_..."]} - merges cases and fires case.merged. */
    @PostMapping("/cases/merge")
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(@RequestBody Map<String, Object> body) {
        world.merge(String.valueOf(body.get("target")), (List<String>) body.get("sources"));
        return Map.of("ok", true);
    }

    /** Body: {"type":"alert.stuck","data":{...}} - fire any webhook by hand. */
    @PostMapping("/webhooks/send")
    @SuppressWarnings("unchecked")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) {
        webhooks.send(String.valueOf(body.get("type")), (Map<String, Object>) body.getOrDefault("data", Map.of()));
        return Map.of("ok", true);
    }

    @GetMapping("/webhooks")
    public List<Map<String, Object>> deliveries() {
        return webhooks.deliveries();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        world.reset();
        return Map.of("ok", true);
    }
}
