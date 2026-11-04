package com.minty.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class MintyApplication {
    public static void main(String[] args) { SpringApplication.run(MintyApplication.class, args); }
}
