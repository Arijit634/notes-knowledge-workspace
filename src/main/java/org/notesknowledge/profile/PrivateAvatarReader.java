package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.ResponseStreamInterruptedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded private custody outside transactions, followed by final persisted read admission. */
@Service
class PrivateAvatarReader {
    private final AvatarTransactions transactions;
    private final AvatarObjectStore store;
    private final AvatarValidator validator;
    private final Clock clock;
    private final Semaphore capacity=new Semaphore(2);
    PrivateAvatarReader(AvatarTransactions transactions,AvatarObjectStore store,AvatarValidator validator,Clock clock) {
        this.transactions=transactions;this.store=store;this.validator=validator;this.clock=clock;
    }
    @Transactional(propagation=Propagation.NEVER)
    void read(UUID owner,HttpServletRequest request,HttpServletResponse response) {
        if(!capacity.tryAcquire())throw unavailable();
        var started=clock.instant();
        try {
            var captured=transactions.current(owner,request);
            byte[] bytes;
            try(var input=store.open(captured.reference(),captured.size())) {
                if(input==null)throw unavailable();
                bytes=input.readNBytes(Math.toIntExact(captured.size())+1);
            }
            if(bytes.length!=captured.size())throw unavailable();
            AvatarValidator.Canonical image;
            try { image=validator.validate(new ByteArrayInputStream(bytes),"avatar"); }
            catch(ApiFailureException invalidStoredBytes){throw unavailable();}
            if(!image.mediaType().equals(captured.type())||image.width()!=captured.width()||image.height()!=captured.height()
                    ||!clock.instant().isBefore(started.plus(Duration.ofMinutes(5))))throw unavailable();
            // The completed short transaction is the byte-admission point. Reads already
            // admitted before a later removal cannot be recalled; later admissions recheck.
            if(!captured.equals(transactions.current(owner,request)))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
            response.setContentType(captured.type());response.setContentLengthLong(bytes.length);
            response.setHeader("Content-Disposition","inline; filename=\"avatar\"");
            response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","no-store");
            response.setHeader("Accept-Ranges","none");
            try {response.getOutputStream().write(bytes);response.flushBuffer();}
            catch(IOException interrupted){throw new ResponseStreamInterruptedException();}
        } catch(ResponseStreamInterruptedException interrupted){throw interrupted;}
        catch(Exception failure) {
            if(response.isCommitted())throw new ResponseStreamInterruptedException();
            response.resetBuffer();response.setHeader("Content-Length",null);response.setHeader("Content-Disposition",null);
            response.setContentType(null);
            if(failure instanceof ApiFailureException api)throw api;
            throw unavailable();
        } finally {capacity.release();}
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
