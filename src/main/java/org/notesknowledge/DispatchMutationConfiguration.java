package org.notesknowledge;

import java.lang.reflect.Method;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

/** Infrastructure advice only. Module services explicitly declare their coordination scope. */
@Configuration(proxyBeanMethods=false)
public class DispatchMutationConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    DefaultPointcutAdvisor dispatchMutationAdvisor(DispatchCoordinator coordinator) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> target) {
                return method.isAnnotationPresent(CoordinatedMutation.class);
            }
        };
        MethodInterceptor advice = invocation -> {
            var declaration = invocation.getMethod().getAnnotation(CoordinatedMutation.class);
            UUID owner = (UUID) invocation.getArguments()[declaration.ownerArgument()];
            var handle = declaration.noteArgument() < 0 ? coordinator.ownerMutation(owner)
                    : coordinator.noteMutation(owner, (UUID) invocation.getArguments()[declaration.noteArgument()]);
            Object result;
            try (handle) { result = invocation.proceed(); }
            if (!handle.safelyReleased()) throw DispatchCoordinator.unavailable();
            return result;
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, advice);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return advisor;
    }
}
