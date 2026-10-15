package com.changegraph.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ChangeGraphBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChangeGraphBackendApplication.class, args);
	}

}
