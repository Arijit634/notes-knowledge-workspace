package org.notesknowledge.notes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Durable synthetic filesystem custody across application restarts, test only. */
@TestConfiguration(proxyBeanMethods = false)
public class ConfiguredMediaStorageFixtures {
    @Bean @Primary PrivateAttachmentObjectStore configuredSyntheticStore(org.springframework.core.env.Environment environment) {
        Path directory = Path.of(environment.getRequiredProperty("synthetic.media-store"));
        return new PrivateAttachmentObjectStore() {
            private Path path(String reference) {
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    throw new AssertionError("Synthetic object I/O inside database transaction");
                // References remain opaque. Hashing here is only the test store's
                // safe filename encoding; it never grants owner authority.
                try {
                    byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return directory.resolve(java.util.HexFormat.of().formatHex(hash));
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            }
            public InputStream openRange(String reference, long offset, long length) {
                try {
                    var input = Files.newInputStream(path(reference)); input.skipNBytes(offset);
                    return new InputStream() {
                        long remaining = length;
                        public int read() throws java.io.IOException {
                            if (remaining == 0) return -1;
                            int value = input.read(); if (value >= 0) remaining--; return value;
                        }
                        public int read(byte[] bytes, int start, int count) throws java.io.IOException {
                            if (remaining == 0) return -1;
                            int read = input.read(bytes, start, (int)Math.min(count, remaining));
                            if (read > 0) remaining -= read; return read;
                        }
                        public void close() throws java.io.IOException { input.close(); }
                    };
                } catch (java.io.IOException failed) { throw new AssertionError("Synthetic stored bytes unavailable", failed); }
            }
            public void write(String reference, InputStream input, long size) {
                try (var output = Files.newOutputStream(path(reference), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    long copied = input.transferTo(output);
                    if (copied != size) throw new AssertionError("Synthetic stored byte count mismatch");
                } catch (java.io.IOException failed) { throw new AssertionError("Synthetic storage write failed", failed); }
            }
            public void delete(String reference) {
                try { Files.deleteIfExists(path(reference)); }
                catch (java.io.IOException failed) { throw new AssertionError("Synthetic storage delete failed", failed); }
            }
            public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) { return List.of(); }
        };
    }
    public static String pdfIdentity(Path root) throws Exception {
        return PdfRuntimeIdentity.fingerprint(root.resolve("runtime"), root.resolve("artifacts"), root.resolve("helper"));
    }
    public static String videoIdentity(Path root) throws Exception {
        return SupervisedVideoClipRuntime.identity(root.resolve("helper"), root.resolve("runtime"), root.resolve("artifacts"), root.resolve("tools"));
    }
    public static org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfChild page(
            org.springframework.context.ApplicationContext application, byte[] original, int page) {
        try { return application.getBean(PdfPageRuntime.class).render(original, page); }
        catch (RuntimeException denied) {
            // Synthetic native qualification only: distinguish parent admission
            // from child processing without logging source/paths/native output.
            boolean budgetAvailable;
            try { PdfLinuxServiceBudget.require(); budgetAvailable=true; }
            catch (java.io.IOException unavailable) { budgetAvailable=false; }
            System.out.println("{\"configuredPdfFailure\":true,\"parentBudgetAvailable\":"+budgetAvailable+"}");
            throw denied;
        }
    }
    public static org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan plan(
            org.springframework.context.ApplicationContext application, byte[] original) {
        return application.getBean(VideoClipRuntime.class).plan(original);
    }
    public static org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoChild clip(
            org.springframework.context.ApplicationContext application, byte[] original,
            org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan plan, int first, int last) {
        return application.getBean(VideoClipRuntime.class).render(original, plan, first, last);
    }
}
