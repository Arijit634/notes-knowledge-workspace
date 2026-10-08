package org.notesknowledge.publishing;

import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.PublicStorageTelemetry;
import org.notesknowledge.notes.AttachmentSourceApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.NEVER)
class PublicationService {
    record Preview(String title,String markdown,List<String> tags,long sourceRevision,List<PreviewMedia> media,
        List<String> warnings,String previewFingerprint) {
        @Override public String toString(){return "PublicationPreview[REDACTED]";}
    }
    record PreviewMedia(UUID attachmentId,String mediaKind,String mediaType,String displayName,long sizeBytes) { }
    private static final SecureRandom RANDOM=new SecureRandom();
    private final PublicationTransactions transactions;
    private final PublicationPreviewFingerprintCodec fingerprints;
    private final AttachmentSourceApi attachments;
    private final PublicMediaObjectStore objects;
    private final PublicationRepository repository;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final PublicationTelemetry telemetry;
    private final PublicStorageTelemetry storageTelemetry;
    private final Semaphore capacity=new Semaphore(2);
    private final ReentrantReadWriteLock staging=new ReentrantReadWriteLock(true);
    PublicationService(PublicationTransactions transactions,PublicationPreviewFingerprintCodec fingerprints,
        AttachmentSourceApi attachments,PublicMediaObjectStore objects,PublicationRepository repository,DatabaseUuidV7Generator ids,Clock clock,PublicationTelemetry telemetry,PublicStorageTelemetry storageTelemetry) {
        this.transactions=transactions;this.fingerprints=fingerprints;this.attachments=attachments;this.objects=objects;
        this.repository=repository;this.ids=ids;this.clock=clock;this.telemetry=telemetry;this.storageTelemetry=storageTelemetry;
    }
    Preview preview(UUID owner,UUID note,String ifMatch,List<UUID> selected,HttpServletRequest request) {
        var p=transactions.preview(owner,note,ifMatch,selected,request);
        return new Preview(p.source().title(),p.source().markdown(),p.source().tags(),p.source().revision(),p.selection().stream()
            .map(m->new PreviewMedia(m.id(),m.kind(),m.type(),m.name(),m.size())).toList(),
            List.of("Only explicitly selected media will be copied. Later private changes do not update this public copy."),fingerprints.issue(p));
    }
    PublicationTransactions.Etagged create(UUID owner,UUID note,String ifMatch,List<UUID> selected,String fingerprint,HttpServletRequest request) {
        try{var p=transactions.preview(owner,note,ifMatch,selected,request);
            return publish(p,ids.generate(),true,"create",ifMatch,fingerprint,request);}
        catch(RuntimeException failure){telemetry.rejected(PublicationTelemetry.Command.CREATE);throw failure;}
    }
    PublicationTransactions.Etagged replace(UUID owner,UUID id,String ifMatch,List<UUID> selected,String fingerprint,String action,HttpServletRequest request) {
        try{var p=transactions.prepareReplacement(owner,id,ifMatch,selected,request);
            return publish(p,id,false,action,ifMatch,fingerprint,request);}
        catch(RuntimeException failure){telemetry.rejected(action.equals("update")?PublicationTelemetry.Command.UPDATE:PublicationTelemetry.Command.REPUBLISH);throw failure;}
    }
    private PublicationTransactions.Etagged publish(PublicationTransactions.Prepared p,UUID id,boolean create,String action,
        String ifMatch,String fingerprint,HttpServletRequest request) {
        fingerprints.require(fingerprint,p);
        if(!capacity.tryAcquire())throw unavailable();
        staging.readLock().lock();var copies=new ArrayList<PublicationRecord.Media>();boolean committed=false;
        var started=clock.instant();
        try {
            for(var s:p.selection()) {
                byte[] random=new byte[32];RANDOM.nextBytes(random);
                var ref="public-publication-media/"+HexFormat.of().formatHex(random);
                var copy=new PublicationRecord.Media(ids.generate(),id,1,1,s.id(),ref,s.kind(),s.type(),s.name(),s.size(),s.width(),s.height(),s.duration(),s.pages(),copies.size());
                copies.add(copy);
                try(var sample=storageTelemetry.start(PublicStorageTelemetry.ObjectClass.PUBLIC_MEDIA,PublicStorageTelemetry.Operation.COPY);
                    var input=attachments.openPreparedSource(s)) {
                    if(input==null)throw unavailable();
                    var bounded=new ExactInput(input,s.size());objects.write(ref,bounded,s.size());
                    if(bounded.remaining!=0||input.read()!=-1)throw unavailable();
                    sample.success();
                }
            }
            // Inventory age is 24h; no aged in-flight copy may later become current.
            if(!clock.instant().isBefore(started.plus(Duration.ofMinutes(5))))throw unavailable();
            var result=transactions.commit(p,id,create,action,ifMatch,fingerprint,copies,request);committed=true;
            result.retired().forEach(this::clean);telemetry.command(create?PublicationTelemetry.Command.CREATE:
                action.equals("update")?PublicationTelemetry.Command.UPDATE:PublicationTelemetry.Command.REPUBLISH);return result.result();
        }catch(IOException|org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException failure){throw unavailable();}
        catch(ApiFailureException failure){throw failure;}
        catch(RuntimeException failure){throw unavailable();}
        finally {if(!committed)copies.forEach(m->clean(m.reference()));staging.readLock().unlock();capacity.release();}
    }
    PublicationTransactions.Etagged unpublish(UUID owner,UUID id,String ifMatch,HttpServletRequest request) {
        try{var result=transactions.unpublish(owner,id,ifMatch,request);result.retired().forEach(this::clean);telemetry.command(PublicationTelemetry.Command.UNPUBLISH);return result.result();}
        catch(RuntimeException failure){telemetry.rejected(PublicationTelemetry.Command.UNPUBLISH);throw failure;}
    }
    private void clean(String ref) {
        try {if(!repository.referenced(ref))try(var sample=storageTelemetry.start(PublicStorageTelemetry.ObjectClass.PUBLIC_MEDIA,PublicStorageTelemetry.Operation.DELETE)){
            objects.delete(ref);sample.success();}}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(PublicationService.class).warn("public_media_cleanup_deferred");}
    }
    String reconcile(String after) {
        staging.writeLock().lock();
        try {
            var cutoff=clock.instant().minus(Duration.ofHours(24));var rows=objects.inventoryBefore(cutoff,after,100);
            if(rows.size()>100)throw unavailable();String next=after;
            for(var row:rows) {
                if(row.reference()==null||!row.reference().matches("public-publication-media/[0-9a-f]{64}")||row.createdAt()==null
                    ||!row.createdAt().isBefore(cutoff)||next!=null&&row.reference().compareTo(next)<=0)throw unavailable();
                clean(row.reference());next=row.reference();
            }
            return rows.size()==100?next:null;
        }finally{staging.writeLock().unlock();}
    }
    private static final class ExactInput extends FilterInputStream {
        private long remaining;
        ExactInput(InputStream in,long size){super(in);remaining=size;}
        @Override public int read()throws IOException {
            if(remaining==0)return -1;int b=in.read();if(b<0)throw new IOException("Public copy input incomplete");remaining--;return b;
        }
        @Override public int read(byte[] b,int off,int len)throws IOException {
            if(len==0)return 0;if(remaining==0)return -1;
            int n=in.read(b,off,(int)Math.min(len,remaining));if(n<0)throw new IOException("Public copy input incomplete");remaining-=n;return n;
        }
        @Override public long skip(long n)throws IOException {long skipped=0;byte[] b=new byte[8192];while(skipped<n&&remaining>0){int count=read(b,0,(int)Math.min(b.length,n-skipped));if(count<=0)break;skipped+=count;}return skipped;}
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
