package com.example.alerts.web;

import com.example.alerts.service.ScenarioService;
import com.example.alerts.web.Dtos.ScenarioInfo;
import com.example.alerts.web.Dtos.ScenarioResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarios;

    public ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<ScenarioInfo> list() {
        return scenarios.list();
    }

    /** Plays the scenario through the simulated bank rules; sends an alert to Hummingbird only if a rule fires. */
    @PostMapping("/{key}/run")
    public ScenarioResult run(@PathVariable String key) {
        return scenarios.run(key);
    }
}
