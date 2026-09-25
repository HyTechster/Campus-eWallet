package com.hytechster.ewallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EwalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(EwalletApplication.class, args);
    }
}
