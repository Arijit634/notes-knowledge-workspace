package org.notesknowledge.knowledge;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({DeterministicExtractionProperties.class,KnowledgePolicyProperties.class})
class KnowledgeFoundationConfiguration { }
