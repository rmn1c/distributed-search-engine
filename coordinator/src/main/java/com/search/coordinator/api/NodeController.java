package com.search.coordinator.api;

import com.search.coordinator.registry.NodeRegistry;
import com.search.shared.dto.NodeRegistrationRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/nodes")
public class NodeController {

    private final NodeRegistry nodeRegistry;

    public NodeController(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> register(@RequestBody NodeRegistrationRequest req) {
        nodeRegistry.register(req.nodeId(), req.url());
        return ResponseEntity.ok(Map.of("status", "registered", "nodeId", req.nodeId()));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(nodeRegistry.getAll());
    }

    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> deregister(@PathVariable String nodeId) {
        nodeRegistry.deregister(nodeId);
        return ResponseEntity.noContent().build();
    }
}
