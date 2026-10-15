package com.changegraph.backend.config;


import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile("!test")
public class FalkorDbConfig {

    @Bean(destroyMethod = "close")
    public Driver falkorDbDriver(
            @Value("${falkordb.host:localhost}") String host,
            @Value("${falkordb.port:6379}") int port,
            @Value("${falkordb.username:}") String username,
            @Value("${falkordb.password:}") String password) {
        if (StringUtils.hasText(password)) {
            String user = StringUtils.hasText(username) ? username : "default";
            return FalkorDB.driver(host, port, user, password);
        }
        return FalkorDB.driver(host, port);
    }
}