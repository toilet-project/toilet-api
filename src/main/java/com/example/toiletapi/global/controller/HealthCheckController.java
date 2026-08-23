package com.example.toiletapi.global.controller;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    private final DataSource dataSource;

    public HealthCheckController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health")
    public String healthCheck() {
        try (Connection connection = dataSource.getConnection()) {
            return "API server is running (DB: " + connection.getCatalog() + ")";
        } catch (Exception exception) {
            return "API database connection failed: " + exception.getMessage();
        }
    }
}
