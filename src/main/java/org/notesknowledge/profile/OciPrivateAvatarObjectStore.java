package org.notesknowledge.profile;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** Profile-owned storage port; transport is not authorization. */
final class OciPrivateAvatarObjectStore implements AvatarObjectStore,AutoCloseable {
    private final OciAvatarStorage storage;
    private final org.notesknowledge.PublicStorageTelemetry telemetry;
    private static final org.notesknowledge.PublicStorageTelemetry.ObjectClass KIND=org.notesknowledge.PublicStorageTelemetry.ObjectClass.PRIVATE_AVATAR;
    OciPrivateAvatarObjectStore(OciAvatarStorage storage){this(storage,null);}
    OciPrivateAvatarObjectStore(OciAvatarStorage storage,org.notesknowledge.PublicStorageTelemetry telemetry){this.storage=storage;this.telemetry=telemetry;}
    @Override public void write(String reference,byte[] bytes){observe(org.notesknowledge.PublicStorageTelemetry.Operation.WRITE,()->{storage.write(reference,bytes);return null;});}
    @Override public InputStream open(String reference,long size){return telemetry==null?storage.open(reference,size):telemetry.read(KIND,()->storage.open(reference,size));}
    @Override public void delete(String reference){observe(org.notesknowledge.PublicStorageTelemetry.Operation.DELETE,()->{storage.delete(reference);return null;});}
    @Override public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){return scanBefore(cutoff,after,limit).objects();}
    @Override public InventoryPage scanBefore(Instant cutoff,String after,int limit){
        var page=observe(org.notesknowledge.PublicStorageTelemetry.Operation.INVENTORY,()->storage.scanBefore(cutoff,after,limit));
        return new InventoryPage(page.objects().stream().map(o->new StoredObject(o.reference(),o.createdAt())).toList(),page.continueAfter());
    }
    private <T>T observe(org.notesknowledge.PublicStorageTelemetry.Operation op,java.util.function.Supplier<T> work){return telemetry==null?work.get():telemetry.observe(KIND,op,work);}
    @Override public void close(){storage.close();}
    @Override public String toString(){return "OciPrivateAvatarObjectStore[REDACTED]";}
}
