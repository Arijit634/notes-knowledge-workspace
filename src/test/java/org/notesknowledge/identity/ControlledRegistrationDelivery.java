package org.notesknowledge.identity;

import java.time.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;

/** Synthetic mailbox boundary only; capabilities, encryption, worker and verification remain real. */
@TestConfiguration(proxyBeanMethods=false)
public class ControlledRegistrationDelivery {
    @Bean Mailbox syntheticRegistrationMailbox(){return new Mailbox();}
    static class Mailbox implements SecurityEmailProviderPort {
        private String token;
        @Override public Outcome submit(String recipient,SecurityEmailMessageRenderer.Message message){
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw new AssertionError("External submission in transaction");
            String body=message.body();int start=body.indexOf("#token=")+7;
            token=body.substring(start,body.indexOf("\nThis link",start));
            return Outcome.SUBMITTED;
        }
        @Override public String toString(){return "SyntheticMailbox[REDACTED]";}
    }
    public static String verificationToken(ApplicationContext app){
        var claims=app.getBean(SecurityEmailDeliveryRepository.class).claimReady(app.getBean(Clock.class).instant(),
            new LeaseOwner("synthetic_custody_worker"),new LeasePolicy(Duration.ofMinutes(2),10),10);
        if(claims.size()!=1)throw new AssertionError("Expected one synthetic verification delivery");
        app.getBean(SecurityEmailWorker.class).process(claims.getFirst());
        var mailbox=app.getBean(Mailbox.class);String token=mailbox.token;mailbox.token=null;
        if(token==null)throw new AssertionError("Synthetic delivery unavailable");return token;
    }
}
