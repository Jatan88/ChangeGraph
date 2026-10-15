package com.changegraph.backend.config;

import com.falkordb.Driver;
import com.falkordb.GraphContextGenerator;
import com.falkordb.ResultSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Configuration
@Profile("test")
public class TestInfrastructureConfig {

    @Bean
    @Primary
    public Driver falkorDbDriver() {
        Driver driver = mock(Driver.class);
        GraphContextGenerator graph = mock(GraphContextGenerator.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.iterator()).thenReturn(Collections.emptyIterator());
        when(resultSet.size()).thenReturn(0);
        when(driver.graph(anyString())).thenReturn(graph);
        when(graph.query(anyString())).thenReturn(resultSet);
        when(graph.query(anyString(), any())).thenReturn(resultSet);
        when(graph.readOnlyQuery(anyString())).thenReturn(resultSet);
        when(graph.readOnlyQuery(anyString(), any())).thenReturn(resultSet);
        when(graph.deleteGraph()).thenReturn("ok");
        return driver;
    }
}
