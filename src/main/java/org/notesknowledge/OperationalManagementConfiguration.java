package org.notesknowledge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** The host agent scrapes loopback only. Browser/API security remains in its own chain. */
@Configuration(proxyBeanMethods=false)
@Profile("operations")
@org.springframework.scheduling.annotation.EnableScheduling
class OperationalManagementConfiguration {
    private final int port;
    OperationalManagementConfiguration(Environment environment){
        port=environment.getProperty("management.server.port",Integer.class,8081);
        int application=environment.getProperty("server.port",Integer.class,8080);
        if(port<1024||port>65535||port==application||!"127.0.0.1".equals(environment.getProperty("management.server.address")))
            throw new IllegalStateException("Restricted management listener configuration required");
    }
    @Bean @Order(-1)
    SecurityFilterChain loopbackManagementSecurity(HttpSecurity http)throws Exception {
        http.securityMatcher(request->request.getLocalPort()==port)
            .authorizeHttpRequests(auth->auth
                .requestMatchers(request->(request.getMethod().equals("GET")||request.getMethod().equals("HEAD"))
                    &&request.getLocalAddr().equals("127.0.0.1")&&request.getRemoteAddr().equals("127.0.0.1")
                    &&(request.getRequestURI().equals("/actuator/prometheus")||request.getRequestURI().equals("/actuator/health")
                        ||request.getRequestURI().equals("/actuator/health/liveness")||request.getRequestURI().equals("/actuator/health/readiness")))
                .permitAll().anyRequest().denyAll())
            .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session->session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
