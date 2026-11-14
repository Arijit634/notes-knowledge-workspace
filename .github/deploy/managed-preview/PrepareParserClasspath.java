package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;

/** Build-only extraction from the exact application; never ships as a tool. */
class PrepareParserClasspath {
    public static void main(String[] args) throws Exception {
        if(args.length!=0)throw new IllegalArgumentException("No configurable input");
        System.setProperty("java.class.path","/application.jar");
        Path root=Path.of("/opt/pdf/artifacts/admission");
        Files.createDirectory(root);
        String actual=AttachmentParserRuntime.childClasspath(root);
        if(!actual.equals(root.resolve("classes")+java.io.File.pathSeparator+root.resolve("libraries")+"/*"))
            throw new IllegalStateException("Parser classpath preparation failed");
        if(!actual.equals(AttachmentParserRuntime.immutableClasspath(root)))
            throw new IllegalStateException("Parser image ownership validation failed");
    }
}
