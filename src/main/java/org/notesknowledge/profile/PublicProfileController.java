package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.notesknowledge.profile.spi.ActiveAuthorPublications;
import org.notesknowledge.security.PublicReadRateControl;
import org.notesknowledge.websupport.CursorPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class PublicProfileController {
    private final PublicProfileApi profiles;
    private final PublicAvatarService avatars;
    private final ObjectProvider<ActiveAuthorPublications> publications;
    private final PublicReadRateControl rate;
    PublicProfileController(PublicProfileApi profiles,PublicAvatarService avatars,ObjectProvider<ActiveAuthorPublications> publications,PublicReadRateControl rate) {
        this.profiles=profiles;this.avatars=avatars;this.publications=publications;this.rate=rate;
    }
    @PutMapping("/api/me/public-profile")
    ResponseEntity<PublicProfileApi.View> activate(@RequestBody(required=false) java.util.Map<String,Object> body,HttpServletRequest request){
        if(body!=null&&!body.isEmpty())throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.MALFORMED_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.activate(ProfileController.owner(),request));}
    @GetMapping("/api/public/profiles/{handle}")
    ResponseEntity<PublicProfileApi.View> read(@PathVariable String handle,HttpServletRequest request){rate.check(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.resolveByHandle(handle).view());}
    @GetMapping("/api/public/profiles/{handle}/publications")
    ResponseEntity<CursorPage<ActiveAuthorPublications.Item>> page(@PathVariable String handle,@RequestParam(required=false) Integer limit,
        @RequestParam(required=false) String cursor,HttpServletRequest request){rate.check(request);var author=profiles.resolveByHandle(handle);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publications.getObject().page(author.projectionId(),author.generation(),limit,cursor));}
    @GetMapping("/api/public/profiles/{handle}/avatar/{publicAvatarId}/content")
    void avatar(@PathVariable String handle,@PathVariable UUID publicAvatarId,HttpServletRequest request,HttpServletResponse response){rate.check(request);avatars.stream(handle,publicAvatarId,response);}
}
