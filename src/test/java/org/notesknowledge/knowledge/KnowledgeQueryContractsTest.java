package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class KnowledgeQueryContractsTest {
    private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private static final UUID WORK=UUID.fromString("01999000-0000-7000-8000-000000000001"),OWNER=UUID.fromString("01999000-0000-7000-8000-000000000002");
    private static KnowledgeOperationMaterialCipher cipher(){return new KnowledgeOperationMaterialCipher("v1",KEY,"","");}
    private static KnowledgeOperationMaterialCipher.Context context(){return new KnowledgeOperationMaterialCipher.Context(WORK,OWNER,"semantic_corpus",KnowledgeOperationMaterialCipher.Kind.INPUT,0);}
    @Test void roundtripRotationAndRedaction(){var c=cipher();var frame=c.seal(context(),"synthetic intent".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new String(c.open(context(),frame),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("synthetic intent");
        var rotated=new KnowledgeOperationMaterialCipher("v2",Base64.getEncoder().encodeToString(new byte[32]),"v1",KEY);
        assertThat(rotated.open(context(),frame)).isEqualTo("synthetic intent".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(frame.toString()+context()).doesNotContain(WORK.toString(),OWNER.toString(),"synthetic intent",KEY);
    }
    @ParameterizedTest @ValueSource(strings={"work","owner","purpose","kind","version","tamper","missing-key","wrong-key","nonce"})
    void authenticatedContextCannotBeSwapped(String attack){var c=cipher();var frame=c.seal(context(),new byte[]{1,2,3});var ctx=context();
        switch(attack){
            case "work"->ctx=new KnowledgeOperationMaterialCipher.Context(OWNER,OWNER,ctx.purpose(),ctx.kind(),0);
            case "owner"->ctx=new KnowledgeOperationMaterialCipher.Context(WORK,WORK,ctx.purpose(),ctx.kind(),0);
            case "purpose"->ctx=new KnowledgeOperationMaterialCipher.Context(WORK,OWNER,"deterministic_corpus",ctx.kind(),0);
            case "kind"->ctx=new KnowledgeOperationMaterialCipher.Context(WORK,OWNER,ctx.purpose(),KnowledgeOperationMaterialCipher.Kind.RESULT,0);
            case "version"->ctx=new KnowledgeOperationMaterialCipher.Context(WORK,OWNER,ctx.purpose(),ctx.kind(),1);
            case "tamper"->{byte[] bytes=frame.ciphertext();bytes[0]^=1;frame=new KnowledgeOperationMaterialCipher.Envelope(bytes,frame.nonce(),frame.keyVersion());}
            case "missing-key"->c=new KnowledgeOperationMaterialCipher("v2",KEY,"","");
            case "wrong-key"->{byte[] wrong=new byte[32];wrong[0]=1;c=new KnowledgeOperationMaterialCipher("v1",Base64.getEncoder().encodeToString(wrong),"","");}
            case "nonce"->frame=new KnowledgeOperationMaterialCipher.Envelope(frame.ciphertext(),new byte[12],frame.keyVersion());
        }
        var finalCipher=c;var finalContext=ctx;var finalFrame=frame;
        assertThatThrownBy(()->finalCipher.open(finalContext,finalFrame)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void materialBoundsAreHardAndKeyAbsenceDoesNotInventMaterial(){var c=cipher();
        assertThat(c.seal(context(),new byte[16384]).ciphertext()).hasSize(16400);
        assertThatThrownBy(()->c.seal(context(),new byte[16385])).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        var result=new KnowledgeOperationMaterialCipher.Context(WORK,OWNER,"semantic_corpus",KnowledgeOperationMaterialCipher.Kind.RESULT,1);
        assertThat(c.seal(result,new byte[1048576]).ciphertext()).hasSize(1048592);
        assertThatThrownBy(()->c.seal(result,new byte[1048577])).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        assertThatThrownBy(()->new KnowledgeOperationMaterialCipher("v1","","","").seal(context(),new byte[]{1})).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void opaqueHandlesBindOwnerPurposeAndExpiry(){Instant now=Instant.parse("2026-10-07T00:00:00Z");var codec=new KnowledgeOperationHandleCodec("v1",KEY,"","",Clock.fixed(now,ZoneOffset.UTC));
        var location=new KnowledgeOperationHandleCodec.Location(WORK,OWNER,"semantic_corpus",now.plusSeconds(60));String handle=codec.encode(location);
        assertThat(handle).doesNotContain(WORK.toString(),OWNER.toString());assertThat(codec.decode(handle,OWNER)).isEqualTo(location);
        assertThatThrownBy(()->codec.decode(handle,WORK)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        String tampered=handle.substring(0,20)+(handle.charAt(20)=='A'?"B":"A")+handle.substring(21);
        assertThatThrownBy(()->codec.decode(tampered,OWNER)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        var expired=new KnowledgeOperationHandleCodec("v1",KEY,"","",Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC));
        assertThatThrownBy(()->expired.decode(handle,OWNER)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void routingIsServerOwnedAndArbitrarySemanticCategoriesAreNotHardcoded(){
        assertThat(KnowledgeQueryRequest.parse(Map.of("query","show every link I saved")).plan()).isEqualTo(KnowledgeQueryRequest.Plan.DETERMINISTIC_CORPUS);
        for(String q:List.of("list all cities I visited","every book I mentioned","list films to watch","all recipes for dinner","find everything about the project deadline"))assertThat(KnowledgeQueryRequest.parse(Map.of("query",q)).plan()).isEqualTo(KnowledgeQueryRequest.Plan.SEMANTIC_CORPUS);
        assertThat(KnowledgeQueryRequest.parse(Map.of("query","what was my PlayStation password")).plan()).isEqualTo(KnowledgeQueryRequest.Plan.FOCUSED);
        assertThat(KnowledgeQueryRequest.parse(Map.of("query","find Kyoto hotel")).plan()).isEqualTo(KnowledgeQueryRequest.Plan.RANKED);
    }
    @ParameterizedTest @ValueSource(strings={"ownerUserId","provider","model","queryClass","lineage","vector","reranker","contextSize","workClass"})
    void rejectsTechnicalRouting(String field){assertThatThrownBy(()->KnowledgeQueryRequest.parse(Map.of("query","synthetic",field,"injected"))).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);}
    @Test void rrfUsesRankSixtyAndDoesNotPenalizeAiExcludedSources(){assertThat(ReciprocalRankFusion.fuse(List.of(List.of("off","on"),List.of("on","semantic")),3)).containsExactly("on","off","semantic");
        assertThat(ReciprocalRankFusion.fuse(List.of(List.of("off","on")),2)).containsExactly("off","on");}
    @Test void providerClaimsRequireValidSuppliedEvidence(){assertThatThrownBy(()->KnowledgeQueryEngine.validate(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Unsupported",List.of("foreign"))),false),1)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->KnowledgeQueryEngine.validate(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Unsupported",List.of())),false),1)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->KnowledgeQueryEngine.validate(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Invented alias",List.of("e00"))),false),1)).isInstanceOf(DerivationFailure.class);
        KnowledgeQueryEngine.validate(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Supported",List.of("e0"))),false),1);
    }
    @Test void equalMediaTextAtDifferentLocationsIsNotDuplicateEvidence(){
        for(var pair:List.of(
                List.of(mediaSegment("pdf_text",1,null,null,null),mediaSegment("pdf_text",7,null,null,null)),
                List.of(mediaSegment("transcript",null,0.,1.,null),mediaSegment("transcript",null,25.,26.,null)),
                List.of(mediaSegment("video_scene",null,0.,1.,null),mediaSegment("video_scene",null,25.,26.,null)),
                List.of(mediaSegment("image_region",null,null,null,.1),mediaSegment("image_region",null,null,null,.6)))){
            var first=evidence(UUID.fromString("01999000-0000-7000-8000-000000000003"),pair.get(0));
            var second=evidence(UUID.fromString("01999000-0000-7000-8000-000000000004"),pair.get(1));
            assertThat(KnowledgeQueryEngine.overlaps(first,second)).as("distinct location keeps its citation").isFalse();
            assertThat(KnowledgeQueryEngine.overlaps(first,evidence(second.segmentId(),pair.get(0)))).isTrue();
            assertThat(KnowledgeQueryEngine.overlaps(first,first)).isTrue();
        }
    }
    @Test void generationEvidenceCarriesTypedLocationsWithoutPrivateIdentifiers(){
        var segment=mediaSegment("pdf_text",7,null,null,null);
        var payload=new StructuredKnowledgePort.Evidence("e0",segment.text(),"pdf",KnowledgeQueryEngine.location(segment));
        var serialized=new tools.jackson.databind.ObjectMapper().writeValueAsString(payload);
        assertThat(serialized).contains("\"page\":7","\"id\":\"e0\"").doesNotContain(OWNER.toString(),WORK.toString(),"owner","attachmentId","noteId");
        assertThat(payload.toString()).doesNotContain(segment.text(),"7");
    }
    private static DerivedSegment mediaSegment(String kind,Integer page,Double start,Double end,Double x){
        return new DerivedSegment("Same fictional observation",kind,"",null,null,page,start,end,x,x==null?null:.1,x==null?null:.2,x==null?null:.2);
    }
    private static QueryEvidenceRepository.Evidence evidence(UUID segment,DerivedSegment value){
        var expected=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(OWNER,WORK,WORK,1,1,1L);
        return new QueryEvidenceRepository.Evidence(segment,WORK,expected,"lineage","pdf",0,value);
    }
    @ParameterizedTest @ValueSource(strings={""," \n\t"})
    void titleOnlyNoteProducesRealEvidenceWithoutInventingABodySpan(String body){
        var segments=DerivationExecutor.noteSegments(material("Fictional media collection",body));
        assertThat(segments).hasSize(1);var title=segments.getFirst();
        assertThat(title.text()).isEqualTo("Note title: Fictional media collection");
        assertThat(title.heading()).isEqualTo("Note title");assertThat(title.start()).isZero();assertThat(title.end()).isZero();
    }
    @Test void nonblankMarkdownRetainsItsExistingTextAndExactOffsets(){
        String body="# Actual heading\nFictional body text";
        assertThat(DerivationExecutor.noteSegments(material("Separate title",body))).isEqualTo(new MarkdownChunker().chunk(body));
    }
    private static org.notesknowledge.knowledge.spi.PrivateDerivationSource.Material material(String title,String body){
        return new org.notesknowledge.knowledge.spi.PrivateDerivationSource.Material(){
            public String title(){return title;}public String markdown(){return body;}
            public java.io.InputStream open(){throw new AssertionError("not_media");}
        };
    }
}
