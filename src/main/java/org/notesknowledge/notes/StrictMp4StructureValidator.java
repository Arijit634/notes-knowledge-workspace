package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.notesknowledge.websupport.ApiFailureException;

/** Narrow progressive, single-video-track AVC Baseline container gate, not a codec decoder.
 * Fixed-size channel reads and capped table arrays only. Unknown structures fail closed.
 * The caller bounds/custodies the input before entry; Tika must independently agree.
 */
final class StrictMp4StructureValidator {
    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final int MAX_DEPTH = 8, MAX_BOXES = 512, MAX_TRACKS = 1, MAX_DESCRIPTIONS = 1;
    static final int MAX_SAMPLES = 18_000, MAX_CHUNKS = 18_000, MAX_TABLE_ENTRIES = 18_000;
    static final int MAX_DIMENSION = 4096;
    static final long MAX_PIXELS = 8_294_400, MAX_SECONDS = 300;
    private static final Set<String> BRANDS = Set.of("mp41", "mp42", "isom", "iso2", "avc1");
    private static final Set<Integer> AVC_LEVELS = Set.of(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52);

    record Video(int width, int height, double durationSeconds) { }
    private record Box(String type, long payload, long end) {
        long length() { return end - payload; }
    }
    private record Range(long start, long end) { }
    private record Timing(long scale, long ticks) { }
    private record ChunkMapping(int first, int samples) { }

    Video validate(Path input) {
        try (var channel = FileChannel.open(input, StandardOpenOption.READ)) {
            if (channel.size() < 8 || channel.size() > MAX_BYTES) throw invalid();
            return new Reader(channel).validate();
        } catch (ArithmeticException e) {
            throw invalid();
        } catch (IOException e) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    private static ApiFailureException invalid() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
    }

    private static final class Reader {
        private final FileChannel channel;
        private final ByteBuffer scratch = ByteBuffer.allocate(8);
        private int visited;
        private final List<Range> media = new ArrayList<>();

        Reader(FileChannel channel) { this.channel = channel; }

        Video validate() throws IOException {
            List<Box> top = boxes(0, channel.size(), 1);
            Box ftyp = null, moov = null;
            for (Box box : top) {
                switch (box.type()) {
                    case "ftyp" -> { if (ftyp != null) throw invalid(); ftyp = box; }
                    case "moov" -> { if (moov != null) throw invalid(); moov = box; }
                    case "mdat" -> { if (box.length() == 0) throw invalid(); media.add(new Range(box.payload(), box.end())); }
                    case "free" -> { /* Harmless padding: framing checked, payload never read. */ }
                    default -> throw invalid();
                }
            }
            if (ftyp == null || moov == null || media.isEmpty() || top.getFirst() != ftyp) throw invalid();
            brands(ftyp);
            Map<String, Box> movie = children(moov, 2, Set.of("mvhd", "trak"));
            Timing movieTime = timing(required(movie, "mvhd"), 100);
            Map<String, Box> track = children(required(movie, "trak"), 3, Set.of("tkhd", "mdia"));
            Box tkhd = required(track, "tkhd");
            exact(tkhd, 84);
            long flags = u32(tkhd, 0);
            if (flags != 3 && flags != 7 || u32(tkhd, 12) != 1 || u32(tkhd, 20) != movieTime.ticks()) throw invalid();
            // No track transform/rotation: sample and display dimensions must agree.
            for (int i = 0; i < 9; i++) {
                long expected = i == 0 || i == 4 ? 0x10000L : i == 8 ? 0x40000000L : 0;
                if (u32(tkhd, 40 + i * 4L) != expected) throw invalid();
            }
            Map<String, Box> mdia = children(required(track, "mdia"), 4, Set.of("mdhd", "hdlr", "minf"));
            Timing mediaTime = timing(required(mdia, "mdhd"), 24);
            Box handler = required(mdia, "hdlr");
            if (handler.length() < 24 || u32(handler, 0) != 0 || u32(handler, 4) != 0 || !type(handler, 8).equals("vide")) throw invalid();
            Map<String, Box> minf = children(required(mdia, "minf"), 5, Set.of("vmhd", "dinf", "stbl"));
            Box vmhd = required(minf, "vmhd");
            exact(vmhd, 12);
            if (u32(vmhd, 0) != 1) throw invalid();
            selfContained(required(minf, "dinf"));
            Map<String, Box> tables = children(required(minf, "stbl"), 6,
                    Set.of("stsd", "stts", "stsc", "stsz", "stco", "stss"));
            Video video = description(required(tables, "stsd"));
            if (u32(tkhd, 76) != ((long) video.width() << 16) || u32(tkhd, 80) != ((long) video.height() << 16)) throw invalid();
            long[] sizes = sizes(required(tables, "stsz"));
            sampleTiming(required(tables, "stts"), sizes.length, mediaTime);
            if (Math.multiplyExact(mediaTime.ticks(), movieTime.scale()) != Math.multiplyExact(movieTime.ticks(), mediaTime.scale())) throw invalid();
            double seconds = (double) mediaTime.ticks() / mediaTime.scale();
            if (seconds <= 0 || seconds > MAX_SECONDS || sizes.length / seconds > 60) throw invalid();
            ranges(required(tables, "stsc"), required(tables, "stco"), sizes);
            if (tables.containsKey("stss")) syncSamples(tables.get("stss"), sizes.length);
            return new Video(video.width(), video.height(), seconds);
        }

