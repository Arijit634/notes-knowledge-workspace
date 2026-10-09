package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("DATABASE")
class KnowledgeMigrationPinsTest {
    @Test void historicalMigrationBytesRemainExactlyPinned() throws Exception {
        Map<String,String> expected=Map.ofEntries(
            Map.entry("V001__platform__spring_session.sql","9c4099d9f67f889181cd97dcebd347de2a9f1a3e58b384625aebe79717ea2258"),
            Map.entry("V002__identity__account_verification_and_security_email.sql","8697914be30364a9b7a3c5aa317496dadd7df23248644d0342063657b78f131a"),
            Map.entry("V003__identity__mfa_core.sql","ee55f02e322a91ee82a3944fcf4317d3baac3d4013a7e432266bf3ec60d61da3"),
            Map.entry("V004__identity__google_oidc_core.sql","1d1b712632df8f8002746e040816f681bb6eba663de0d6dc451933237bb2a80e"),
            Map.entry("V005__identity__application_session_descriptor.sql","8d80d2e8087e26f6aacc7fde8344b9c2356551258bb5e4a049e45c881ced8cd7"),
            Map.entry("V006__identity__privilege_assignment.sql","1ba7e3d54ee4acba1d359f87211ed97da7ba22e3182c520861ad039be3bbe352"),
            Map.entry("V007__notes__editor_core.sql","dcc5fd8739580c92ae424473565286a8eb59400317c0458258e3c5b7347c0356"),
            Map.entry("V008__notes__tags.sql","8858ad40c28e58dae2df5c24c0e74a29748217bee85446adff7a27e603adfd8b"),
            Map.entry("V009__notes__versions.sql","5df3d231b769871582bb18bc5dbc4d7b7bf960a940ff87d2923242861539e24d"),
            Map.entry("V010__profile__private_core.sql","6e09b53f3791c823b401243e6223038d7c7a8894ec2e0f7af7502cb20606a410"),
            Map.entry("V011__profile__avatar_management.sql","6d109273280b5618f7d50b862378fb2ebe6c8b19527b60103db504350adf4336"),
            Map.entry("V012__notes__attachment_upload_core.sql","cc6c5ecd2f05556203d8f3e578fc682f7b19634309d20c54f8b2119ceba51288"),
            Map.entry("V013__notes__ordinary_search.sql","97c9b730fbb7c2b1d7aa43a092f7c75b5edf476ce6b9425a090747b3ea9a0480"),
            Map.entry("V014__knowledge__processing_foundation.sql","ce364901abf6794e16d7a26c76fb8f829c8747841225672b3d8f367b7b0461b3"),
            Map.entry("V015__knowledge__private_derivations.sql","0aec4c905d7ae906a38a02b185bcdb301dca3820ccf0ee65339bba6f13dd1655"),
            Map.entry("V016__knowledge__query_operations.sql","a50b059bed079083f174b590d787f094ca1b1be19cecbf44d6474155fc9ac722"),
            Map.entry("V017__publishing__public_core.sql","7e3d922f41457c12c9ebf89c7d6ef8845f95db7289da3d4e62fe5e1413e537cd"),
            Map.entry("V018__discovery__public_search_and_engagement.sql","6cefe6951bdeb239bf7e2c2f98292d286927dd95e0ddf0d685615aad2a8bc0c0"),
            Map.entry("V019__moderation__reports_and_decisions.sql","ca5784f5f865b2f0b809ce183ed78cbc6c75da477d5808e42464b31a76164982"));
        for(var e:expected.entrySet())try(var stream=getClass().getResourceAsStream("/db/migration/"+e.getKey())){
            assertThat(stream).as(e.getKey()).isNotNull();assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()))).as(e.getKey()).isEqualTo(e.getValue());
        }
    }
}
