package org.notesknowledge.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "identity.delivery", name = "polling-enabled", havingValue = "true")
class IdentitySchedulingConfiguration { }
