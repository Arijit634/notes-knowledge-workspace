package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Closed-egress native qualification only, never packaged in the application. */
public final class ManagedBoundaryQualification {
    public static void main(String[] args)throws Exception {
        if(args.length>1||args.length==1&&!args[0].equals("--once")||!ManagedMediaBoundary.selected()||!ManagedMediaBoundary.verified())throw new IllegalStateException("Managed boundary denied");
        MediaContainerBudget.require();PdfLinuxServiceBudget.require();
        var root=Path.of("/opt/pdf");
        PdfLinuxDeploymentCustody.require(root.resolve("helper"),root.resolve("runtime"),root.resolve("artifacts"));
        var video=Path.of("/opt/video");
        PdfLinuxDeploymentCustody.require(video.resolve("helper"),video.resolve("runtime"),video.resolve("tools"));
        var properties=new Properties();
        try(var input=Files.newInputStream(Path.of("/provenance/runtime-selection.properties"))){properties.load(input);}
        String identity=properties.getProperty("notes.media-runtime.pdf.artifact-identity");
        Files.createDirectory(Path.of("/tmp/managed-pdf-recovery"),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        try(var runtime=SupervisedPdfPageRuntime.linux(root.resolve("helper"),root.resolve("runtime"),root.resolve("artifacts"),
                identity,Path.of("/tmp/managed-pdf-recovery"),"native-pdf-v1",identity)) {
            // The constructor executes the unchanged confined synthetic raster.
        }
        System.out.println("{\"managedNativePreflight\":true,\"parentWriteBoundaryVerified\":true,\"hardProcessBudgetVerified\":true,\"runtimeCustodyVerified\":true,\"unchangedPdfWorkerSucceeded\":true,\"liveProviderCalls\":0}");
        if(args.length==0)try(var listener=new java.net.ServerSocket(10000,16)) {
            while(true)try(var ignored=listener.accept()){ }
        }
    }
}
