package org.notesknowledge.profile;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** Profile-owned storage port; transport is not authorization. */
final class OciPrivateAvatarObjectStore implements AvatarObjectStore,AutoCloseable {
    private final OciAvatarStorage storage;
    OciPrivateAvatarObjectStore(OciAvatarStorage storage){this.storage=storage;}
    @Override public void write(String reference,byte[] bytes){storage.write(reference,bytes);}
    @Override public InputStream open(String reference,long size){return storage.open(reference,size);}
    @Override public void delete(String reference){storage.delete(reference);}
    @Override public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){return scanBefore(cutoff,after,limit).objects();}
    @Override public InventoryPage scanBefore(Instant cutoff,String after,int limit){
        var page=storage.scanBefore(cutoff,after,limit);
        return new InventoryPage(page.objects().stream().map(o->new StoredObject(o.reference(),o.createdAt())).toList(),page.continueAfter());
    }
    @Override public void close(){storage.close();}
    @Override public String toString(){return "OciPrivateAvatarObjectStore[REDACTED]";}
}
