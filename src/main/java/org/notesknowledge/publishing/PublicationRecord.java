package org.notesknowledge.publishing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record PublicationRecord(UUID id,UUID owner,UUID note,UUID checkpoint,long sourceRevision,UUID author,
    String title,String markdown,long snapshot,long generation,String state,Instant publishedAt,Instant updatedAt,List<String> tags) {
    @Override public String toString(){return "Publication[REDACTED]";}
    record Media(UUID id,UUID publication,long snapshot,long generation,UUID source,String reference,String kind,String type,
        String name,long size,Integer width,Integer height,Double duration,Integer pages,int order) {
        @Override public String toString(){return "PublicMedia[REDACTED]";}
        MediaView view(){return new MediaView(id,kind,type,name,size,width,height,duration,pages,
            "/api/public/publications/"+publication+"/media/"+id+"/content");}
    }
    record MediaView(UUID id,String mediaKind,String mediaType,String displayName,long sizeBytes,Integer width,Integer height,
        Double durationSeconds,Integer pageCount,String contentUrl) { }
    record OwnerView(UUID id,String title,String markdown,List<String> tags,String availability,String publicUrl,List<MediaView> media,
        Instant publishedAt,Instant updatedAt) {
        @Override public String toString(){return "OwnerPublicationView[REDACTED]";}
    }
    record OwnerSummary(UUID id,String title,List<String> tags,String availability,Instant publishedAt,Instant updatedAt) {
        @Override public String toString(){return "OwnerPublicationSummary[REDACTED]";}
    }
}
