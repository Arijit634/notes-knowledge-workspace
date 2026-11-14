package org.notesknowledge.identity;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Share one memory-hard workspace across Identity's password operations. */
final class BoundedPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final Semaphore workspace = new Semaphore(1, true);
    private final long waitMillis;

    BoundedPasswordEncoder(PasswordEncoder delegate) { this(delegate, 2_000); }
    BoundedPasswordEncoder(PasswordEncoder delegate, long waitMillis) {
        if (waitMillis < 1 || waitMillis > 2_000) throw new IllegalArgumentException("Invalid password admission bound");
        this.delegate = delegate;
        this.waitMillis = waitMillis;
    }
    @Override public String encode(CharSequence raw) { return admitted(() -> delegate.encode(raw)); }
    @Override public boolean matches(CharSequence raw, String encoded) { return admitted(() -> delegate.matches(raw, encoded)); }
    @Override public boolean upgradeEncoding(String encoded) { return delegate.upgradeEncoding(encoded); }

    private <T> T admitted(Supplier<T> operation) {
        try {
            if (!workspace.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) throw ApiFailureException.rateLimited(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        try { return operation.get(); }
        finally { workspace.release(); }
    }
}
