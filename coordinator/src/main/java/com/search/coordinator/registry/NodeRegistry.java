package com.search.coordinator.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NodeRegistry {
    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final ConcurrentHashMap<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    public void register(String nodeId, String url) {
        nodes.put(nodeId, new NodeInfo(nodeId, url, System.currentTimeMillis()));
        log.info("Node registered: {} -> {}", nodeId, url);
    }

    public void deregister(String nodeId) {
        nodes.remove(nodeId);
        log.info("Node deregistered: {}", nodeId);
    }

    public List<NodeInfo> getAll() {
        return List.copyOf(nodes.values());
    }

    public Optional<NodeInfo> get(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public int size() {
        return nodes.size();
    }
}
