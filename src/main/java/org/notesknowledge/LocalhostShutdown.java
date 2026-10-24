package org.notesknowledge;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Owner-controlled filesystem stop signal; no HTTP management or authentication bypass. */
@Component
@Profile("localhost")
final class LocalhostShutdown {
    private final ConfigurableApplicationContext context;
    private final Path stop;
    private final AtomicBoolean closed = new AtomicBoolean();
    LocalhostShutdown(LocalhostMode mode, Environment environment, ConfigurableApplicationContext context) {
        this.context = context;
        String name = environment.getRequiredProperty("nkw.localhost.stop-name");
        if (!name.matches("stop-[0-9a-f]{32}")) throw new IllegalStateException("Local stop configuration unavailable");
        stop = mode.directory("outbox").getParent().resolve(name);
        if (Files.exists(stop, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Local stop signal already exists");
    }
    @EventListener(ApplicationReadyEvent.class)
    void watch() {
        Thread.ofPlatform().daemon().name("localhost-stop").start(() -> {
            try {
                while (!closed.get() && !Files.isRegularFile(stop, LinkOption.NOFOLLOW_LINKS)) Thread.sleep(250);
                if (!closed.get()) context.close();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
    }
    @jakarta.annotation.PreDestroy void closed() { closed.set(true); }
}
