package com.example.toiletapi.global.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthCheckControllerTest {
    private DataSource dataSource;
    private Connection connection;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        mvc = MockMvcBuilders.standaloneSetup(new HealthCheckController(dataSource)).build();
    }

    @Test
    void healthyResponseIsMinimalAndUncachedAndReleasesConnection() throws Exception {
        assertResponse(200, "UP");
        verify(connection).close();
        verify(connection, never()).getCatalog();
    }

    @Test
    void connectionFailureReturnsUnavailableWithoutDriverDetails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("synthetic-private-host/schema credential-detail"));
        assertResponse(503, "DOWN");
    }

    @Test
    void connectionCleanupFailureIsAlsoUnavailable() throws Exception {
        doThrow(new SQLException("synthetic cleanup detail")).when(connection).close();
        assertResponse(503, "DOWN");
    }

    private void assertResponse(int status, String state) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/health")).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("application/json", response.getContentType());
        assertEquals("{\"status\":\"" + state + "\"}", response.getContentAsString());
    }
}
