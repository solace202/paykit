package dev.paykit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PayKitApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayKitApplication.class, args);
    }
}
