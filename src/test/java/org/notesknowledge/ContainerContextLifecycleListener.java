package org.notesknowledge;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cached Spring resources must not outlive their class-owned containers. */
public final class ContainerContextLifecycleListener extends AbstractTestExecutionListener {
    @Override public int getOrder() { return 3000; }

    @Override public void afterTestClass(TestContext context) {
        // Do not load a context merely to dispose it, and leave ordinary shared
        // Spring tests alone. Static @Container fixtures are class-scoped.
        if (AnnotatedElementUtils.hasAnnotation(context.getTestClass(), Testcontainers.class)
                && context.hasApplicationContext()) {
            context.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
        }
    }
}
