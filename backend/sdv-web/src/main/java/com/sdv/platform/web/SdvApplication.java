package com.sdv.platform.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sdv.platform")
public class SdvApplication {
    public static void main(String[] args) {
        SpringApplication.run(SdvApplication.class, args);
    }
}