        private void brands(Box box) throws IOException {
            if (box.length() < 12 || box.length() > 72 || (box.length() - 8) % 4 != 0) throw invalid();
            String major = type(box, 0);
            if (!major.equals("mp41") && !major.equals("mp42")) throw invalid();
            for (long i = 8; i < box.length(); i += 4) if (!BRANDS.contains(type(box, i))) throw invalid();
        }

        private Timing timing(Box box, int length) throws IOException {
            exact(box, length);
            if (u32(box, 0) != 0) throw invalid(); // Version 0 only; no 64-bit/edit-list timing.
            long scale = u32(box, 12), ticks = u32(box, 16);
            if (scale == 0 || scale > 1_000_000 || ticks == 0 || ticks > Math.multiplyExact(scale, MAX_SECONDS)) throw invalid();
            return new Timing(scale, ticks);
        }

        private void selfContained(Box dinf) throws IOException {
            Box dref = required(children(dinf, 6, Set.of("dref")), "dref");
            if (dref.length() != 20 || u32(dref, 0) != 0 || u32(dref, 4) != 1) throw invalid();
            List<Box> references = boxes(dref.payload() + 8, dref.end(), 7);
            if (references.size() != 1 || !references.getFirst().type().equals("url ")) throw invalid();
            Box reference = references.getFirst();
            exact(reference, 4);
            if (u32(reference, 0) != 1) throw invalid(); // Self-contained flag, no URL/path payload.
        }

        private Video description(Box stsd) throws IOException {
            if (stsd.length() < 8 || u32(stsd, 0) != 0 || u32(stsd, 4) != MAX_DESCRIPTIONS) throw invalid();
            List<Box> entries = boxes(stsd.payload() + 8, stsd.end(), 7);
            if (entries.size() != 1) throw invalid();
            Box entry = entries.getFirst();
            if (!entry.type().equals("avc1") || entry.length() < 78) throw invalid();
            for (int i = 0; i < 6; i++) if (u8(entry, i) != 0) throw invalid();
            if (u16(entry, 6) != 1) throw invalid();
            for (int i = 8; i < 24; i += 4) if (u32(entry, i) != 0) throw invalid();
            int width = u16(entry, 24), height = u16(entry, 26);
            if (width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                    || (long) width * height > MAX_PIXELS || u16(entry, 40) != 1
                    || u16(entry, 74) != 24 || u16(entry, 76) != 65535) throw invalid();
            List<Box> extensions = boxes(entry.payload() + 78, entry.end(), 8);
            if (extensions.size() != 1 || !extensions.getFirst().type().equals("avcC")) throw invalid();
            avcConfiguration(extensions.getFirst());
            return new Video(width, height, 0);
        }

