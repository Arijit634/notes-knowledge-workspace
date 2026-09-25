package org.notesknowledge.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import java.util.List;

import org.notesknowledge.identity.IdentityEligibilityFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import org.notesknowledge.websupport.ApiProblemWriter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Spring Session writes join an enclosing Identity security transition. */
    @Bean("springSessionTransactionOperations")
    TransactionOperations springSessionTransactionOperations(
            ObjectProvider<PlatformTransactionManager> managers) {
        // Database-less compatibility profiles do not instantiate the JDBC repository.
        // Resolve the manager only when Spring Session performs an actual JDBC operation.
        return new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) {
                TransactionTemplate operations = new TransactionTemplate(managers.getObject());
                operations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                return operations.execute(callback);
            }
        };
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    DefaultCookieSerializer sessionCookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy identitySessionAuthenticationStrategy(
            HttpSessionCsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            HttpSessionCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            ObjectProvider<IdentityEligibilityFilter> eligibilityFilter,
            ApiProblemWriter problemWriter) throws Exception {
        IdentityEligibilityFilter identityCore = eligibilityFilter.getIfAvailable();
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/session")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                identityCore != null))
                        .requestMatchers(HttpMethod.POST, "/api/auth/registrations",
                                "/api/auth/email-verification/requests",
                                "/api/auth/email-verification/confirmations",
                                "/api/auth/login/password",
                                "/api/auth/oidc/google/authorizations")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                identityCore != null && !(authentication.get().getPrincipal()
                                        instanceof org.notesknowledge.identity.IdentitySessionPrincipal)))
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                identityCore != null))
                        .requestMatchers(HttpMethod.GET, "/api/auth/oidc/google/callback")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                identityCore != null))
                        .requestMatchers(HttpMethod.GET, "/api/auth/reauth/oidc/google/callback")
                        .hasAuthority("ROLE_USER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/mfa/challenges/{challengeId}/totp",
                                "/api/auth/mfa/challenges/{challengeId}/recovery-code")
                        .hasAuthority("ROLE_MFA_PENDING")
                        .requestMatchers(HttpMethod.POST, "/api/auth/reauth/password",
                                "/api/auth/reauth/oidc/google/authorizations",
                                "/api/me/security/mfa/totp/enrollments",
                                "/api/me/security/mfa/totp/enrollments/{enrollmentId}/confirmation")
                        .hasAuthority("ROLE_USER")
                        .anyRequest()
                        .denyAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                problemWriter.writeAuthenticationRequired(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                problemWriter.writeAccessDenied(request, response, exception)))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .headers(headers -> headers
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicy.NO_REFERRER)));

        if (identityCore != null) {
            http.addFilterAfter(identityCore, SecurityContextHolderFilter.class);
        }
        return http.build();
    }
}
