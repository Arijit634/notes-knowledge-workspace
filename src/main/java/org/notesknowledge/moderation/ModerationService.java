package org.notesknowledge.moderation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.notesknowledge.DispatchCoordinator;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;

@Service
class ModerationService {
    private final ModerationTransactions transactions;
    private final DispatchCoordinator dispatch;
    ModerationService(ModerationTransactions transactions,DispatchCoordinator dispatch){this.transactions=transactions;this.dispatch=dispatch;}
    ModerationContract.Decision decide(UUID actor,UUID report,ModerationContract.DecisionRequest input,HttpServletRequest request) {
        input.validate();var target=transactions.preflight(actor,report,request);
        if(input.consequence()!=ModerationContract.Consequence.removePublicationAndSuspendResponsibleAccount)
            return transactions.decide(actor,report,target,input,request);
        // Drain private AI dispatch BEFORE starting the local ACID suspension transaction.
        var handle=dispatch.ownerMutation(target.responsible());ModerationContract.Decision result;
        try(handle){result=transactions.decide(actor,report,target,input,request);}
        if(!handle.safelyReleased())throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        return result;
    }
}