        private void avcConfiguration(Box config) throws IOException {
            if (config.length() < 11 || config.length() > 8192 || u8(config, 0) != 1
                    || u8(config, 1) != 66 || (u8(config, 2) & 3) != 0 || !AVC_LEVELS.contains(u8(config, 3))
                    || u8(config, 4) != 255 || u8(config, 5) != 225) throw invalid();
            // Exactly one SPS and one PPS. Check length framing only, never NAL contents.
            int spsLength = u16(config, 6);
            if (spsLength < 1 || spsLength > 4096) throw invalid();
            long ppsCount = Math.addExact(8, spsLength);
            if (u8(config, ppsCount) != 1) throw invalid();
            int ppsLength = u16(config, ppsCount + 1);
            if (ppsLength < 1 || ppsLength > 4096 || config.length() != ppsCount + 3 + ppsLength) throw invalid();
        }

        private long[] sizes(Box box) throws IOException {
            if (box.length() < 12 || u32(box, 0) != 0) throw invalid();
            long fixed = u32(box, 4);
            int count = bounded(u32(box, 8), MAX_SAMPLES);
            exact(box, Math.addExact(12, fixed == 0 ? Math.multiplyExact(4L, count) : 0));
            long[] sizes = new long[count];
            for (int i = 0; i < count; i++) {
                sizes[i] = fixed == 0 ? u32(box, 12 + i * 4L) : fixed;
                if (sizes[i] == 0 || sizes[i] > MAX_BYTES) throw invalid();
            }
            return sizes;
        }

        private void sampleTiming(Box box, int sampleCount, Timing expected) throws IOException {
            int entries = tableCount(box, 8, MAX_TABLE_ENTRIES);
            long count = 0, ticks = 0;
            for (int i = 0; i < entries; i++) {
                long samples = u32(box, 8 + i * 8L), delta = u32(box, 12 + i * 8L);
                if (samples == 0 || samples > MAX_SAMPLES || delta == 0) throw invalid();
                count = Math.addExact(count, samples);
                ticks = Math.addExact(ticks, Math.multiplyExact(samples, delta));
            }
            if (count != sampleCount || ticks != expected.ticks()) throw invalid();
        }

        private void ranges(Box stsc, Box stco, long[] sizes) throws IOException {
            int chunks = tableCount(stco, 4, MAX_CHUNKS), entries = tableCount(stsc, 12, MAX_TABLE_ENTRIES);
            List<ChunkMapping> mapping = new ArrayList<>(entries);
            int previous = 0;
            for (int i = 0; i < entries; i++) {
                int first = bounded(u32(stsc, 8 + i * 12L), chunks);
                int samples = bounded(u32(stsc, 12 + i * 12L), MAX_SAMPLES);
                if (first <= previous || i == 0 && first != 1 || u32(stsc, 16 + i * 12L) != 1) throw invalid();
                mapping.add(new ChunkMapping(first, samples)); previous = first;
            }
            int entry = 0, sample = 0;
            List<Range> custody = new ArrayList<>(chunks);
            boolean[] referenced = new boolean[media.size()];
            for (int chunk = 1; chunk <= chunks; chunk++) {
                if (entry + 1 < entries && chunk == mapping.get(entry + 1).first()) entry++;
                int number = mapping.get(entry).samples();
                if (number > sizes.length - sample) throw invalid();
                long bytes = 0;
                for (int i = 0; i < number; i++) bytes = Math.addExact(bytes, sizes[sample++]);
                long start = u32(stco, 8 + (chunk - 1) * 4L), end = Math.addExact(start, bytes);
                boolean contained = false;
                for (int i = 0; i < media.size(); i++) {
                    Range mdat = media.get(i);
                    if (start >= mdat.start() && end <= mdat.end()) { contained = true; referenced[i] = true; break; }
                }
                if (!contained) throw invalid();
                custody.add(new Range(start, end));
            }
            if (sample != sizes.length) throw invalid();
            for (boolean used : referenced) if (!used) throw invalid();
            custody.sort(Comparator.comparingLong(Range::start));
            for (int i = 1; i < custody.size(); i++) if (custody.get(i).start() < custody.get(i - 1).end()) throw invalid();
        }

