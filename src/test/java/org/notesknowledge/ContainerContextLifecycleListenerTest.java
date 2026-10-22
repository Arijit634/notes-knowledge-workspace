package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextManager;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("FAST")
class ContainerContextLifecycleListenerTest {
    @Test void discoveredListenerClosesAndEvictsTheClassOwnedContext() throws Exception {
        var manager=new TestContextManager(ContainerFixture.class);
        assertThat(manager.getTestExecutionListeners()).anyMatch(ContainerContextLifecycleListener.class::isInstance);
        manager.beforeTestClass();
        var context=manager.getTestContext().getApplicationContext();
        var resource=context.getBean(OwnedResource.class);
        assertThat(resource.closed.get()).isFalse();
        manager.afterTestClass();
        assertThat(resource.closed.get()).isTrue();
        assertThat(manager.getTestContext().hasApplicationContext()).isFalse();
    }

    @Test void ordinarySharedContextIsNotEvicted() {
        var context=mock(TestContext.class);
        doReturn(OrdinaryFixture.class).when(context).getTestClass();
        new ContainerContextLifecycleListener().afterTestClass(context);
        verify(context,never()).hasApplicationContext();
        verify(context,never()).markApplicationContextDirty(any(HierarchyMode.class));
    }

    @Test void absentContainerContextIsNotCreatedDuringCleanup() {
        var context=mock(TestContext.class);
        doReturn(ContainerFixture.class).when(context).getTestClass();
        when(context.hasApplicationContext()).thenReturn(false);
        new ContainerContextLifecycleListener().afterTestClass(context);
        verify(context,never()).getApplicationContext();
        verify(context,never()).markApplicationContextDirty(any(HierarchyMode.class));
    }

    @Testcontainers
    @ContextConfiguration(classes=OwnedConfiguration.class)
    static class ContainerFixture { }
    static class OrdinaryFixture { }
    @TestConfiguration(proxyBeanMethods=false)
    static class OwnedConfiguration {
        @Bean OwnedResource ownedResource() { return new OwnedResource(); }
    }
    static class OwnedResource implements AutoCloseable {
        final AtomicBoolean closed=new AtomicBoolean();
        @Override public void close() { closed.set(true); }
    }
}
