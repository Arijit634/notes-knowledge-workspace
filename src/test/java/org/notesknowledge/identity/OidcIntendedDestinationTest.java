package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("FAST") @Tag("SECURITY") @Tag("API")
class OidcIntendedDestinationTest {
    @ParameterizedTest @ValueSource(strings={"/notes","/notes/new","/ask","/settings/profile","/settings/privacy","/settings/security","/settings/security/mfa","/settings/security/sessions","/settings/public-profile","/publications","/explore","/explore/search","/moderation/reports","/notes/01990a30-e254-70b3-a1d7-0763c4de2201","/publication/01990a30-e254-70b3-a1d7-0763c4de2201","/publications/01990a30-e254-70b3-a1d7-0763c4de2201","/moderation/reports/01990a30-e254-70b3-a1d7-0763c4de2201","/profile/synthetic_writer"})
    void onlyCanonicalApprovedRoutes(String path){assertThat(OidcIntendedDestination.allowed(path)).isTrue();}
    @ParameterizedTest @ValueSource(strings={"https://example.test","//example.test","\\\\example.test","/notes\\bad","/notes/../ask","/notes/%2f","/notes/%252f","/notes/%5c","/notes?next=/ask","/notes#x","/notes/"," /notes","/notes ","/login","/api/me/profile","/unknown","/NOTES","/profile/AUTHOR","/notes/01990A30-e254-70b3-a1d7-0763c4de2201","/notes\n","/notes\u0000","/notes\uff0fthing"})
    void maliciousAmbiguousAndUnapprovedRoutesAreNeverNormalized(String path){assertThat(OidcIntendedDestination.allowed(path)).isFalse();assertThat(OidcIntendedDestination.safe(path)).isEqualTo("/notes");}
    @Test void strictOptionalStartBodyAndLinkIsolation() {
        var request=new MockHttpServletRequest();assertThat(OidcAuthorizationInput.read(request,true)).isEqualTo("/notes");
        request.setContentType("application/json");request.setContent("{\"intendedDestination\":\"/ask\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(OidcAuthorizationInput.read(request,true)).isEqualTo("/ask");
        var link=new MockHttpServletRequest();link.setContentType("application/json");link.setContent("{\"intendedDestination\":\"/ask\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->OidcAuthorizationInput.read(link,false)).isInstanceOf(ApiFailureException.class);
    }
    @Test void continuationIsBoundToLoginUserSessionChallengeAndExpiry() {
        UUID user=new UUID(0,1);Instant now=Instant.parse("2026-10-23T00:00:00Z");
        var intent=new OidcReturnIntent(OidcProtocolPort.Action.LOGIN,user,"synthetic-session","synthetic-challenge",now.plusSeconds(10),"/ask");
        assertThat(intent.resolve(user,"synthetic-session","synthetic-challenge",now)).isEqualTo("/ask");
        assertThat(intent.resolve(new UUID(0,2),"synthetic-session","synthetic-challenge",now)).isEqualTo("/notes");
        assertThat(intent.resolve(user,"wrong","synthetic-challenge",now)).isEqualTo("/notes");
        assertThat(intent.resolve(user,"synthetic-session","wrong",now)).isEqualTo("/notes");
        assertThat(intent.resolve(user,"synthetic-session","synthetic-challenge",now.plusSeconds(10))).isEqualTo("/notes");
        assertThat(new OidcReturnIntent(OidcProtocolPort.Action.LINK,user,"synthetic-session","synthetic-challenge",now.plusSeconds(10),"/ask")
            .resolve(user,"synthetic-session","synthetic-challenge",now)).isEqualTo("/notes");
        assertThat(intent.toString()).doesNotContain("/ask","synthetic-session","synthetic-challenge");
    }
    @ParameterizedTest @ValueSource(strings={"{\"unknown\":true}","[]","{} {}","{\"intendedDestination\":\"/ask\",\"intendedDestination\":\"/notes\"}","{bad"})
    void malformedOrAmbiguousJsonIs400(String json){
        var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->OidcAuthorizationInput.read(request,true)).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.MALFORMED_REQUEST));
    }
    @ParameterizedTest @ValueSource(strings={"null","7","\"//example.test\"","\"/notes%2fnew\""})
    void presentInvalidDestinationIs422(String target){
        var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent(("{\"intendedDestination\":"+target+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->OidcAuthorizationInput.read(request,true)).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.INVALID_CONTRACT_INPUT));
    }
    @Test void bodyBoundsMediaTypeAndUtf8FailClosed(){
        var large=new MockHttpServletRequest();large.setContentType("application/json");large.setContent(new byte[1025]);
        assertThatThrownBy(()->OidcAuthorizationInput.read(large,true)).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.REQUEST_TOO_LARGE));
        var type=new MockHttpServletRequest();type.setContentType("text/plain");type.setContent("{}".getBytes());
        assertThatThrownBy(()->OidcAuthorizationInput.read(type,true)).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE));
        var invalid=new MockHttpServletRequest();invalid.setContentType("application/json");invalid.setContent(new byte[]{(byte)0xc3,0x28});
        assertThatThrownBy(()->OidcAuthorizationInput.read(invalid,true)).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.MALFORMED_REQUEST));
    }
}
