package org.notesknowledge.identity;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityEmailDeliveryProperties.class)
class IdentityPasswordConfiguration {

    @Bean
    PasswordEncoder identityPasswordEncoder() {
        // 64 MiB, three passes, one lane. Benchmark on the target ARM64 host before rollout.
        var argon2id = new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
        return new DelegatingPasswordEncoder("argon2id", Map.of("argon2id", argon2id));
    }
}
