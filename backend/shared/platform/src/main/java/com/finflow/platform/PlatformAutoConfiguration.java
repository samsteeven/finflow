package com.finflow.platform;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

@AutoConfiguration
@EnableConfigurationProperties(PlatformProperties.class)
@PropertySource("classpath:finflow-platform.properties")
@org.springframework.context.annotation.Import(ActuatorObservationFilter.class)
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Resilience resilience(PlatformProperties properties, MeterRegistry meters) {
        return new Resilience(properties, meters);
    }
}
