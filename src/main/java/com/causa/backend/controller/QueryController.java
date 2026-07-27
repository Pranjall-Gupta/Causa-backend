package com.causa.backend.controller;

import com.causa.backend.service.AnomalyDetectionService;
import com.causa.backend.service.AnomalyDetectionService.ServiceAlert;
import com.causa.backend.service.RcaService;
import com.causa.backend.service.RcaService.Trajectory;
import com.causa.backend.service.TopologyService;
import com.causa.backend.service.TopologyService.TopologyGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "http://localhost:3000")
public class QueryController {

    @Autowired
    private AnomalyDetectionService anomalyDetectionService;

    @Autowired
    private TopologyService topologyService;

    @Autowired
    private RcaService rcaService;

    @GetMapping("/alerts")
    public List<ServiceAlert> getAlerts() {
        return anomalyDetectionService.detectAnomalies();
    }

    @GetMapping("/graph")
    public TopologyGraph getGraph(@RequestParam(value = "time_point", required = false) String timePoint) {
        return topologyService.buildTopology();
    }

    @GetMapping("/rca")
    public List<Trajectory> getRca(@RequestParam("source") String source,
                                   @RequestParam(value = "time_point", required = false) String timePoint) {
        return rcaService.analyzeRootCauses(source);
    }
}
