package org.notesknowledge.profile;

import java.util.Map;
import java.util.UUID;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public-only author composition. No private Profile repository participates in public reads. */
@Service
public class PublicProfileApi {
    public record View(String handle,String displayName,String biography,String avatarUrl,Map<String,String> links) {
        @Override public String toString(){return "PublicProfileView[REDACTED]";}
    }
    public record Author(UUID projectionId,UUID owner,long generation,View view) {
        @Override public String toString(){return "PublicAuthor[REDACTED]";}
    }
    private final PublicProfileRepository repository;
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    PublicProfileApi(PublicProfileRepository repository,ObjectProvider<AccountEligibilityApi> eligibility){this.repository=repository;this.eligibility=eligibility;}
    @Transactional(readOnly=true)
    public Author requireActiveProjection(UUID owner) {
        var p=repository.owner(owner).filter(PublicProfileRepository.Projection::active).orElseThrow(PublicProfileRepository::required);
        return requireEligible(p,true);
    }
    @Transactional(readOnly=true)
    public Author resolveByHandle(String handle){return requireEligible(repository.handle(normalize(handle)).orElseThrow(PublicProfileApi::missing),false);}
    @Transactional(readOnly=true)
    public Author resolveById(UUID id){return requireEligible(repository.id(id).orElseThrow(PublicProfileApi::missing),false);}
    private Author requireEligible(PublicProfileRepository.Projection p,boolean owner) {
        if(eligibility.getIfAvailable()==null||!eligibility.getObject().isPubliclyEligible(p.owner()))
            throw owner?PublicProfileRepository.required():missing();
        return new Author(p.id(),p.owner(),p.generation(),view(p));
    }
    static View view(PublicProfileRepository.Projection p){return new View(p.handle(),p.displayName(),p.biography(),
        p.avatar()==null?null:"/api/public/profiles/"+p.handle()+"/avatar/"+p.avatar()+"/content",
        Map.of("publications","/api/public/profiles/"+p.handle()+"/publications"));}
    static String normalize(String handle){if(handle==null||!handle.matches("[A-Za-z][A-Za-z0-9_]{2,29}"))
        throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);return handle.toLowerCase(java.util.Locale.ROOT);}
    static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
