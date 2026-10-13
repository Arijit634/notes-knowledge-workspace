package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.notesknowledge.identity.ControlledRegistrationDelivery;
import org.notesknowledge.identity.SyntheticRecentProof;
import org.notesknowledge.profile.ConfiguredAvatarStorageFixtures;
import org.notesknowledge.publishing.ConfiguredPublicMediaStorageFixtures;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;

/** Cross-module synthetic journey driven through the real application APIs.
 * Only email/object/provider network boundaries are controlled. No private byte URL is invented. */
public final class ConfiguredObjectCustodyJourney {
    private ConfiguredObjectCustodyJourney(){}
    public record State(String avatarUrl,byte[] avatar,String publication,String mediaUrl,byte[] media) {
        @Override public String toString(){return "CustodyJourney[REDACTED]";}
    }
    public static UUID registerAndLogin(ConfigurableApplicationContext app,MockMvc mvc)throws Exception {
        var json=app.getBean(ObjectMapper.class);var jdbc=app.getBean(JdbcTemplate.class);
        String email="synthetic-custody@example.test",password="SyntheticCustodyPassword-2026!";
        var initial=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var cookie=initial.getCookie("SESSION");String csrf=json.readTree(initial.getContentAsString()).get("csrfToken").asText();
        mvc.perform(post("/api/auth/registrations").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json")
            .content(json.writeValueAsString(Map.of("email",email,"password",password)))).andExpect(status().isAccepted());
        String token=ControlledRegistrationDelivery.verificationToken(app);
        mvc.perform(post("/api/auth/email-verification/confirmations").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json")
            .content(json.writeValueAsString(Map.of("token",token)))).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login/password").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json")
            .content(json.writeValueAsString(Map.of("email",email,"password","IncorrectSyntheticPassword-2026!")))).andExpect(status().isUnauthorized());
        var logged=mvc.perform(post("/api/auth/login/password").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json")
            .content(json.writeValueAsString(Map.of("email",email,"password",password)))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(logged.getCookie("SESSION")).isNotNull();
        assertThat(logged.getCookie("SESSION").getValue()).isNotEqualTo(cookie.getValue());
        return jdbc.queryForObject("select user_id from identity.account where canonical_email=?",UUID.class,email);
    }
    public static State prepare(ConfigurableApplicationContext app,MockMvc mvc,UUID owner,Cookie cookie,String csrf,UUID note,UUID image)throws Exception {
        var json=app.getBean(ObjectMapper.class);String handle="writer_"+owner.toString().replace("-","").substring(12);
        mvc.perform(put("/api/me/profile").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json")
            .content(json.writeValueAsString(Map.of("displayName","Synthetic Reader","biography","Fictional public biography","handle",handle)))).andExpect(status().isOk());
        ConfiguredAvatarStorageFixtures.ambiguousUpload(app);ConfiguredAvatarStorageFixtures.failDeletion(app,true);
        uploadAvatar(mvc,cookie,csrf,"png").andExpect(status().isServiceUnavailable());
        assertThat(app.getBean(JdbcTemplate.class).queryForObject("select count(*) from profile.avatar_asset",Integer.class)).isZero();
        assertThat(ConfiguredAvatarStorageFixtures.ageObjects(app)).isEqualTo(1);
        ConfiguredAvatarStorageFixtures.failDeletion(app,false);ConfiguredAvatarStorageFixtures.reconcile(app);
        assertThat(ConfiguredAvatarStorageFixtures.ageObjects(app)).isZero();
        uploadAvatar(mvc,cookie,csrf,"png").andExpect(status().isOk());
        // Deterministic source invalidation between the real copy and final DB
        // transaction: stale bytes must not become an approved public projection.
        var jdbc=app.getBean(JdbcTemplate.class);
        UUID selected=jdbc.queryForObject("select selected_avatar_id from profile.profile where user_id=?",UUID.class,owner);
        ConfiguredAvatarStorageFixtures.afterWrite(app,key->{
            if(key.startsWith("public-profile-avatar/"))
                jdbc.update("update profile.profile set selected_avatar_id=null,updated_at=clock_timestamp() where user_id=?",owner);
        });
        try{mvc.perform(put("/api/me/public-profile").cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isPreconditionFailed());}
        finally{ConfiguredAvatarStorageFixtures.afterWrite(app,key->{});}
        mvc.perform(get("/api/public/profiles/"+handle)).andExpect(status().isNotFound());
        jdbc.update("update profile.profile set selected_avatar_id=?,updated_at=clock_timestamp() where user_id=?",selected,owner);
        var projected=mvc.perform(put("/api/me/public-profile").cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(projected.getContentAsString()).doesNotContain(owner.toString(),"object_reference","private-avatar/");
        String avatarUrl=json.readTree(projected.getContentAsString()).get("avatarUrl").asText();
        byte[] avatar=read(mvc,avatarUrl);
        uploadAvatar(mvc,cookie,csrf,"jpeg").andExpect(status().isOk());
        assertThat(read(mvc,avatarUrl)).isEqualTo(avatar);
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
        // Unsafe anonymous requests are independently denied at the CSRF boundary.
        mvc.perform(put("/api/me/profile/avatar")).andExpect(status().isForbidden());
        // Current session proof is production-validated; only construction is synthetic here.
        recent(app,cookie,owner);
        String noteTag=mvc.perform(get("/api/notes/"+note).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        var selection=Map.of("selectedAttachmentIds",List.of(image));
        var preview=mvc.perform(post("/api/notes/"+note+"/publication-preview").cookie(cookie).header("X-CSRF-TOKEN",csrf)
            .header("If-Match",noteTag).contentType("application/json").content(json.writeValueAsString(selection))).andExpect(status().isOk()).andReturn().getResponse();
        String payload=json.writeValueAsString(Map.of("selectedAttachmentIds",List.of(image),"previewFingerprint",
            json.readTree(preview.getContentAsString()).get("previewFingerprint").asText()));
        ConfiguredPublicMediaStorageFixtures.ambiguousUpload(app);ConfiguredPublicMediaStorageFixtures.failDeletion(app,true);
        create(mvc,cookie,csrf,note,noteTag,payload).andExpect(status().isServiceUnavailable());
        assertThat(app.getBean(JdbcTemplate.class).queryForObject("select count(*) from publishing.publication",Integer.class)).isZero();
        assertThat(ConfiguredPublicMediaStorageFixtures.ageObjects(app)).isEqualTo(1);
        ConfiguredPublicMediaStorageFixtures.failDeletion(app,false);ConfiguredPublicMediaStorageFixtures.reconcile(app);
        assertThat(ConfiguredPublicMediaStorageFixtures.ageObjects(app)).isZero();
        var publication=create(mvc,cookie,csrf,note,noteTag,payload).andExpect(status().isCreated()).andReturn().getResponse();
        var document=json.readTree(publication.getContentAsString());String publicationId=document.get("id").asText();
        String mediaUrl=document.get("media").get(0).get("contentUrl").asText();byte[] media=read(mvc,mediaUrl);
        var publicDocument=mvc.perform(get("/api/public/publications/"+publicationId)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(publicDocument.getContentAsString()).doesNotContain(note.toString(),image.toString(),owner.toString(),"private-attachment/","public-publication-media/");
        return new State(avatarUrl,avatar,publicationId,mediaUrl,media);
    }
    public static void afterRestart(ConfigurableApplicationContext app,MockMvc mvc,State state,Cookie cookie,String csrf)throws Exception {
        assertThat(read(mvc,state.avatarUrl())).isEqualTo(state.avatar());
        assertThat(read(mvc,state.mediaUrl())).isEqualTo(state.media());
        var avatar=mvc.perform(get(state.avatarUrl())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(avatar.getHeader("Accept-Ranges")).isNull();assertThat(avatar.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(avatar.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        var range=mvc.perform(get(state.mediaUrl()).header("Range","bytes=3-19")).andExpect(status().isPartialContent()).andReturn().getResponse();
        assertThat(range.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(state.media(),3,20));
        // This refresh must read the private JPEG persisted before application restart.
        String refreshed=app.getBean(ObjectMapper.class).readTree(mvc.perform(put("/api/me/public-profile").cookie(cookie)
            .header("X-CSRF-TOKEN",csrf)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("avatarUrl").asText();
        assertThat(refreshed).isNotEqualTo(state.avatarUrl());mvc.perform(get(state.avatarUrl())).andExpect(status().isNotFound());
        byte[] copied=read(mvc,refreshed);ConfiguredAvatarStorageFixtures.failDeletion(app,true);
        mvc.perform(delete("/api/me/profile/avatar").cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        assertThat(read(mvc,refreshed)).isEqualTo(copied);
        var cleared=mvc.perform(put("/api/me/public-profile").cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(app.getBean(ObjectMapper.class).readTree(cleared.getContentAsString()).get("avatarUrl").isNull()).isTrue();
        mvc.perform(get(refreshed)).andExpect(status().isNotFound());
        ConfiguredAvatarStorageFixtures.ageObjects(app);ConfiguredAvatarStorageFixtures.failDeletion(app,false);
        ConfiguredAvatarStorageFixtures.reconcile(app);assertThat(ConfiguredAvatarStorageFixtures.ageObjects(app)).isZero();
    }
    public static void finish(ConfigurableApplicationContext app,MockMvc mvc,State state,UUID owner,Cookie cookie,String csrf)throws Exception {
        // Private Attachment logical and physical deletion already ran: independent public bytes survive.
        assertThat(read(mvc,state.mediaUrl())).isEqualTo(state.media());recent(app,cookie,owner);
        String path="/api/me/publications/"+state.publication();
        String tag=mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        ConfiguredPublicMediaStorageFixtures.failDeletion(app,true);
        mvc.perform(post(path+"/unpublish").cookie(cookie).header("X-CSRF-TOKEN",csrf).header("If-Match",tag)).andExpect(status().isOk());
        mvc.perform(get(state.mediaUrl())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/publications/"+state.publication())).andExpect(status().isNotFound());
        assertThat(ConfiguredPublicMediaStorageFixtures.ageObjects(app)).isPositive();
        ConfiguredPublicMediaStorageFixtures.failDeletion(app,false);ConfiguredPublicMediaStorageFixtures.reconcile(app);
        assertThat(ConfiguredPublicMediaStorageFixtures.ageObjects(app)).isZero();
    }
    private static ResultActions uploadAvatar(MockMvc mvc,Cookie cookie,String csrf,String format)throws Exception {
        return mvc.perform(multipart("/api/me/profile/avatar").file(new MockMultipartFile("file","synthetic."+format,
            "image/"+format,ConfiguredAvatarStorageFixtures.image(format))).with(r->{r.setMethod("PUT");return r;})
            .cookie(cookie).header("X-CSRF-TOKEN",csrf));
    }
    private static ResultActions create(MockMvc mvc,Cookie cookie,String csrf,UUID note,String tag,String body)throws Exception{
        return mvc.perform(post("/api/notes/"+note+"/publication").cookie(cookie).header("X-CSRF-TOKEN",csrf).header("If-Match",tag)
            .contentType("application/json").content(body));
    }
    private static byte[] read(MockMvc mvc,String url)throws Exception{return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();}
    @SuppressWarnings({"rawtypes","unchecked"}) private static void recent(ConfigurableApplicationContext app,Cookie cookie,UUID owner){
        var sessions=app.getBean(SessionRepository.class);
        var id=new String(Base64.getDecoder().decode(cookie.getValue()),java.nio.charset.StandardCharsets.UTF_8);
        Session session=sessions.findById(id);SyntheticRecentProof.password(session,owner,app.getBean(java.time.Clock.class).instant());sessions.save(session);
    }
}
