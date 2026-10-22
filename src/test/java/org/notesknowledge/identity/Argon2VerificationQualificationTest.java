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
    @Test void dependencyMatchesThePublishedRfc9106Argon2idVector() {
        // RFC 9106 section 5.3: an independent known answer, not a value computed
        // by the same dependency under test. These small parameters belong ONLY
        // to the published vector; the application parameters stay unchanged.
        byte[] password=repeated(32,1),salt=repeated(16,2);
        byte[] secret=repeated(8,3),associated=repeated(12,4),actual=new byte[32];
        var generator=new Argon2BytesGenerator();
        var parameters=new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(32)
                .withIterations(3).withParallelism(4).withSalt(salt)
                .withSecret(secret).withAdditional(associated).build();
        try {
            generator.init(parameters);generator.generateBytes(password,actual);
            byte[] expected=HexFormat.of().parseHex(
                    "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659");
            assertThat(java.security.MessageDigest.isEqual(actual,expected))
                    .as("published Argon2id known answer").isTrue();
        } finally {
            parameters.clear();
            for(byte[] value:List.of(password,salt,secret,associated,actual))Arrays.fill(value,(byte)0);
        }
    }
    private static byte[] repeated(int size,int value) {
        byte[] result=new byte[size];Arrays.fill(result,(byte)value);return result;
    }
    @Test void eachFreshSpringHashAgreesWithDirectDependencyOnItsActualEncodedSalt(){
        var encoder=new IdentityPasswordConfiguration().identityPasswordEncoder();
        // Bounded diagnostic, not rerun-until-green. Verify ALL hashes with both paths.
        for(int i=0;i<16;i++){
            String value="synthetic-dependency-oracle-"+i+"-\u03bb";
            String encoded=encoder.encode(value);
            String[] fields=encoded.substring("{argon2id}".length()).split("\\$");
            assertThat(fields.length==6&&fields[1].equals("argon2id")&&fields[2].equals("v=19")
                &&fields[3].equals("m=65536,t=3,p=1")).as("approved encoding structure %d",i).isTrue();
            byte[] salt=Base64.getDecoder().decode(fields[4]),actual=Base64.getDecoder().decode(fields[5]);
            byte[] password=value.getBytes(StandardCharsets.UTF_8),expected=derive(password,salt);
            try {
                assertThat(salt.length==16&&actual.length==32).as("approved output shape %d",i).isTrue();
                assertThat(java.security.MessageDigest.isEqual(expected,actual)).as("direct dependency agrees with fresh encoding %d",i).isTrue();
                assertThat(encoder.matches(value,encoded)).as("Spring correct-password verification %d",i).isTrue();
                assertThat(encoder.matches(value+"-wrong",encoded)).as("wrong password rejected %d",i).isFalse();
            } finally {Arrays.fill(salt,(byte)0);Arrays.fill(actual,(byte)0);Arrays.fill(password,(byte)0);Arrays.fill(expected,(byte)0);}
        }
    }
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
