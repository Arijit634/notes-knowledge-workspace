package org.notesknowledge.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@IdentityCoreEnabled
@EnableConfigurationProperties(MfaProperties.class)
class MfaConfiguration { }
