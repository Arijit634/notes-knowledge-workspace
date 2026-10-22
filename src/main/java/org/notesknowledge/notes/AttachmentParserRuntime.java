package org.notesknowledge.notes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
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
        this(false);
    }
    AttachmentParserRuntime(boolean extractPdfText) {
        Path created = null;
        PipesForkParser started = null;
        try {
            created = privateDirectory();
            Path json = resource(created, extractPdfText?"pdf-derivation-parser.json":"parser.json");
            Path logback = resource(created, "logback.xml");
            Path log4j = resource(created, "log4j2.xml");
            var arguments = new ArrayList<>(List.of("-Xmx256m", "-XX:MaxDirectMemorySize=64m",
                    "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                    "-Djava.io.tmpdir=" + created,
                    "-Dlogback.configurationFile=" + logback, "-Dlog4j.configurationFile=" + log4j,
                    "-cp", compactClasspath(created, childClasspath(created))));
            var configuration = new PipesForkParserConfig().setUserConfigPath(json)
                    .setPluginsDir(created.resolve("plugins"))
                    .setJavaPath(Path.of(System.getProperty("java.home"), "bin",
                            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString())
                    .setNumClients(1).setMaxFilesPerProcess(25).setWriteLimit(extractPdfText?65536:256).setMaxEmbeddedCount(0)
                    .setTimeoutLimits(new TimeoutLimits(15_000, 5_000)).setJvmArgs(arguments);
            configuration.getPipesConfig().setMaxInlineBytes(0);
            configuration.getPipesConfig().setMaxIpcPayloadBytes(extractPdfText?262144:MAX_IPC_BYTES);
            if(extractPdfText)configuration.setHandlerType(org.apache.tika.sax.BasicContentHandlerFactory.HANDLER_TYPE.XML);
            started = new PipesForkParser(configuration);
            directory = created;
            parser = started;
        } catch (Exception failure) {
            if (started != null) try { started.close(); } catch (Exception ignored) { }
            if (created != null) removeDirectory(created);
            throw unavailable(); // Never attach parser-controlled diagnostics as a cause.
        }
    }

    record PdfExtraction(String xhtml,String emptyPages) { }
    synchronized PdfExtraction extractPdfXhtml(Path ownedInput) {
        try {
            var result=parser.parse(ownedInput);requireSuccess(result);
            if(result.getMetadataList().size()!=1||!"application/pdf".equals(result.getMetadata().get("Content-Type")))throw invalid();
            if("true".equalsIgnoreCase(result.getMetadata().get(TikaCoreProperties.WRITE_LIMIT_REACHED)))throw invalid();
            String content=result.getContent();
            if(content==null||content.length()>65536)throw invalid();
            String emptyPages=result.getMetadata().get("nkw:pdf-empty-v1");
            Integer count=result.getMetadata().getInt(PagedText.N_PAGES);
            if(count==null||emptyPages==null||count<1||count>100||emptyPages.length()!=count||!emptyPages.matches("[EU]+"))throw invalid();
            return new PdfExtraction(content,emptyPages);
        } catch(ApiFailureException failure){throw failure;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failure){throw unavailable();}
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

    static String childClasspath(Path directory) throws IOException {
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
                    Path destination = parserLibraryDestination(libraries, candidate);
                    String filename = name.substring("BOOT-INF/lib/".length());
                    if (!parserLibrary(filename)) continue;
                    if (++count > 64 || candidate.getSize() < 0 || candidate.getSize() > 32L * 1024 * 1024) {
                        throw new IOException("Unexpected parser artifact");
                    }
                    try (var input = jar.getInputStream(candidate)) { Files.copy(input, destination); }
                }
                if (count == 0) throw new IOException("Parser libraries unavailable");
                // Trusted, fixed class resource only: no input-selected class extraction.
                Path trustedClasses=Files.createDirectory(directory.resolve("classes"));
                for(String evidenceClass:List.of("org/notesknowledge/notes/PdfEvidenceParser.class",
                        "org/notesknowledge/notes/PdfEvidenceParser$1.class",
                        "org/notesknowledge/notes/PdfPageRasterWorker.class",
                        "org/notesknowledge/notes/PdfPageRasterWorker$BoundedPng.class")) {
                    Path destination=trustedClasses.resolve(evidenceClass);
                    Files.createDirectories(destination.getParent());
                    try(var input=AttachmentParserRuntime.class.getResourceAsStream("/"+evidenceClass)) {
                        if(input==null)throw new IOException("Parser implementation unavailable");
                        Files.copy(input,destination);
                    }
                }
                copyParserRegistry(jar,trustedClasses);
                return trustedClasses+File.pathSeparator+libraries+File.separator+"*";
            }
        }
        return classpath;
    }

    static String compactClasspath(Path directory, String classpath) throws IOException {
        // A development dependency graph can exceed Windows' CreateProcess limit.
        // A manifest-only JAR preserves the trusted classpath without copying code,
        // changing parser permissions, or exposing document-selected launch options.
        if (classpath.length() <= 16_000) return classpath;
        var entries = new ArrayList<String>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (entry.endsWith(File.separator + "*") || entry.endsWith("/*")) {
                Path libraries = Path.of(entry.substring(0, entry.length() - 1));
                try (var paths = Files.list(libraries)) {
                    for (Path path : paths.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                        entries.add(path.toAbsolutePath().toUri().toASCIIString());
                    }
                }
            } else entries.add(Path.of(entry).toAbsolutePath().toUri().toASCIIString());
        }
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", entries));
        Path launchJar = directory.resolve("parser-classpath.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(launchJar,
                java.nio.file.StandardOpenOption.CREATE_NEW), manifest)) { }
        return launchJar.toAbsolutePath().toString();
    }

    static void copyParserRegistry(JarFile jar,Path trustedClasses) throws IOException {
        String registry="META-INF/tika/parsers.idx";
        // Boot repackaging retains META-INF registries at the archive root.
        // Also accept the ordinary classes layout; neither path is input-selected.
        var entry=jar.getJarEntry(registry);
        if(entry==null)entry=jar.getJarEntry("BOOT-INF/classes/"+registry);
        if(entry==null||entry.isDirectory()||entry.getSize()<0||entry.getSize()>4096) {
            throw new IOException("Parser registry unavailable");
        }
        Path destination=trustedClasses.resolve(registry);
        Files.createDirectories(destination.getParent());
        try(var input=jar.getInputStream(entry)){Files.copy(input,destination);}
    }

    static Path parserLibraryDestination(Path libraries, JarEntry entry) throws IOException {
        String name = entry.getName();
        if (entry.isDirectory() || !name.startsWith("BOOT-INF/lib/")) {
            throw new IOException("Unexpected parser artifact");
        }
        String filename = name.substring("BOOT-INF/lib/".length());
        // Reject both platforms' separators and Windows drive/stream forms on every host.
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || filename.contains(":") || filename.equals(".") || filename.equals("..")) {
            throw new IOException("Unexpected parser artifact");
        }
        try {
            Path relative = Path.of(filename);
            if (relative.isAbsolute() || relative.getNameCount() != 1) {
                throw new IOException("Unexpected parser artifact");
            }
            Path root = libraries.toAbsolutePath().normalize();
            Path destination = root.resolve(relative).normalize();
            if (!destination.startsWith(root) || !root.equals(destination.getParent())) {
                throw new IOException("Unexpected parser artifact");
            }
            return destination;
        } catch (InvalidPathException failure) {
            throw new IOException("Unexpected parser artifact");
        }
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
