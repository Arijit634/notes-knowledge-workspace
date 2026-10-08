package org.notesknowledge.publishing;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.notes.*;
import org.notesknowledge.profile.PublicProfileApi;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("SECURITY")
class PublicationPreviewFingerprintCodecTest {
    private static final Instant NOW=Instant.parse("2026-10-08T00:00:00Z");
    private static final String KEY="AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=";
    private final PublicationPreviewFingerprintCodec codec=new PublicationPreviewFingerprintCodec(KEY,"test1",Clock.fixed(NOW,ZoneOffset.UTC));
    @Test void opaqueRandomExpiringTokenBindsExactAuthorizedMaterial(){var p=prepared();var token=codec.issue(p);
        codec.require(token,p);assertThat(codec.issue(p)).isNotEqualTo(token);assertThat(token).doesNotContain(p.owner().toString(),p.source().noteId().toString(),"Synthetic");
        assertThatThrownBy(()->codec.require(token+"x",p)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(()->new PublicationPreviewFingerprintCodec(KEY,"test1",Clock.fixed(NOW.plusSeconds(300),ZoneOffset.UTC)).require(token,p)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(()->new PublicationPreviewFingerprintCodec(KEY,"test2",Clock.fixed(NOW,ZoneOffset.UTC)).require(token,p)).isInstanceOf(ApiFailureException.class);
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int finalIndex=alphabet.indexOf(token.charAt(token.length()-1));
        String noncanonical=token.substring(0,token.length()-1)+alphabet.charAt(finalIndex+1);
        assertThat(Base64.getUrlDecoder().decode(noncanonical.substring(noncanonical.lastIndexOf('.')+1)))
            .isEqualTo(Base64.getUrlDecoder().decode(token.substring(token.lastIndexOf('.')+1)));
        assertThatThrownBy(()->codec.require(noncanonical,p)).isInstanceOf(ApiFailureException.class);
    }
    @Test void ownerNoteRevisionTagsProfileAndSelectionCannotBeSubstituted(){var p=prepared();var token=codec.issue(p);
        List<PublicationTransactions.Prepared> wrong=List.of(
            new PublicationTransactions.Prepared(UUID.randomUUID(),p.source(),p.selection(),p.author()),
            new PublicationTransactions.Prepared(p.owner(),new PublishableSourceApi.Source(UUID.randomUUID(),1,"Synthetic","Body",List.of()),p.selection(),p.author()),
            new PublicationTransactions.Prepared(p.owner(),new PublishableSourceApi.Source(p.source().noteId(),2,"Synthetic","Body",List.of()),p.selection(),p.author()),
            new PublicationTransactions.Prepared(p.owner(),new PublishableSourceApi.Source(p.source().noteId(),1,"Synthetic","Different",List.of()),p.selection(),p.author()),
            new PublicationTransactions.Prepared(p.owner(),new PublishableSourceApi.Source(p.source().noteId(),1,"Synthetic","Body",List.of("Changed tag")),p.selection(),p.author()),
            new PublicationTransactions.Prepared(p.owner(),p.source(),List.of(),p.author()),
            new PublicationTransactions.Prepared(p.owner(),p.source(),p.selection(),new PublicProfileApi.Author(p.author().projectionId(),p.owner(),2,p.author().view())));
        wrong.forEach(w->assertThatThrownBy(()->codec.require(token,w)).isInstanceOf(ApiFailureException.class));
    }
    @Test void unavailableKeyFailsClosedAndMalformedInputIsBounded(){var p=prepared();
        assertThatThrownBy(()->new PublicationPreviewFingerprintCodec("","test1",Clock.fixed(NOW,ZoneOffset.UTC)).issue(p)).isInstanceOf(ApiFailureException.class);
        for(String invalid:List.of("","x".repeat(257),"p1.test1.0.bad.bad","p1.test1.bad.bad.bad"))assertThatThrownBy(()->codec.require(invalid,p)).isInstanceOf(ApiFailureException.class);
    }
    private PublicationTransactions.Prepared prepared(){UUID owner=UUID.randomUUID();return new PublicationTransactions.Prepared(owner,
        new PublishableSourceApi.Source(UUID.randomUUID(),1,"Synthetic","Body",List.of()),
        List.of(new AttachmentSourceApi.Selected(UUID.randomUUID(),1,"private-attachment/"+"a".repeat(64),"image","image/png","synthetic.png",100,2,2,null,null)),
        new PublicProfileApi.Author(UUID.randomUUID(),owner,1,new PublicProfileApi.View("synthetic","Reader","",null,Map.of())));}
}
