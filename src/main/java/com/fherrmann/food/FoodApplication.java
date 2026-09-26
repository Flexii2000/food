package com.fherrmann.food;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Die Zeitsteuerung braucht genau ein Bauteil: {@code ReleaseAnnouncer}, der nach
 * neuen Android-Versionen schaut.
 */
@SpringBootApplication
@EnableScheduling
public class FoodApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodApplication.class, args);
    }

    /** Injectable so tests can pin "today" instead of depending on the wall clock. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
