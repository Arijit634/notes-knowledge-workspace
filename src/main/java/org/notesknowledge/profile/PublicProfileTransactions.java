package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicProfileTransactions {
    record Activated(PublicProfileApi.View view,String retired) {
        @Override public String toString(){return "ActivatedPublicProfile[REDACTED]";}
    }
    private final PublicProfileRepository repository;
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    private final PublicExposureCoordinator exposure;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    PublicProfileTransactions(PublicProfileRepository repository,ObjectProvider<AccountEligibilityApi> eligibility,
        PublicExposureCoordinator exposure,DatabaseUuidV7Generator ids,Clock clock) {
        this.repository=repository;this.eligibility=eligibility;this.exposure=exposure;this.ids=ids;this.clock=clock;
    }
    @Transactional
    PublicProfileRepository.Source prepare(UUID owner,HttpServletRequest request) {
        eligibility.getObject().requireCurrentOwner(owner,request);
        var source=repository.source(owner,false);
        if(source.handle()==null)throw PublicProfileRepository.required();
        return source;
    }
    @Transactional
    Activated activate(PublicProfileRepository.Source captured,UUID avatar,String reference,HttpServletRequest request) {
        eligibility.getObject().requireCurrentOwner(captured.owner(),request);
        var current=repository.source(captured.owner(),true);
        if(!current.equals(captured))throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
        exposure.denyBoundary(captured.owner());
        var previous=repository.owner(captured.owner()).orElse(null);
        if(previous!=null&&previous.active()&&Objects.equals(previous.handle(),current.handle())
            &&Objects.equals(previous.displayName(),current.name())&&Objects.equals(previous.biography(),current.biography())
            &&Objects.equals(previous.sourceAvatar(),current.avatar()))return new Activated(PublicProfileApi.view(previous),reference);
        repository.activate(ids.generate(),current,avatar,reference,clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return new Activated(PublicProfileApi.view(repository.owner(captured.owner()).orElseThrow()),previous==null?null:previous.reference());
    }
}
