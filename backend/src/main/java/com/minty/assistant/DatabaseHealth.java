package com.minty.assistant;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
public class DatabaseHealth {
    private final DataSource dataSource;
    DatabaseHealth(DataSource dataSource) { this.dataSource = dataSource; }

    public boolean ready() {
        try (var connection = dataSource.getConnection()) { return connection.isValid(1); }
        catch (java.sql.SQLException e) { return false; }
    }
}