        private void syncSamples(Box box, int samples) throws IOException {
            int entries = tableCount(box, 4, MAX_TABLE_ENTRIES), previous = 0;
            for (int i = 0; i < entries; i++) {
                int sample = bounded(u32(box, 8 + i * 4L), samples);
                if (sample <= previous) throw invalid();
                previous = sample;
            }
        }

        private int tableCount(Box box, int entryBytes, int cap) throws IOException {
            if (box.length() < 8 || u32(box, 0) != 0) throw invalid();
            int count = bounded(u32(box, 4), cap);
            exact(box, Math.addExact(8, Math.multiplyExact((long) entryBytes, count)));
            return count;
        }

        private List<Box> boxes(long start, long end, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw invalid();
            List<Box> result = new ArrayList<>();
            for (long position = start; position < end;) {
                if (end - position < 8 || ++visited > MAX_BOXES) throw invalid();
                long length = number(position, 4);
                if (length < 8) throw invalid(); // Explicitly rejects size-zero and extended-size boxes.
                long next = Math.addExact(position, length);
                if (next > end) throw invalid();
                result.add(new Box(fourcc(position + 4), position + 8, next));
                position = next;
            }
            return result;
        }

        private Map<String, Box> children(Box parent, int depth, Set<String> allowed) throws IOException {
            Map<String, Box> result = new HashMap<>();
            for (Box box : boxes(parent.payload(), parent.end(), depth)) {
                if (!allowed.contains(box.type()) || result.putIfAbsent(box.type(), box) != null) throw invalid();
            }
            return result;
        }

        private static Box required(Map<String, Box> boxes, String type) {
            Box box = boxes.get(type);
            if (box == null) throw invalid();
            return box;
        }
        private static int bounded(long number, int cap) {
            if (number < 1 || number > cap) throw invalid();
            return (int) number;
        }
        private static void exact(Box box, long length) { if (box.length() != length) throw invalid(); }
        private long u32(Box box, long offset) throws IOException { return field(box, offset, 4); }
        private int u16(Box box, long offset) throws IOException { return (int) field(box, offset, 2); }
        private int u8(Box box, long offset) throws IOException { return (int) field(box, offset, 1); }
        private long field(Box box, long offset, int bytes) throws IOException {
            if (offset < 0 || offset > box.length() - bytes) throw invalid();
            return number(Math.addExact(box.payload(), offset), bytes);
        }
        private String type(Box box, long offset) throws IOException {
            return code((int) u32(box, offset));
        }
        private String fourcc(long offset) throws IOException { return code((int) number(offset, 4)); }
        private static String code(int value) {
            return new String(new char[]{(char)(value >>> 24 & 255), (char)(value >>> 16 & 255),
                    (char)(value >>> 8 & 255), (char)(value & 255)});
        }
        private long number(long offset, int bytes) throws IOException {
            scratch.clear().limit(bytes);
            while (scratch.hasRemaining()) {
                int read = channel.read(scratch, offset + scratch.position());
                if (read <= 0) throw invalid();
            }
            scratch.flip();
            return switch (bytes) {
                case 4 -> Integer.toUnsignedLong(scratch.getInt());
                case 2 -> Short.toUnsignedInt(scratch.getShort());
                case 1 -> Byte.toUnsignedInt(scratch.get());
                default -> throw new IllegalArgumentException("Unsupported fixed-size field");
            };
        }
    }
}
