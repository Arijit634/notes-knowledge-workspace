package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Disposable host facts only. No application, credentials, user input or media. */
public final class HostPreflight {
    @FunctionalInterface interface Check { void run() throws Exception; }
    private static boolean passes(Check check) {
        try { check.run(); return true; } catch (Exception denied) { return false; }
    }
    private static String bounded(Path path) throws Exception {
        try (var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(129);
            if(bytes.length>128)throw new IllegalStateException("Diagnostic bound");
            return new String(bytes,java.nio.charset.StandardCharsets.US_ASCII).strip();
        }
    }
    private static String numeric(String text) {
        return text.matches("(?:max|[0-9]{1,12})(?: [0-9]{1,12})?")?text:"unavailable";
    }
    public static void main(String[] args) throws Exception {
        if(args.length>1||args.length==1&&!args[0].equals("--once"))throw new IllegalArgumentException("Fixed diagnostic only");
        var root=Path.of("/opt/pdf");var selection=new Properties();
        try(var input=Files.newInputStream(Path.of("/provenance/runtime-selection.properties"))){selection.load(input);}
        String identity=selection.getProperty("notes.media-runtime.pdf.artifact-identity");
        boolean custody=passes(()->PdfLinuxDeploymentCustody.require(root.resolve("helper"),root.resolve("runtime"),root.resolve("artifacts")));
        boolean budget=passes(MediaContainerBudget::require);
        boolean service=passes(PdfLinuxServiceBudget::require);
        boolean fingerprint=passes(()->PdfRuntimeIdentity.require(identity,root.resolve("runtime"),root.resolve("artifacts"),root.resolve("helper")));
        boolean readonly=Files.getFileStore(root).isReadOnly();
        boolean namespace=bounded(Path.of("/proc/self/cgroup")).equals("0::/");
        long uid=((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).longValue();
        String memory=numeric(bounded(Path.of("/sys/fs/cgroup/memory.max")));
        String pids=numeric(bounded(Path.of("/sys/fs/cgroup/pids.max")));
        String cpu=numeric(bounded(Path.of("/sys/fs/cgroup/cpu.max")));
        System.out.println("{\"hostPreflight\":true,\"uidUnprivileged\":"+(uid==65533)+",\"pdfCustody\":"+custody
            +",\"containerBudget\":"+budget+",\"serviceBudget\":"+service+",\"artifactIdentity\":"+fingerprint
            +",\"artifactFilesystemReadOnly\":"+readonly+",\"privateCgroupNamespace\":"+namespace
            +",\"memoryMax\":\""+memory+"\",\"pidsMax\":\""+pids+"\",\"cpuMax\":\""+cpu
            +"\",\"applicationDataAccessed\":false,\"providerCalls\":0}");
        if(args.length==1)return;
        // Fixed TCP health only. No routes, command input, returned facts or API.
        try(var listener=new java.net.ServerSocket(10000,16)) {
            while(true)try(var ignored=listener.accept()){ }
        }
    }
}
