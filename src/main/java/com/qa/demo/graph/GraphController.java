package com.qa.demo.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/graph")
public class GraphController {

    private final Driver neo4jDriver;
    private final GraphProperties graphProperties;

    public GraphController(Driver neo4jDriver, GraphProperties graphProperties) {
        this.neo4jDriver = neo4jDriver;
        this.graphProperties = graphProperties;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        try (Session session = neo4jDriver.session()) {
            Record record = session.run(
                    "RETURN 1 AS ok, count { MATCH (c:Company) } AS companyCount"
            ).single();
            return Map.of(
                    "ok", record.get("ok").asInt() == 1,
                    "companyCount", record.get("companyCount").asLong(),
                    "uri", graphProperties.getUri(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }
}
