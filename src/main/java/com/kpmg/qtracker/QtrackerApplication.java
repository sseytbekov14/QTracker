package com.kpmg.qtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QtrackerApplication {
        public static void main(String[] args) {
            SpringApplication.run(QtrackerApplication.class, args);
        }
}