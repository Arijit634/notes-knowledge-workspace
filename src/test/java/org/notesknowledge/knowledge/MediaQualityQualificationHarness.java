package org.notesknowledge.knowledge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.notesknowledge.notes.MeaningfulMediaFixtures;
import tools.jackson.databind.ObjectMapper;

/** Opt-in, test-classpath-only media recorder. Never a production bean or alternate provider.
 * Install around the actual MediaUnderstandingPort in an isolated synthetic qualification runner.
 * Permits, exact child approvals, final SDK validation and currentness remain production-owned.
 * No credential/file-input option, retry, stored vector, raw provider response or quality claim.
 */
final class MediaQualityQualificationHarness implements MediaUnderstandingPort, AutoCloseable {
    record Consent(boolean explicitlyApproved, boolean confirmedFreeTier, int requests,
            long estimatedRequestBytes, long reservedOutputTokens, int minimumIntervalSeconds) {
        void require() {
            if(!explicitlyApproved||!confirmedFreeTier||requests<1||requests>12
                    ||estimatedRequestBytes<1||estimatedRequestBytes>64L*1024*1024
                    ||reservedOutputTokens<8192||reservedOutputTokens>12L*8192
                    ||minimumIntervalSeconds<15)throw new IllegalStateException("Live qualification not authorized");
        }
    }
    record Fixture(String id, String mediaType, byte[] bytes) {
        Fixture { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "FrozenSyntheticMedia["+id+"]";}
    }
    record Event(int sequence, String key, String fixture, String state, long estimatedBytes,
            long reservedOutputTokens, long elapsedMillis, Map<String,Object> checks) { }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MediaUnderstandingPort delegate;
    private final Consent consent;
    private final Clock clock;
    private final Path directory;
    private final FileChannel channel;
    private final java.nio.channels.FileLock lock;
    private final Map<String,Fixture> corpus=new HashMap<>();
    private final List<Event> events=new ArrayList<>();
    private Instant last;
    private boolean stopped,closed;

