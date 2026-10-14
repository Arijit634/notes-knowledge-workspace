package org.notesknowledge.publishing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.discovery.PublicProjectionApi;
import org.notesknowledge.security.PublicReadRateControl;
import org.springframework.stereotype.Service;

/** Owner-module orchestration; HTTP framing does not own repositories or read leases. */
@Service
final class PublicationReadService {
    private final PublicationRepository repository;
    private final PublicationTransactions transactions;
    private final PublicExposureCoordinator exposure;
    private final PublicProjectionApi discovery;
    private final PublicReadRateControl rate;

    PublicationReadService(PublicationRepository repository, PublicationTransactions transactions,
            PublicExposureCoordinator exposure, PublicProjectionApi discovery, PublicReadRateControl rate) {
        this.repository = repository;
        this.transactions = transactions;
        this.exposure = exposure;
        this.discovery = discovery;
        this.rate = rate;
    }

    PublicationTransactions.PublicView read(UUID id, HttpServletRequest request) {
        rate.check(request);
        var initial = repository.active(id).orElseThrow(PublicationTransactions::missing);
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        UUID viewer = authentication != null
                && authentication.getPrincipal() instanceof org.notesknowledge.identity.IdentitySessionPrincipal principal
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER"))
                ? principal.userId() : null;
        PublicationTransactions.PublicView result;
        try (var lease = exposure.readLease(initial.owner())) {
            result = transactions.publicView(id, viewer);
        }
        try {
            discovery.recordApproximateView(id, initial.generation(), rate.viewKey(request, viewer));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(PublicationController.class).warn("public_view_aggregate_deferred");
        }
        org.slf4j.LoggerFactory.getLogger(PublicationController.class).debug("public_publication_read outcome=success");
        return result;
    }
}
