package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("FAST") @Tag("SECURITY")
class BoundedPasswordEncoderTest {
    @Test void capacityIsNotReportedAsWrongCredentialsForKnownUnknownOrRecentAuth() {
        var identity=mock(IdentityPersistence.class);var passwords=mock(PasswordEncoder.class);
        var commit=mock(LoginCommitService.class);var id=UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");
        when(identity.loginAccount("known@example.test")).thenReturn(Optional.of(new IdentityPersistence.LoginAccount(id,"synthetic-verifier","active",Instant.EPOCH)));
        when(identity.currentPasswordVerifier(id)).thenReturn(Optional.of("synthetic-verifier"));
        when(passwords.matches(any(),nullable(String.class))).thenThrow(ApiFailureException.rateLimited(1));
        var service=new PasswordAuthenticationService(identity,passwords,Clock.systemUTC(),commit);
        for(String email:new String[]{"known@example.test","unknown@example.test"}) {
            assertThatThrownBy(()->service.authenticate(email,"synthetic-input",new MockHttpServletRequest(),new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.RATE_LIMITED));
        }
        assertThatThrownBy(()->service.reauthenticate(id,"synthetic-input"))
            .isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.RATE_LIMITED));
        verifyNoInteractions(commit);
    }
    @Test void encodeAndVerificationShareOneBoundedWorkspaceAndRecoverAfterContention() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        PasswordEncoder delegate=new PasswordEncoder() {
            public String encode(CharSequence raw) {
                calls.incrementAndGet();entered.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Missing fixture release"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                return "synthetic-verifier";
            }
            public boolean matches(CharSequence raw,String encoded){calls.incrementAndGet();return true;}
        };
        var encoder=new BoundedPasswordEncoder(delegate,20);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->encoder.encode("synthetic-input"));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->encoder.matches("synthetic-input","synthetic-verifier"))
                    .isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.RATE_LIMITED));
                assertThat(calls).hasValue(1);
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo("synthetic-verifier");
            assertThat(encoder.matches("synthetic-input","synthetic-verifier")).isTrue();
            assertThat(calls).hasValue(2);
        }
    }
    @Test void delegateFailureAlwaysReleasesWorkspaceAndDoesNotChangeVerificationOutcome() {
        var calls=new AtomicInteger();
        var encoder=new BoundedPasswordEncoder(new PasswordEncoder() {
            public String encode(CharSequence raw){if(calls.getAndIncrement()==0)throw new IllegalArgumentException("synthetic rejection");return "synthetic-verifier";}
            public boolean matches(CharSequence raw,String encoded){return false;}
            public boolean upgradeEncoding(String encoded){return true;}
        });
        assertThatThrownBy(()->encoder.encode("synthetic-input")).isInstanceOf(IllegalArgumentException.class);
        assertThat(encoder.encode("synthetic-input")).isEqualTo("synthetic-verifier");
        assertThat(encoder.matches("wrong","synthetic-verifier")).isFalse();
        assertThat(encoder.upgradeEncoding("synthetic-verifier")).isTrue();
    }
    @Test void interruptedAdmissionPreservesInterruptAndNeverInvokesHashing() {
        var calls=new AtomicInteger();
        var encoder=new BoundedPasswordEncoder(new PasswordEncoder() {
            public String encode(CharSequence raw){calls.incrementAndGet();return "synthetic-verifier";}
            public boolean matches(CharSequence raw,String encoded){calls.incrementAndGet();return true;}
        });
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->encoder.encode("synthetic-input")).isInstanceOfSatisfying(ApiFailureException.class,
                e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(calls).hasValue(0);
        } finally { Thread.interrupted(); }
        assertThat(encoder.encode("synthetic-input")).isEqualTo("synthetic-verifier");
    }
}