    static List<Fixture> frozenCorpus() throws Exception {
        var fixtures=new ArrayList<Fixture>();
        for(String kind:List.of("image","mixed","audio","tones","video")) {
            var file=kind.equals("image")?MeaningfulMediaFixtures.acceptedImage(kind):MeaningfulMediaFixtures.media(kind);
            byte[] bytes=file.getBytes();
            if(kind.equals("mixed"))bytes=frozenPdf(bytes);
            fixtures.add(new Fixture(kind,file.getContentType(),bytes));
        }
        fixtures.add(new Fixture("long-video","video/mp4",MeaningfulMediaFixtures.videoSamples(40,1000)));
        fixtures.add(new Fixture("diagram-image","image/png",MeaningfulMediaFixtures.acceptedImage("diagram-image").getBytes()));
        fixtures.add(new Fixture("diagram-pdf","application/pdf",frozenPdf(org.notesknowledge.notes.QualityMediaFixtures.pdf(1,true,false).getBytes())));
        fixtures.add(new Fixture("text-pdf","application/pdf",frozenPdf(MeaningfulMediaFixtures.media("pdf").getBytes())));
        fixtures.add(new Fixture("scan-pdf","application/pdf",frozenPdf(MeaningfulMediaFixtures.media("scan").getBytes())));
        return List.copyOf(fixtures);
    }
    private static byte[] frozenPdf(byte[] bytes)throws Exception {
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(bytes);var output=new java.io.ByteArrayOutputStream()) {
            // Pin creation-time trailer IDs only for fictional fixtures, not production media.
            var ids=new org.apache.pdfbox.cos.COSArray();
            ids.add(new org.apache.pdfbox.cos.COSString("frozen-media-quality-v1"));
            ids.add(new org.apache.pdfbox.cos.COSString("frozen-media-quality-v1"));
            pdf.getDocument().getTrailer().setItem(org.apache.pdfbox.cos.COSName.ID,ids);pdf.save(output);return output.toByteArray();
        }
    }

    MediaQualityQualificationHarness(Path directory, Consent consent, MediaUnderstandingPort delegate,
            Clock clock) throws Exception {
        consent.require(); // No files, Spring startup or provider calls before explicit opt-in.
        this.directory=directory;this.consent=consent;this.delegate=Objects.requireNonNull(delegate);this.clock=clock;
        for(var fixture:frozenCorpus())corpus.put(TransformedMediaIdentity.digest(fixture.bytes()),fixture);
        Files.createDirectories(directory);
        channel=FileChannel.open(directory.resolve("qualification.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        java.nio.channels.FileLock acquired;
        try {acquired=channel.tryLock();if(acquired==null)throw new IllegalStateException("Qualification already running");}
        catch(IOException|RuntimeException failure){channel.close();throw failure;}
        lock=acquired;
        try {
            var identity=new TreeMap<String,Object>();identity.put("consent",consent);
            identity.put("corpus",corpus.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getValue().id(),Map.Entry::getKey)));
            var expected=JSON.readTree(JSON.writeValueAsBytes(identity));Path header=directory.resolve("identity.json");
            if(!Files.exists(header))QualificationJournal.forceCreate(header,JSON.writeValueAsBytes(identity));
            else if(!JSON.readTree(Files.readAllBytes(header)).equals(expected))throw new IllegalStateException("Qualification identity or budget changed");
            try(var paths=Files.list(directory)) {
            for(Path path:paths.filter(p->p.getFileName().toString().matches("[0-9]{6}\\.json")).sorted().toList()) {
                var event=JSON.readValue(Files.readAllBytes(path),Event.class);
                if(event.sequence()!=events.size()+1||!path.getFileName().toString().equals("%06d.json".formatted(event.sequence()))
                        ||!Set.of("reserved","completed","failed").contains(event.state()))throw new IllegalStateException("Invalid qualification journal");
                events.add(event);
            }
            }
            // Unknown, failed or interrupted outcomes are charged. A fresh process is not a retry license.
            if(events.stream().anyMatch(e->e.state().equals("failed")||e.state().equals("reserved")
                    &&events.stream().noneMatch(c->c.key().equals(e.key())&&c.state().equals("completed"))))stopped=true;
        } catch(IOException|RuntimeException failure){lock.release();channel.close();throw failure;}
        // Restart never bypasses RPM spacing; no sleeping while a short-lived permit ages.
        if(!events.isEmpty())last=clock.instant();
    }
    @Override public boolean available(){return !closed&&!stopped&&delegate.available();}
    @Override public synchronized List<DerivedSegment> describe(AiProcessingGate.SourceAiPermit permit,
            byte[] bytes,MediaUnderstandingRequest request) {
        permit.requireGoogleDispatch();
        var fixture=requireFixture(bytes);
        if(!fixture.mediaType().equals(request.mediaType()))throw denied();
        return dispatch(fixture,permit.source().expected(),null,bytes,request,()->delegate.describe(permit,bytes,request));
    }
    @Override public synchronized List<DerivedSegment> describeChild(AiProcessingGate.SourceAiPermit permit,
            TransformedMediaIdentity child,byte[] original,byte[] bytes,MediaUnderstandingRequest request) {
        // No approval is synthesized from a corpus match. The real source/child permit must already exist.
        permit.requireGoogleChildDispatch(child,original,bytes);
        var fixture=requireFixture(original);
        if(child==null||!fixture.mediaType().equals(child.originalType())||!child.childType().equals(request.mediaType()))throw denied();
        child.requireOriginalBytes(original);child.requireBytes(bytes);
        return dispatch(fixture,permit.source().expected(),child.approvalFingerprint(),bytes,request,
            ()->delegate.describeChild(permit,child,original,bytes,request));
    }
    private Fixture requireFixture(byte[] bytes) {
        var fixture=corpus.get(TransformedMediaIdentity.digest(bytes));
        if(fixture==null)throw denied();return fixture;
    }
    private List<DerivedSegment> dispatch(Fixture fixture,org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected source,
            String child,byte[] bytes,MediaUnderstandingRequest request,java.util.function.Supplier<List<DerivedSegment>> call) {
        if(closed||stopped||last!=null&&clock.instant().isBefore(last.plusSeconds(consent.minimumIntervalSeconds())))throw denied();
        request.requireInlineBudget(bytes.length,GoogleDerivationAdapters.mediaInstruction(request),GoogleDerivationAdapters.mediaSchema());
        long estimate=4L*((bytes.length+2L)/3)+MediaUnderstandingRequest.ENVELOPE_RESERVE_BYTES
            +GoogleDerivationAdapters.mediaInstruction(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            +GoogleDerivationAdapters.mediaSchema().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String key=QualificationJournal.hash(ProviderDispatchPolicy.fingerprint(source)+"\n"+Objects.toString(child,"original")
            +"\n"+TransformedMediaIdentity.digest(bytes)+"\n"+request.mediaType()+"\n"+request.firstPage()+"\n"+request.lastPage()+"\n"+request.durationSeconds());
        var reservations=events.stream().filter(e->e.state().equals("reserved")).toList();
        if(events.stream().anyMatch(e->e.key().equals(key))||reservations.size()>=consent.requests()
                ||reservations.stream().mapToLong(Event::estimatedBytes).sum()+estimate>consent.estimatedRequestBytes()
                ||reservations.stream().mapToLong(Event::reservedOutputTokens).sum()+8192>consent.reservedOutputTokens())throw denied();
        append(key,fixture.id(),"reserved",estimate,8192,0,Map.of("rawMediaBytes",bytes.length));
        last=clock.instant();long started=System.nanoTime();
        try {
            var result=List.copyOf(call.get());
            append(key,fixture.id(),"completed",0,0,(System.nanoTime()-started)/1_000_000,checks(fixture.id(),result));
            return result;
        } catch(RuntimeException failure) {
            stopped=true;
            append(key,fixture.id(),"failed",0,0,(System.nanoTime()-started)/1_000_000,
                Map.of("outcome",failure instanceof DerivationFailure d?d.category.name():"unclassified_failure"));
            if(failure instanceof DerivationFailure)throw failure;
            throw new IllegalStateException("Qualification provider failure"); // No raw external message/cause.
        } finally {
            // Errors are not swallowed. If a caller survives one, the uncertain reservation
            // still closes this recorder as well as a subsequently restarted process.
            if(events.getLast().state().equals("reserved"))stopped=true;
        }
    }
    private void append(String key,String fixture,String state,long bytes,long tokens,long elapsed,Map<String,Object> checks) {
        var event=new Event(events.size()+1,key,fixture,state,bytes,tokens,elapsed,checks);
        try {
            QualificationJournal.forceCreate(directory.resolve("%06d.json".formatted(event.sequence())),JSON.writeValueAsBytes(event));
            events.add(event);
        } catch(IOException failure){stopped=true;throw new IllegalStateException("Qualification journal unavailable");}
    }
    static Map<String,Object> checks(String fixture,List<DerivedSegment> segments) {
        String text=segments.stream().map(DerivedSegment::text).collect(java.util.stream.Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        var checks=new LinkedHashMap<String,Object>();checks.put("segments",segments.size());
        checks.put("claimFaithfulness","requires independent judgment; keyword checks are not entailment");
        checks.put("actualSerializedRequestBytes","not exposed by port; estimatedBytes is conservative envelope accounting");
        checks.put("actualProviderTokens","not exposed by port; reservedOutputTokens is a ceiling, not measured usage");
        if(Set.of("image","video","long-video","mixed").contains(fixture)) {
            checks.put("ocrAster",text.contains("aster"));checks.put("blueAndRedMentioned",text.contains("blue")&&text.contains("red"));
            checks.put("regionAndRelationshipAccuracy","independent judgment required");
        }
        if(fixture.equals("mixed"))checks.put("scannedPageAttribution",segments.stream().filter(s->s.text().toLowerCase(Locale.ROOT).contains("aster")).allMatch(s->Integer.valueOf(3).equals(s.page()))
            &&segments.stream().anyMatch(s->Integer.valueOf(3).equals(s.page())&&s.text().toLowerCase(Locale.ROOT).contains("aster")));
        if(fixture.startsWith("diagram-")) {
            checks.put("ocrEquipmentReserve",text.contains("740")&&text.contains("equipment")&&text.contains("reserve"));
            checks.put("arrowRelationshipAccuracy","independent judgment required; arrow from blue square to red circle");
            if(fixture.equals("diagram-pdf"))checks.put("diagramPageAttribution",!segments.isEmpty()&&segments.stream().allMatch(s->Integer.valueOf(1).equals(s.page())));
        }
        if(fixture.equals("audio")) {
            String transcript=segments.stream().filter(s->!s.heading().equals("Acoustic description")).map(DerivedSegment::text).collect(java.util.stream.Collectors.joining(" "));
            checks.put("wordErrorRate",wordErrorRate("The fictional observatory is on planet Aster. Its dome is silver.",transcript));
            checks.put("timingAccuracy","reference sentence only; independent interval judgment required");
        }
        if(fixture.equals("tones")) {
            checks.put("qualifiedAcousticSegments",segments.stream().filter(s->s.heading().equals("Acoustic description")).count());
            checks.put("reference","generated 660 Hz then 330 Hz at 2 seconds; no song, performer or instrument ground truth");
            checks.put("eventAndTransitionAccuracy","independent judgment required");
        }
        return Collections.unmodifiableMap(checks);
    }
    static double wordErrorRate(String reference,String candidate) {
        var expected=words(reference);var actual=words(candidate);int[] previous=new int[actual.size()+1];
        for(int j=0;j<previous.length;j++)previous[j]=j;
        for(int i=1;i<=expected.size();i++) {
            int[] current=new int[actual.size()+1];current[0]=i;
            for(int j=1;j<=actual.size();j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),
                previous[j-1]+(expected.get(i-1).equals(actual.get(j-1))?0:1));
            previous=current;
        }
        return expected.isEmpty()?(actual.isEmpty()?0:1):(double)previous[actual.size()]/expected.size();
    }
    private static List<String> words(String value){return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).filter(s->!s.isEmpty()).toList();}
    List<Event> ledger(){return List.copyOf(events);}
    private static IllegalStateException denied(){return new IllegalStateException("Qualification dispatch denied");}
    @Override public synchronized void close() throws IOException {closed=true;if(lock.isValid())lock.release();channel.close();}
}
