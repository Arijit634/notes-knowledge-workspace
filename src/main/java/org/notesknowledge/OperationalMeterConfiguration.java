package org.notesknowledge;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A second boundary in addition to typed producers: identifiers cannot become dimensions. */
@Configuration(proxyBeanMethods=false)
class OperationalMeterConfiguration {
    private static final Set<String> METERS=Set.of("notes.workspace.http.inflight","notes.workspace.dependency.available",
        "notes.workspace.db.operation","notes.workspace.redis.operation","notes.workspace.rate_control","notes.workspace.note.command",
        "notes.workspace.knowledge.work.count","notes.workspace.knowledge.work.oldest_age","notes.workspace.knowledge.work.outcome",
        "notes.workspace.security_email.work.count","notes.workspace.security_email.oldest_ready_age","notes.workspace.security_email.outcome",
        "notes.workspace.security_email.claim","notes.workspace.security_email.provider","notes.workspace.telemetry.available",
        "notes.workspace.search.request","notes.workspace.retrieval.candidates","notes.workspace.retrieval.coverage",
        "notes.workspace.retrieval.coverage.outcome","notes.workspace.retrieval.pre_generation","notes.workspace.retrieval.query",
        "notes.workspace.ai.provider","notes.workspace.attachment.validation","notes.workspace.storage.operation",
        "notes.workspace.identity.auth","notes.workspace.security.control_rejected","notes.workspace.publication.command",
        "notes.workspace.public.denial","notes.workspace.moderation.command","notes.workspace.knowledge.operation",
        "knowledge.derivation.outcome","knowledge.derivation.poll","knowledge.public.poll","knowledge.public.work",
        "discovery.public.read","discovery.like");
    private static final Set<String> TAGS=Set.of("module","operation","outcome","dependency","state","jobType",
        "eligibility","queryClass","stage","capability","provider","modality","objectClass","reasonClass",
        "control","class","strategy","environment","flow","operationClass");
    private static final Set<String> VALUES;
    static {
        var values=new java.util.HashSet<String>();
        for(var v:BackendTelemetry.Operation.values()){values.add(v.name());values.add(v.name().toLowerCase(java.util.Locale.ROOT));}
        for(var v:BackendTelemetry.Outcome.values())values.add(v.name().toLowerCase(java.util.Locale.ROOT));
        values.addAll(java.util.List.of("application","identity","profile","notes","knowledge","publishing","discovery","moderation",
            "queued","claimed","retry_wait","completed","submitted","failed","obsolete","cancelled","ready","pending",
            "eligible","due","activated","deferred","capacity","databaseUnavailable","invalidMaterial","unavailable","committed","denied",
            "public_avatar","public_media","private_attachment","private_avatar","copy","read","delete","inventory",
            "owner_unpublish","source_retired","account_deleted","superseded","moderation","begin_review","decision",
            "accepted","duplicate","dismissed","actioned","success","failure","up","down","unknown","degraded",
            "gemini","postgresql","redis","object_storage","security_email","google_oidc","gemini_chat","gemini_embedding_multimodal",
            "lexical","fuzzy","exact","hybrid","source","semantic","deterministic","bounded","complete","truncated",
            "csrf","access","recent_auth","mfa","rate_control","policy","throttled","control_unavailable","rejected",
            "note_derivation","attachment_derivation","public_index","capability_link","security_notice","excluded","telemetry",
            "authorization","invalid_csrf","access_denied","allowed","enforced","bypassed","security_critical","best_effort",
            "identity","search","attachment","public","other","like","unlike","satisfied","explore","execute","context","changed","health",
            "retryable","non_retryable","ambiguous","smtp","reclaimed"));
        values.addAll(java.util.List.of("transient_dependency","invalid_source","policy_blocked","attempts_exhausted","quota",
            "provider_unavailable","invalid_output","budget_exceeded","lineage_obsolete"));
        VALUES=Set.copyOf(values);
    }
    @Bean MeterFilter boundedOperationalDimensions() {
        return new MeterFilter(){
            @Override public MeterFilterReply accept(Meter.Id id){
                if(id.getName().startsWith("notes.workspace.")||id.getName().startsWith("knowledge.")||id.getName().startsWith("discovery.")){
                    if(!METERS.contains(id.getName()))return MeterFilterReply.DENY;
                    for(var tag:id.getTags())if(!TAGS.contains(tag.getKey())||!VALUES.contains(tag.getValue()))return MeterFilterReply.DENY;
                }
                return MeterFilterReply.NEUTRAL;
            }
        };
    }
    @Bean MeterFilter boundedHttpRoutes(){return MeterFilter.maximumAllowableTags("http.server.requests","uri",100,MeterFilter.deny());}
}
