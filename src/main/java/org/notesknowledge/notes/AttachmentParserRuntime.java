package org.notesknowledge.notes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PDF;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;
import org.notesknowledge.websupport.ApiFailureException;

/** Local resource containment, not an OS sandbox. No request controls this environment. */
final class AttachmentParserRuntime implements AutoCloseable {
    static final int MAX_IPC_BYTES = 65_536;
    private final Path directory;
    private final PipesForkParser parser;

    AttachmentParserRuntime() {
        Path created = null;
        PipesForkParser started = null;
        try {
            created = privateDirectory();
            Path json = resource(created, "parser.json");
            Path logback = resource(created, "logback.xml");
            Path log4j = resource(created, "log4j2.xml");
            var arguments = new ArrayList<>(List.of("-Xmx256m", "-XX:MaxDirectMemorySize=64m",
                    "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                    "-Djava.io.tmpdir=" + created,
                    "-Dlogback.configurationFile=" + logback, "-Dlog4j.configurationFile=" + log4j,
                    "-cp", childClasspath(created)));
            var configuration = new PipesForkParserConfig().setUserConfigPath(json)
                    .setPluginsDir(created.resolve("plugins"))
                    .setJavaPath(Path.of(System.getProperty("java.home"), "bin",
                            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString())
                    .setNumClients(1).setMaxFilesPerProcess(25).setWriteLimit(256).setMaxEmbeddedCount(0)
                    .setTimeoutLimits(new TimeoutLimits(15_000, 5_000)).setJvmArgs(arguments);
            configuration.getPipesConfig().setMaxInlineBytes(0);
            configuration.getPipesConfig().setMaxIpcPayloadBytes(MAX_IPC_BYTES);
            started = new PipesForkParser(configuration);
            directory = created;
            parser = started;
        } catch (Exception failure) {
            if (started != null) try { started.close(); } catch (Exception ignored) { }
            if (created != null) removeDirectory(created);
            throw unavailable(); // Never attach parser-controlled diagnostics as a cause.
        }
    }

    synchronized Metadata parse(Path ownedInput) {
        try {
            var result = parser.parse(ownedInput);
            requireSuccess(result);
            // No text or arbitrary metadata escapes this boundary. Values are capped again
            // after Tika's pre-allocation IPC frame limit, before application conversion.
            Metadata safe = new Metadata();
            for (String key : METADATA_KEYS) {
                String[] values = result.getMetadata().getValues(key);
                if (values.length > 16) throw invalid();
                for (String value : values) {
                    if (value.length() > 256) throw invalid();
                    // Tika 4 reserved keys must retain their registered Property type.
                    // The allowlist, not the parsed document, selects every key here.
                    var property = org.apache.tika.metadata.Property.get(key);
                    if (property == null) safe.add(key, value);
                    else safe.add(property, value);
                }
            }
            if (result.getMetadataList().size() != 1) throw invalid();
            return safe;
        } catch (ApiFailureException failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception failure) { throw unavailable(); }
    }

    private static final Set<String> METADATA_KEYS = java.util.stream.Stream.concat(
            java.util.stream.Stream.of("Content-Type"),
            java.util.stream.Stream.of(PagedText.N_PAGES, TIFF.IMAGE_WIDTH, TIFF.IMAGE_LENGTH, XMPDM.DURATION,
                    PDF.IS_ENCRYPTED, PDF.ACTION_TYPES, PDF.ACTION_TRIGGERS, PDF.HAS_XFA,
                    PDF.HAS_ACROFORM_FIELDS, PDF.HAS_SIGNATURE_FIELDS, PDF.HAS_COLLECTION, PDF.HAS_3D,
                    PDF.ANNOTATION_TYPES, PDF.ANNOTATION_SUBTYPES, PDF.NUM_3D_ANNOTATIONS,
                    PDF.EMBEDDED_FILE_DESCRIPTION, PDF.ASSOCIATED_FILE_RELATIONSHIP,
                    TikaCoreProperties.EMBEDDED_RESOURCE_LIMIT_REACHED,
                    TikaCoreProperties.EMBEDDED_DEPTH_LIMIT_REACHED,
                    TikaCoreProperties.TIKA_META_EXCEPTION_EMBEDDED_STREAM).map(p -> p.getName()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    static void requireSuccess(PipesForkResult result) {
        if (result.isProcessCrash() || result.isFatal() || result.isInitializationFailure()) throw unavailable();
        if (result.getStatus() != org.apache.tika.pipes.api.PipesResult.RESULT_STATUS.PARSE_SUCCESS) {
            String status = result.getStatus().name();
            if (status.contains("TIMEOUT") || status.contains("OOM") || status.contains("CRASH")
                    || status.equals("CLIENT_UNAVAILABLE_WITHIN_MS") || status.equals("PAYLOAD_LIMIT_EXCEEDED")
                    || status.equals("FETCH_EXCEPTION")) throw unavailable();
            throw invalid();
        }
    }

    static Path privateDirectory() throws IOException {
        Path directory = Files.createTempDirectory("nkw-attachment-");
        try {
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            } else {
                var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
                if (acl == null) throw new IOException("Private custody unavailable");
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(directory))
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            return directory;
        } catch (IOException failure) { removeDirectory(directory); throw failure; }
    }

    private static Path resource(Path directory, String name) throws IOException {
        Path output = directory.resolve(name);
        try (var source = AttachmentParserRuntime.class.getResourceAsStream("/attachments/" + name)) {
            if (source == null) throw new IOException("Parser configuration unavailable");
            Files.copy(source, output);
        }
        return output;
    }

    private static String childClasspath(Path directory) throws IOException {
        String classpath = System.getProperty("java.class.path");
        // A Boot nested-JAR URL is not a java launcher classpath. Materialize only
        // trusted parser dependencies from the immutable application artifact.
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry);
            if (!Files.isRegularFile(path) || !entry.endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getEntry("BOOT-INF/classes/") == null) continue;
                Path libraries = Files.createDirectory(directory.resolve("libraries"));
                int count = 0;
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var candidate = entries.nextElement();
                    String name = candidate.getName();
                    if (!name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) continue;
                    String filename = name.substring("BOOT-INF/lib/".length());
                    if (filename.contains("/") || filename.contains("\\") || !parserLibrary(filename)) continue;
                    if (++count > 64 || candidate.getSize() < 0 || candidate.getSize() > 32L * 1024 * 1024) {
                        throw new IOException("Unexpected parser artifact");
                    }
                    try (var input = jar.getInputStream(candidate)) { Files.copy(input, libraries.resolve(filename)); }
                }
                if (count == 0) throw new IOException("Parser libraries unavailable");
                return libraries + File.separator + "*";
            }
        }
        return classpath;
    }

    private static boolean parserLibrary(String name) {
        return List.of("tika-", "pdfbox-", "fontbox-", "commons-io-", "commons-logging-", "commonmark-",
                "slf4j-api-", "logback-", "log4j-", "jackson-core-", "jackson-databind-", "jackson-annotations-",
                "jackson-dataformat-smile-", "jackson-datatype-jsr310-", "pf4j-", "java-semver-", "xmpcore-",
                "metadata-extractor-", "vorbis-java-core-", "picocli-", "bcprov-jdk18on-")
                .stream().anyMatch(name::startsWith);
    }

    @Override public synchronized void close() {
        try { parser.close(); } catch (Exception ignored) { }
        finally { removeDirectory(directory); }
    }

    static void removeDirectory(Path ownedDirectory) {
        // This path is exclusively an application-created random directory, never user input.
        try (var entries = Files.walk(ownedDirectory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        } catch (IOException ignored) { /* Unreachable local garbage; no private diagnostic is logged. */ }
    }

    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    private static ApiFailureException invalid() { return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT); }
}
