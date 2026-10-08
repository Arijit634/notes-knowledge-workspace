package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.PublicStorageTelemetry;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.ResponseStreamInterruptedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicAvatarService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final PublicProfileTransactions transactions;
    private final PublicProfileRepository repository;
    private final PublicProfileApi profiles;
    private final AvatarObjectStore source;
    private final PublicAvatarObjectStore store;
    private final AvatarValidator validator;
    private final DatabaseUuidV7Generator ids;
    private final PublicExposureCoordinator exposure;
    private final Clock clock;
    private final PublicStorageTelemetry telemetry;
    private final Semaphore capacity=new Semaphore(2);
    // Guards this replica's pre-commit staging from local reconciliation; authority is persisted.
    private final ReentrantReadWriteLock staging=new ReentrantReadWriteLock(true);
    PublicAvatarService(PublicProfileTransactions transactions,PublicProfileRepository repository,PublicProfileApi profiles,
        AvatarObjectStore source,PublicAvatarObjectStore store,AvatarValidator validator,DatabaseUuidV7Generator ids,
        PublicExposureCoordinator exposure,Clock clock,PublicStorageTelemetry telemetry) {
        this.transactions=transactions;this.repository=repository;this.profiles=profiles;this.source=source;
        this.store=store;this.validator=validator;this.ids=ids;this.exposure=exposure;this.clock=clock;this.telemetry=telemetry;
    }
    @Transactional(propagation=Propagation.NEVER)
    PublicProfileApi.View activate(UUID owner,HttpServletRequest request) {
        if(!capacity.tryAcquire())throw unavailable();
        staging.readLock().lock(); String reference=null; boolean committed=false;var started=clock.instant();
        try {
            var captured=transactions.prepare(owner,request); UUID avatar=null;
            if(captured.avatar()!=null) {
                if(captured.size()==null||captured.size()<1||captured.size()>AvatarValidator.OUTPUT_BYTES
                    ||captured.reference()==null||!captured.reference().matches("private-avatar/[0-9a-f]{64}"))throw unavailable();
                byte[] bytes;
                try(var input=source.open(captured.reference(),captured.size())) {
                    if(input==null)throw unavailable(); bytes=input.readNBytes(Math.toIntExact(captured.size())+1);
                }
                if(bytes.length!=captured.size())throw unavailable();
                AvatarValidator.Canonical canonical;
                try{canonical=validator.validate(new ByteArrayInputStream(bytes),"avatar");}
                catch(ApiFailureException invalidStoredSource){throw unavailable();}
                if(!canonical.mediaType().equals(captured.type())||canonical.width()!=captured.width()
                    ||canonical.height()!=captured.height())throw unavailable();
                byte[] random=new byte[32];RANDOM.nextBytes(random);
                reference="public-profile-avatar/"+HexFormat.of().formatHex(random);avatar=ids.generate();
                try(var sample=telemetry.start(PublicStorageTelemetry.ObjectClass.PUBLIC_AVATAR,PublicStorageTelemetry.Operation.COPY)){
                    store.write(reference,bytes);sample.success();
                }
            }
            if(!clock.instant().isBefore(started.plus(Duration.ofMinutes(5))))throw unavailable();
            var result=transactions.activate(captured,avatar,reference,request);committed=true;
            clean(result.retired());org.slf4j.LoggerFactory.getLogger(PublicAvatarService.class).info("public_profile_activation outcome=committed");return result.view();
        } catch(IOException failure){throw unavailable();}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException failure){throw failure;}
        catch(ApiFailureException failure){throw failure;}
        catch(RuntimeException failure){throw unavailable();}
        finally {if(!committed){clean(reference);org.slf4j.LoggerFactory.getLogger(PublicAvatarService.class).warn("public_profile_activation outcome=rejected");}
            staging.readLock().unlock();capacity.release();}
    }
    @Transactional(propagation=Propagation.NEVER)
    void stream(String handle,UUID avatar,HttpServletResponse response) {
        var author=profiles.resolveByHandle(handle);
        try(var lease=exposure.readLease(author.owner())) {
            var current=profiles.resolveByHandle(handle);
            var p=repository.id(current.projectionId()).orElseThrow(PublicProfileApi::missing);
            if(!avatar.equals(p.avatar())||p.size()==null||p.size()<1||p.size()>AvatarValidator.OUTPUT_BYTES)throw PublicProfileApi.missing();
            byte[] bytes;
            try(var sample=telemetry.start(PublicStorageTelemetry.ObjectClass.PUBLIC_AVATAR,PublicStorageTelemetry.Operation.READ);
                var input=store.open(p.reference(),p.size())) {
                if(input==null)throw PublicProfileApi.missing();bytes=input.readNBytes(Math.toIntExact(p.size())+1);
                if(bytes.length==p.size())sample.success();
            } catch(IOException failure){throw unavailable();}
            if(bytes.length!=p.size())throw unavailable();
            // Storage read can race external removal; exact current projection is rechecked before headers/bytes.
            var latest=profiles.resolveByHandle(handle);
            if(latest.generation()!=current.generation()||!latest.projectionId().equals(current.projectionId()))throw PublicProfileApi.missing();
            response.setContentType(p.type());response.setContentLengthLong(bytes.length);
            response.setHeader("Content-Disposition","inline; filename=\"avatar\"");
            response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","no-store");
            try {response.getOutputStream().write(bytes);response.flushBuffer();}
            catch(IOException failure){throw new ResponseStreamInterruptedException();}
        } catch(ResponseStreamInterruptedException failure){throw failure;}
        catch(RuntimeException failure) {
            if(response.isCommitted())throw new ResponseStreamInterruptedException();
            response.resetBuffer();response.setHeader("Content-Length",null);response.setHeader("Content-Disposition",null);
            response.setContentType(null);
            if(failure instanceof ApiFailureException api)throw api;
            throw unavailable();
        }
    }
    private void clean(String reference) {
        if(reference==null)return;
        try {if(!repository.referenced(reference))try(var sample=telemetry.start(PublicStorageTelemetry.ObjectClass.PUBLIC_AVATAR,PublicStorageTelemetry.Operation.DELETE)){
            store.delete(reference);sample.success();}}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(PublicAvatarService.class).warn("public_avatar_cleanup_deferred");}
    }
    /** Bounded internal reconciliation, never a scheduler or HTTP endpoint. */
    @Transactional(propagation=Propagation.NEVER)
    String reconcile(String after) {
        staging.writeLock().lock();
        try {
            var cutoff=clock.instant().minus(Duration.ofHours(24));
            var objects=store.inventoryBefore(cutoff,after,100);
            if(objects.size()>100)throw unavailable();String cursor=after;
            for(var object:objects) {
                if(object.reference()==null||!object.reference().matches("public-profile-avatar/[0-9a-f]{64}")
                    ||object.createdAt()==null||!object.createdAt().isBefore(cutoff)
                    ||cursor!=null&&object.reference().compareTo(cursor)<=0)throw unavailable();
                clean(object.reference());cursor=object.reference();
            }
            return objects.size()==100?cursor:null;
        }finally{staging.writeLock().unlock();}
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
