package org.notesknowledge.notes;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Validated owner-selected byte custody for Publishing; never an anonymous read path. */
@Service
public class AttachmentSourceApi {
    public record Selected(UUID id,long revision,String reference,String kind,String type,String name,long size,
        Integer width,Integer height,Double duration,Integer pages) {
        @Override public String toString(){return "SelectedAttachment[REDACTED]";}
    }
    private final AttachmentRepository repository;
    private final PrivateAttachmentObjectStore objects;
    AttachmentSourceApi(AttachmentRepository repository,PrivateAttachmentObjectStore objects){this.repository=repository;this.objects=objects;}
    /** Caller holds the authorized owning Note lock. Stable ordering avoids attachment-lock inversions. */
    @Transactional(propagation=Propagation.MANDATORY)
    public List<Selected> requireValidatedSelection(UUID owner,UUID note,List<UUID> selection) {
        if(selection==null||selection.size()>20||selection.stream().anyMatch(java.util.Objects::isNull)
            ||selection.stream().distinct().count()!=selection.size())throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        selection.stream().sorted().forEach(id->repository.lockRetained(owner,note,id).orElseThrow(AttachmentSourceApi::missing));
        return selection.stream().map(id->{
            var content=repository.content(owner,note,id).orElseThrow(AttachmentSourceApi::missing);
            var core=repository.findRetained(owner,note,id).orElseThrow(AttachmentSourceApi::missing).view();
            return new Selected(id,content.revision(),content.reference(),core.mediaKind(),core.mediaType(),
                core.displayFilename(),core.sizeBytes(),core.width(),core.height(),core.durationSeconds(),core.pageCount());
        }).toList();
    }
    @Transactional(propagation=Propagation.NEVER)
    public InputStream openPreparedSource(Selected selected) {
        if(selected==null||selected.size()<1||selected.size()>AttachmentRepository.NOTE_BYTES
            ||!selected.reference().matches("private-attachment/[0-9a-f]{64}"))throw missing();
        return objects.openRange(selected.reference(),0,selected.size());
    }
    private static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
