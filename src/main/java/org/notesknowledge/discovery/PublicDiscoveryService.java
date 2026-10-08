package org.notesknowledge.discovery;

import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.notesknowledge.discovery.spi.PublicDiscoveryScope;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.profile.PublicProfileApi;
import org.notesknowledge.websupport.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicDiscoveryService {
    record Item(UUID id,String title,List<String> tags,PublicProfileApi.View author,Instant publishedAt,PublicProjectionApi.Engagement engagement) {
        @Override public String toString(){return "PublicDiscoveryItem[REDACTED]";}
    }
    record Page(List<Item> items,String nextCursor,String rankingMode,boolean semanticAvailable,boolean exhaustive) { }
    private final PublicDiscoveryRepository repository;
    private final PublicDiscoveryScope authority;
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    private final OpaqueCursorCodec cursors;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    PublicDiscoveryService(PublicDiscoveryRepository repository,PublicDiscoveryScope authority,ObjectProvider<AccountEligibilityApi> eligibility,
        OpaqueCursorCodec cursors,Clock clock,io.micrometer.core.instrument.MeterRegistry meters) {
        this.repository=repository;this.authority=authority;this.eligibility=eligibility;this.cursors=cursors;this.clock=clock;this.meters=meters;
    }
    @Transactional(readOnly=true,timeout=5)
    Page page(String sort,String rawQuery,String rawTag,Integer requested,String cursor,boolean search) {
        if(!Set.of("latest","trending").contains(sort))throw malformed();
        String query=normalize(rawQuery,400),tag=normalize(rawTag,64).toLowerCase(Locale.ROOT);
        if(search&&query.isEmpty()&&tag.isEmpty())throw malformed();
        int limit=new PageLimitPolicy(20,100).resolve(requested);
        // As-of time is authenticated in the cursor. Ranking remains current, NOT a historical feed snapshot.
        // Scope fingerprint below rejects continuation if generations, authors or engagement change.
        var rows=new ArrayList<PublicDiscoveryRepository.Row>();var authors=new HashMap<UUID,PublicDiscoveryScope.Current>();
        UUID after=null;int inspected=0;long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        do {
            var batch=repository.inventory(after);if(batch.isEmpty())break;
            inspected+=batch.size();if(inspected>5000||System.nanoTime()>deadline)throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
            var current=authority.current(batch,false);current.forEach(c->authors.put(c.id(),c));
            rows.addAll(repository.rows(current,search?query:"",tag,clock.instant()));
            after=batch.getLast();if(batch.size()<100)break;
        }while(true);
        rows.sort(Comparator.comparing(r->r.id().toString()));
        String stamp=ScopedCursorPage.digest(rows.stream().map(r->r.id()+":"+r.generation()+":"+r.likes()+":"+r.views()+":"+authors.get(r.id()).author().hashCode()).collect(java.util.stream.Collectors.joining("|")));
        var context=new OpaqueCursorCodec.ExpectedCursorContext(new OpaqueCursorCodec.RouteFamily(search?"PUBLIC_SEARCH":"PUBLIC_EXPLORE"),
            new OpaqueCursorCodec.ScopeFingerprint(stamp),new OpaqueCursorCodec.FilterFingerprint(ScopedCursorPage.digest(query+"\0"+tag)),new OpaqueCursorCodec.SortCode(search?"RELEVANCE":sort.toUpperCase(Locale.ROOT)));
        Instant asOf=clock.instant();int offset=0;
        if(cursor!=null) {
            var position=cursors.decode(cursor,context).position().scalars();
            if(position.size()!=2||!(position.get(0) instanceof OpaqueCursorCodec.EpochMillisValue time)||!(position.get(1) instanceof OpaqueCursorCodec.SignedLongValue index)
                ||index.value()<0||index.value()>5000)throw malformed();
            asOf=Instant.ofEpochMilli(time.value());offset=(int)index.value();
        }
        final Instant rankingTime=asOf;
        var eligibleRows=rows.stream().filter(r->!r.published().isAfter(rankingTime)).toList();
        List<PublicDiscoveryRepository.Row> ordered;
        if(search&&!query.isEmpty())ordered=fuse(eligibleRows);
        else {
            ordered=new ArrayList<>(eligibleRows);
            Comparator<PublicDiscoveryRepository.Row> order=Comparator.comparing(PublicDiscoveryRepository.Row::published).reversed().thenComparing(r->r.id().toString(),Comparator.reverseOrder());
            if(sort.equals("trending")&&!search)order=Comparator.<PublicDiscoveryRepository.Row>comparingDouble(r->trending(r.likes(),r.views(),r.published(),rankingTime)).reversed().thenComparing(order);
            ordered.sort(order);
        }
        if(offset>ordered.size())throw malformed();
        int end=Math.min(ordered.size(),offset+limit);var selected=ordered.subList(offset,end);
        // Revalidate after scoring, independently of the earlier scope. Never return a stale generation.
        var fresh=authority.current(selected.stream().map(PublicDiscoveryRepository.Row::id).toList(),false).stream().collect(java.util.stream.Collectors.toMap(PublicDiscoveryScope.Current::id,c->c));
        var items=selected.stream().filter(r->fresh.containsKey(r.id())&&fresh.get(r.id()).generation()==r.generation()&&repository.projectionCurrent(r.id(),r.generation(),false))
            .map(r->new Item(r.id(),r.title(),r.tags(),fresh.get(r.id()).author(),r.published(),new PublicProjectionApi.Engagement(r.likes(),r.views()))).toList();
        String next=end<ordered.size()?cursors.encode(context,new OpaqueCursorCodec.OrderingTuple(List.of(new OpaqueCursorCodec.EpochMillisValue(asOf.toEpochMilli()),new OpaqueCursorCodec.SignedLongValue(end))),Duration.ofMinutes(15)):null;
        readMetric(search);
        return new Page(items,next,search?(query.isEmpty()?"tag_latest":"lexical_fuzzy"):sort,false,false);
    }
    /** RRF over independently ranked lexical and fuzzy lists; no confidence percentage. */
    private static List<PublicDiscoveryRepository.Row> fuse(List<PublicDiscoveryRepository.Row> rows) {
        var tie=Comparator.comparing((PublicDiscoveryRepository.Row r)->r.id().toString());
        var lexical=rows.stream().filter(r->r.lexical()>0).sorted(Comparator.comparingDouble(PublicDiscoveryRepository.Row::lexical).reversed().thenComparing(tie)).limit(50).toList();
        var fuzzy=rows.stream().filter(r->r.fuzzy()>=0.3).sorted(Comparator.comparingDouble(PublicDiscoveryRepository.Row::fuzzy).reversed().thenComparing(tie)).limit(50).toList();
        var scores=new HashMap<PublicDiscoveryRepository.Row,Double>();
        for(var signal:List.of(lexical,fuzzy))for(int i=0;i<signal.size();i++)scores.merge(signal.get(i),1.0/(60+i+1),Double::sum);
        return scores.keySet().stream().sorted(Comparator.<PublicDiscoveryRepository.Row>comparingDouble(scores::get).reversed().thenComparing(tie)).toList();
    }
    /** Saturated aggregate signals, logarithmic amplification, time decay; views are NOT unique viewers. */
    static double trending(long likes,long views,Instant published,Instant asOf) {
        double hours=Math.max(0,Duration.between(published,asOf).toSeconds()/3600.0);
        return (4*Math.log1p(Math.min(10000,Math.max(0,likes)))+Math.log1p(Math.min(100000,Math.max(0,views))))/Math.pow(hours+2,1.5);
    }
    @Transactional(timeout=3)
    void like(UUID viewer,UUID publication,boolean desired,HttpServletRequest request) {
        eligibility.getObject().requireCurrentOwner(viewer,request);
        var current=authority.current(List.of(publication),true);
        if(current.isEmpty()||!repository.projectionCurrent(publication,current.getFirst().generation(),true))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        repository.like(viewer,publication,desired);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit(){likeMetric(desired);}
            });
    }
    void readMetric(boolean search) {
        try {meters.counter("discovery.public.read","operation",search?"search":"explore","outcome","deterministic").increment();}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(PublicDiscoveryService.class).warn("public_read_telemetry_deferred");}
    }
    void likeMetric(boolean desired) {
        try {meters.counter("discovery.like","operation",desired?"like":"unlike","outcome","satisfied").increment();}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(PublicDiscoveryService.class).warn("public_like_telemetry_deferred");}
    }
    static String normalize(String input,int bound) {
        if(input==null)return "";
        if(input.length()>bound||input.codePoints().anyMatch(Character::isISOControl))throw malformed();
        String value=Normalizer.normalize(input.strip(),Normalizer.Form.NFKC).strip();
        if(value.length()>bound)throw malformed();return value;
    }
    private static ApiFailureException malformed(){return ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);}
}
