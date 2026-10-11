package com.janemichael.jmrecruitingcrm.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {

    /** The firm's clock. "Today" and daily touch counts follow this time zone. */
    @Bean
    public Clock clock(@Value("${app.timezone:America/New_York}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
