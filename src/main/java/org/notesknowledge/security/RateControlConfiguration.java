package org.notesknowledge.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityRateProperties.class)
class RateControlConfiguration {
    @Bean
    @ConditionalOnMissingBean(RateControlService.class)
    RateControlService rateControlService(RateLimitPort port,
            SecurityControlRejectionEvents events) {
        return new RateControlService(port, events);
    }
}
