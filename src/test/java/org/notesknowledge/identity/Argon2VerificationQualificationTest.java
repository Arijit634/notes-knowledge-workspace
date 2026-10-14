package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.*;

/** Bounded diagnostics for the prior unexplained correct-password failure.
 * No password, salt or encoded hash is emitted as diagnostic evidence. */
@Tag("FAST") @Tag("SECURITY")
class Argon2VerificationQualificationTest {
    @Test void independentEncoderInstancesAndUnicodeRoundTripsRemainConsistent(){
        var first=new IdentityPasswordConfiguration().identityPasswordEncoder();
        var second=new IdentityPasswordConfiguration().identityPasswordEncoder();
        for(int i=0;i<8;i++){
            String value="synthetic-qualification-\u03bb-\u65e5-"+i;
            String encoded=first.encode(value);
            assertThat(first.matches(value,encoded)).as("same encoder round trip %d",i).isTrue();
            assertThat(second.matches(value,encoded)).as("independent encoder round trip %d",i).isTrue();
            assertThat(first.matches(value+"-different",encoded)).isFalse();
            assertThat(first.upgradeEncoding(encoded)).isFalse();
        }
    }
    @Test void oneSharedEncoderHasNoCrossRequestAuthorityUnderBoundedConcurrency()throws Exception {
        var encoder=new IdentityPasswordConfiguration().identityPasswordEncoder();
        try(var pool=Executors.newFixedThreadPool(2)){
            for(int round=0;round<8;round++){
                var barrier=new CyclicBarrier(2);var tasks=new ArrayList<Callable<Boolean>>();
                for(int lane=0;lane<2;lane++){
                    final String value="synthetic-parallel-"+round+"-"+lane;
                    tasks.add(()->{barrier.await(10,TimeUnit.SECONDS);String hash=encoder.encode(value);
                        return encoder.matches(value,hash)&&!encoder.matches(value+"-wrong",hash);});
                }
                for(var result:pool.invokeAll(tasks,60,TimeUnit.SECONDS))assertThat(result.get()).isTrue();
            }
        }
    }
    @Test void fixedSyntheticSaltDirectDependencyAndSpringParsingAgreeWithoutWeakeningParameters(){
        byte[] salt=new byte[16];Arrays.fill(salt,(byte)17);
        byte[] password="synthetic-fixed-input".getBytes(StandardCharsets.UTF_8);
        byte[] expected=derive(password,salt);
        var b64=Base64.getEncoder().withoutPadding();
        String hash="{argon2id}$argon2id$v=19$m=65536,t=3,p=1$"+b64.encodeToString(salt)+"$"+b64.encodeToString(expected);
        var encoder=new IdentityPasswordConfiguration().identityPasswordEncoder();
        for(int i=0;i<8;i++){
            assertThat(Arrays.equals(expected,derive(password,salt))).as("fixed dependency computation %d",i).isTrue();
            assertThat(encoder.matches("synthetic-fixed-input",hash)).as("Spring parsing/verification %d",i).isTrue();
            assertThat(encoder.matches("synthetic-other-input",hash)).isFalse();
        }
        Arrays.fill(password,(byte)0);Arrays.fill(expected,(byte)0);Arrays.fill(salt,(byte)0);
    }
    private byte[] derive(byte[] password,byte[] salt){
        var generator=new Argon2BytesGenerator();generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(65_536).withIterations(3).withParallelism(1).withSalt(salt).build());
        byte[] result=new byte[32];generator.generateBytes(password,result);return result;
    }
}
