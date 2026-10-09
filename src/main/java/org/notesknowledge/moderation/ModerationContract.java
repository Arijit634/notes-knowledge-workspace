package org.notesknowledge.moderation;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.publishing.PublicationModerationApi;
import org.notesknowledge.websupport.ApiFailureException;

final class ModerationContract {
    static final Set<String> CATEGORIES=Set.of("harmfulContent","spam","harassment","other");
    static final Set<String> STATES=Set.of("open","under_review","dismissed","actioned","closed");
    enum Consequence { none,removePublication,removePublicationAndSuspendResponsibleAccount }
    record Intake(String category,String description) {
        void validate(){if(!CATEGORIES.contains(category==null?"":category)||description==null||description.isBlank()
            ||description.length()>2000||description.codePoints().anyMatch(Character::isISOControl))throw invalid();}
        @Override public String toString(){return "ReportIntake[REDACTED]";}
    }
    record DecisionRequest(Consequence consequence,String reasonCode) {
        void validate(){if(consequence==null||reasonCode==null||!(consequence==Consequence.none
            ?Set.of("noPolicyViolation","insufficientEvidence").contains(reasonCode):reasonCode.equals("confirmedPolicyViolation")))throw invalid();}
    }
    record Report(UUID id,UUID publicationId,long generation,String category,String description,String status,
        Instant submittedAt,Instant reviewedAt,Instant resolvedAt) {
        @Override public String toString(){return "Report[REDACTED]";}
    }
    record Receipt(UUID id,UUID publicationId,String category,String description,String status,Instant submittedAt) {
        @Override public String toString(){return "ReportReceipt[REDACTED]";}
    }
    record Decision(UUID id,UUID reportId,Consequence consequence,String reasonCode,Outcomes outcomes,Instant decidedAt) { }
    record Outcomes(String publication,String account) { }
    record Audit(String action,String outcome,String reason,Instant occurredAt) { }
    record Detail(Report report,PublicationModerationApi.Evidence publicEvidence,boolean targetAvailable,Decision decision,List<Audit> audit) { }
    static ApiFailureException invalid(){return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);}
    static ApiFailureException conflict(){return ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);}
    static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
