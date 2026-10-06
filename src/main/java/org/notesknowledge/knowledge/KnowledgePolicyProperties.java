package org.notesknowledge.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Neutral operator-owned policy code. No default/seeded provider or legal disclosure. */
@ConfigurationProperties("knowledge.processing-policy")
record KnowledgePolicyProperties(String code) { }
