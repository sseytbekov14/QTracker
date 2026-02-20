package com.kpmg.qtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@EnableScheduling
public class QtrackerApplication {
        public static void main(String[] args) {
            SpringApplication.run(QtrackerApplication.class, args);
        }
        
        @Bean
        public Clock clock() {
            return Clock.system(ZoneId.of("Asia/Almaty"));
        }
}