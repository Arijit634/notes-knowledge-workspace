package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;
import org.notesknowledge.websupport.ApiFailureException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST")
class AttachmentForkResourceTest {
    @TempDir Path temporary;

    @Test void testOnlyFailureRegistryCanInitialize() throws Exception {
        var loader = org.apache.tika.config.loader.TikaLoader.load(
                Path.of(getClass().getResource("/attachments/failure-fixture.json").toURI()));
        assertThat(loader.loadParsers().getSupportedTypes(new org.apache.tika.parser.ParseContext()))
                .contains(org.apache.tika.mime.MediaType.application("pdf"));
    }

    @Test void actualCrashIsUnavailableAndTheNextParseRecovers() throws Exception {
        try (var parser = new PipesForkParser(configuration())) {
            unavailable(parser.parse(input(2)));
            assertThat(parser.parse(input(0)).isSuccess()).isTrue();
        }
    }

    @Test void actualOutOfMemoryIsContainedAndTheNextParseRecovers() throws Exception {
        try (var parser = new PipesForkParser(configuration())) {
            unavailable(parser.parse(input(3)));
            assertThat(parser.parse(input(0)).isSuccess()).isTrue();
        }
    }

    @Test void actualTimeoutIsContainedAndTheNextParseRecovers() throws Exception {
        try (var parser = new PipesForkParser(configuration())) {
            unavailable(parser.parse(input(1)));
            assertThat(parser.parse(input(0)).isSuccess()).isTrue();
        }
    }

    @Test void oversizedMetadataCannotEscapeTheIpcFrameBound() throws Exception {
        try (var parser = new PipesForkParser(configuration())) {
            unavailable(parser.parse(input(4)));
            assertThat(parser.parse(input(0)).isSuccess()).isTrue();
        }
    }

    @Test void extractedContentIsLimitedInsideTheChild() throws Exception {
        try (var parser = new PipesForkParser(configuration())) {
            var result = parser.parse(input(5));
            String content = result.getContent();
            assertThat(content == null ? 0 : content.length()).isLessThanOrEqualTo(256);
            assertThat(parser.parse(input(0)).isSuccess()).isTrue();
        }
    }

    @Test void concurrentRequestsUseOnlyOneForkWorker() throws Exception {
        Path first = input(6), second = Files.copy(first, temporary.resolve("synthetic-second.bin"));
        try (var parser = new PipesForkParser(configuration()); var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            var one = workers.submit(() -> { start.await(5, java.util.concurrent.TimeUnit.SECONDS); return parser.parse(first); });
            var two = workers.submit(() -> { start.await(5, java.util.concurrent.TimeUnit.SECONDS); return parser.parse(second); });
            var a = one.get(15, java.util.concurrent.TimeUnit.SECONDS); var b = two.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.isSuccess()).isTrue(); assertThat(b.isSuccess()).isTrue();
            assertThat(a.getMetadata().get("fixture-process")).isNotBlank().isEqualTo(b.getMetadata().get("fixture-process"));
        }
    }

    private void unavailable(PipesForkResult result) {
        assertThatThrownBy(() -> AttachmentParserRuntime.requireSuccess(result))
                .isInstanceOfSatisfying(ApiFailureException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
    }

    private Path input(int mode) throws Exception {
        byte[] bytes = "%PDF-1.4\nX".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        bytes[9] = (byte)mode;
        return Files.write(temporary.resolve("synthetic-" + mode + ".bin"), bytes);
    }

    private PipesForkParserConfig configuration() throws Exception {
        var config = new PipesForkParserConfig()
                .setUserConfigPath(Path.of(getClass().getResource("/attachments/failure-fixture.json").toURI()))
                .setPluginsDir(temporary.resolve("plugins"))
                .setNumClients(1).setMaxFilesPerProcess(25).setMaxEmbeddedCount(0).setWriteLimit(256)
                .setTimeoutLimits(new TimeoutLimits(15_000, 5_000))
                .setJvmArgs(List.of("-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:ActiveProcessorCount=2",
                        "-Djava.awt.headless=true", "-Dnkw.test.parser.child=true",
                        "-Dlogback.configurationFile=" + Path.of("src/main/resources/attachments/logback.xml").toAbsolutePath(),
                        "-Dlog4j.configurationFile=" + Path.of("src/main/resources/attachments/log4j2.xml").toAbsolutePath()));
        config.getPipesConfig().setMaxInlineBytes(0);
        config.getPipesConfig().setMaxIpcPayloadBytes(65_536);
        return config;
    }
}
