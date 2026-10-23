package org.notesknowledge.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable decoded disclosure. The digest binds decoded values, not JSON formatting. */
record ProcessingDisclosure(UUID id, String code, long version, String revision, Instant effectiveAt,
        String provider, String tier, String region, List<String> categories, String content, String fingerprint) {
    static final int MAX_FILE_BYTES = 65_536;
    static final long MAX_VERSION = 9_007_199_254_740_991L;
    static final List<String> TASKS = List.of("groundedGeneration", "mediaUnderstanding", "queryEmbedding",
            "sourceEmbedding", "structuredExtraction");
    private static final Set<String> FIELDS = Set.of("schemaVersion", "processingPolicyId", "policyCode",
            "policyVersion", "disclosureRevision", "effectiveAt", "provider", "tier", "region",
            "providerProcessingCategories", "disclosureContent");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    ProcessingDisclosure { categories = List.copyOf(categories); }
    @Override public String toString() { return "ProcessingDisclosure[REDACTED]"; }

    static ProcessingDisclosure decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) throw unavailable();
            String input = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (input.charAt(0) == '\ufeff') throw unavailable();
            JsonNode root = JSON.readTree(input);
            if (root == null || !root.isObject() || root.size() != FIELDS.size()
                    || root.properties().stream().anyMatch(e -> !FIELDS.contains(e.getKey()))
                    || !root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToLong()
                    || root.path("schemaVersion").longValue() != 1)
                throw unavailable();
            String id = text(root, "processingPolicyId"), code = text(root, "policyCode");
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id) || !code.matches("[a-z][a-z0-9_.-]{0,63}")) throw unavailable();
            JsonNode number = root.path("policyVersion");
            if (!number.isIntegralNumber() || !number.canConvertToLong()) throw unavailable();
            long version = number.longValue();
            String revision = text(root, "disclosureRevision"), date = text(root, "effectiveAt");
            Instant effective = Instant.parse(date);
            if (version < 1 || version > MAX_VERSION || !revision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                    || !effective.toString().equals(date)) throw unavailable();
            String provider = binding(root, "provider"), tier = binding(root, "tier"), region = binding(root, "region");
            JsonNode tasks = root.path("providerProcessingCategories");
            if (!tasks.isArray() || tasks.isEmpty() || tasks.size() > TASKS.size()) throw unavailable();
            List<String> categories = new ArrayList<>();
            for (JsonNode value : tasks) {
                if (!value.isString() || !TASKS.contains(value.stringValue())) throw unavailable();
                categories.add(value.stringValue());
            }
            if (!categories.equals(categories.stream().distinct().sorted().toList())) throw unavailable();
            String content = text(root, "disclosureContent");
            if (content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > 32_768
                    || content.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF
                        || Character.isISOControl(c) && c != '\n' && c != '\t')) throw unavailable();
            var fields = new ArrayList<>(List.of("1", id, code, Long.toString(version), revision, date,
                    provider, tier, region, Integer.toString(categories.size())));
            fields.addAll(categories); fields.add(content);
            var canonical = new ByteArrayOutputStream();
            canonical.writeBytes("nkw-processing-disclosure-v1\n".getBytes(StandardCharsets.US_ASCII));
            for (String value : fields) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                canonical.writeBytes((encoded.length + ":").getBytes(StandardCharsets.US_ASCII));
                canonical.writeBytes(encoded); canonical.write('\n');
            }
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
            return new ProcessingDisclosure(uuid, code, version, revision, effective, provider, tier, region,
                    categories, content, fingerprint);
        } catch (Exception invalid) {
            // No JSON/parser/path/content exception (or binding value) escapes this boundary.
            throw unavailable();
        }
    }

    private static String text(JsonNode root, String name) {
        if (!root.path(name).isString()) throw unavailable();
        return root.path(name).stringValue();
    }
    private static String binding(JsonNode root, String name) {
        String value = text(root, name);
        if (!value.matches("[a-z][a-z0-9_-]{0,31}")) throw unavailable();
        return value;
    }
    static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
}
