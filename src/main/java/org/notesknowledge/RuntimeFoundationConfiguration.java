package org.notesknowledge;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RuntimeFoundationConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
