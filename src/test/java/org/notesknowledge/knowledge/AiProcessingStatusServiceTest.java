package org.notesknowledge.knowledge;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
@Tag("FAST") @Tag("SECURITY")
class AiProcessingStatusServiceTest {
    private final ProcessingPolicyService policies=mock(ProcessingPolicyService.class);
    private final AiDerivationProperties config=mock(AiDerivationProperties.class);
    private final PrivateRepresentationRepository representations=mock(PrivateRepresentationRepository.class);
    private final ProviderDispatchPolicy dispatch=mock(ProviderDispatchPolicy.class);
    private final AiProcessingStatusService status=new AiProcessingStatusService(null,null,policies,config,representations,dispatch);
    private final UUID owner=new UUID(0,1),note=new UUID(0,2);
    @BeforeEach void prepare(){
        when(policies.policyPrerequisite(owner)).thenReturn(Optional.of(new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64))));
        when(config.configured()).thenReturn(true);when(config.approvedPolicyFingerprint()).thenReturn("a".repeat(64));
        when(config.provider()).thenReturn("synthetic");when(config.adapterVersion()).thenReturn("v1");when(config.embeddingModel()).thenReturn("synthetic-embedding");when(config.mediaModel()).thenReturn("synthetic-media");when(config.modelRevision()).thenReturn("v1");when(config.dimension()).thenReturn(2);when(config.operator()).thenReturn("cosine");when(config.normalization()).thenReturn("none");when(config.configurationId()).thenReturn("v1");when(config.region()).thenReturn("synthetic");when(config.tier()).thenReturn("synthetic");
        when(dispatch.permits(any(),any(),any())).thenReturn(true);
    }
    private PrivateDerivationSource.Metadata source(boolean file){return new PrivateDerivationSource.Metadata(new PrivateAiSourceCurrentness.Expected(owner,note,file?new UUID(0,4):null,1,1,file?1L:null),file?"video":"note",true,"active",file?"video/mp4":null,100,file?1.:null,null);}
    @Test void pausedNoteIndexingDoesNotMisreportProviderOutageOrBlockReadyMedia(){
        status.scheduleNotes(false);var video=source(true);when(representations.ready(eq(video.expected()),any())).thenReturn(true);
        assertThat(status.state(source(false))).isEqualTo(new AiProcessingStatusService.State("blocked","noteTextIndexingPaused"));
        assertThat(status.state(video)).isEqualTo(new AiProcessingStatusService.State("ready",null));
    }
    @Test void existingReadyNoteIsStillReadyWhileNewNoteIndexingIsPaused(){
        status.scheduleNotes(false);when(representations.ready(any(),any())).thenReturn(true);
        assertThat(status.state(source(false))).isEqualTo(new AiProcessingStatusService.State("ready",null));
    }
    @Test void notYetScheduledSourceIsDistinctFromUnavailableProvider(){
        assertThat(status.state(source(false)).reason()).isEqualTo("processingNotScheduled");
        assertThat(status.state(source(true)).reason()).isEqualTo("processingNotScheduled");
        when(config.configured()).thenReturn(false);assertThat(status.state(source(false)).reason()).isEqualTo("providerUnavailable");
    }
    @Test void pausedSettingCannotOverrideAiOffOrPolicyDenial(){
        status.scheduleNotes(false);var s=source(false);var off=new PrivateDerivationSource.Metadata(s.expected(),"note",false,"active",null,0,null,null);
        assertThat(status.state(off).reason()).isEqualTo("aiDisabled");when(policies.policyPrerequisite(owner)).thenReturn(Optional.empty());
        assertThat(status.state(s).reason()).isEqualTo("policyAcknowledgementRequired");
    }
}
