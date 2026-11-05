package com.minty.assistant;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseHealthTest {
    @Test void healthChecksRealConnectionValidityAndClosesIt() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
        assertThat(new DatabaseHealth(source).ready()).isTrue();
        verify(connection).close();
    }
    @Test void failedDatabaseConnectionIsNotReportedAsReady() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("private connection details"));
        assertThat(new DatabaseHealth(source).ready()).isFalse();
    }
    @Test void connectionFailureReturnsAnActionableErrorWithoutInternalDetails() {
        var response = new ApiErrorHandler().databaseUnavailable(new DataAccessResourceFailureException("private connection details"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().toString()).contains("DATABASE_UNAVAILABLE", "数据库暂时无法连接").doesNotContain("private connection details");
    }
}
