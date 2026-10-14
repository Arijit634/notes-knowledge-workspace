package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Production OCI/S3 adapter with a controlled external HTTP transport. Only
 * the external service is simulated; SDK serialization and custody are real. */
@TestConfiguration(proxyBeanMethods = false)
public class ConfiguredMediaStorageFixtures {
    @Bean ControlledObjectStorageTransport configuredStorageTransport(org.springframework.core.env.Environment environment) {
        return new ControlledObjectStorageTransport(Path.of(environment.getRequiredProperty("synthetic.media-store")));
    }
    @Bean @Primary OciPrivateAttachmentObjectStore configuredSyntheticStore(ControlledObjectStorageTransport transport) {
        return ControlledObjectStorageTransport.store(transport);
    }
    public static void failDeletion(org.springframework.context.ApplicationContext app,boolean fail) {
        app.getBean(ControlledObjectStorageTransport.class).failDelete=fail;
    }
    public static void ambiguousUpload(org.springframework.context.ApplicationContext app) {
        app.getBean(ControlledObjectStorageTransport.class).ambiguousPut=true;
    }
    public static long ageObjects(org.springframework.context.ApplicationContext app)throws java.io.IOException {
        var transport=app.getBean(ControlledObjectStorageTransport.class);
        try(var files=Files.list(transport.directory)){
            var objects=files.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}")).toList();
            for(var file:objects)Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(90_000)));
            return objects.size();
        }
    }
    public static void reconcile(org.springframework.context.ApplicationContext app) {
        app.getBean(AttachmentDeleteService.class).reconcilePendingPage(null,5);
        app.getBean(AttachmentUploadService.class).reconcile(null,5);
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
