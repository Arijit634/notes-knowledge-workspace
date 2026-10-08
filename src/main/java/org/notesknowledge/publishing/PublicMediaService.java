package org.notesknowledge.publishing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.PublicStorageTelemetry;
import org.notesknowledge.profile.PublicProfileApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.ResponseStreamInterruptedException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicMediaService {
    private final PublicationRepository publications;
    private final PublicProfileApi profiles;
    private final PublicMediaObjectStore objects;
    private final PublicExposureCoordinator exposure;
    private final PublicStorageTelemetry telemetry;
    PublicMediaService(PublicationRepository publications,PublicProfileApi profiles,PublicMediaObjectStore objects,PublicExposureCoordinator exposure,PublicStorageTelemetry telemetry) {
        this.publications=publications;this.profiles=profiles;this.objects=objects;this.exposure=exposure;this.telemetry=telemetry;
    }
    @Transactional(propagation=Propagation.NEVER)
    void stream(UUID id,UUID mediaId,HttpServletRequest request,HttpServletResponse response) {
        var initial=publications.active(id).orElseThrow(PublicationTransactions::missing);
        var media=requireCurrent(initial,mediaId);
        List<String> headers=Collections.list(request.getHeaders("Range"));
        if(headers.size()>1)throw malformed();
        long start=0,end=media.size()-1;boolean partial=!headers.isEmpty();
        if(partial) {
            var value=headers.getFirst();
            if(value.length()>256||!value.matches("bytes=(?:[0-9]{1,19}-[0-9]{0,19}|-[0-9]{1,19})"))throw malformed();
            try{for(String scalar:value.substring(6).split("-"))if(!scalar.isEmpty())Long.parseLong(scalar);}
            catch(NumberFormatException malformedNumber){throw malformed();}
            try {
                var range=HttpRange.parseRanges(value).getFirst();start=range.getRangeStart(media.size());end=range.getRangeEnd(media.size());
                if(start<0||start>=media.size()||end<start)throw ApiFailureException.rangeNotSatisfiable(media.size());
            }catch(IllegalArgumentException failure){throw ApiFailureException.rangeNotSatisfiable(media.size());}
        }
        long length=end-start+1;
        try(var sample=telemetry.start(PublicStorageTelemetry.ObjectClass.PUBLIC_MEDIA,PublicStorageTelemetry.Operation.READ);
            var input=objects.openRange(media.reference(),start,length)) {
            if(input==null)throw PublicationTransactions.missing();
            long remaining=length;byte[] buffer=new byte[16*1024];boolean begun=false;
            while(remaining>0) {
                // Bounded storage I/O is outside the authorization lease and outside every DB transaction.
                int count=input.read(buffer,0,(int)Math.min(remaining,buffer.length));if(count<=0)throw unavailable();
                try(var lease=exposure.readLease(initial.owner())) {
                    var current=requireCurrent(initial,mediaId);
                    if(!current.equals(media))throw PublicationTransactions.missing();
                    if(!begun) {
                        response.setStatus(partial?206:200);response.setContentType(media.type());response.setContentLengthLong(length);
                        response.setHeader("Content-Disposition",ContentDisposition.inline().filename(media.name(),StandardCharsets.UTF_8).build().toString());
                        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
                        response.setHeader("Accept-Ranges","bytes");
                        if(partial)response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+media.size());
                        begun=true;
                    }
                    response.getOutputStream().write(buffer,0,count);response.flushBuffer();remaining-=count;
                }
            }
            sample.success();
        }catch(IOException|RuntimeException failure) {
            if(response.isCommitted())throw new ResponseStreamInterruptedException();
            reset(response);
            if(failure instanceof ApiFailureException expected)throw expected;
            throw unavailable();
        }
    }
    private PublicationRecord.Media requireCurrent(PublicationRecord expected,UUID media) {
        var current=publications.active(expected.id()).orElseThrow(PublicationTransactions::missing);
        if(current.generation()!=expected.generation()||current.snapshot()!=expected.snapshot())throw PublicationTransactions.missing();
        profiles.resolveById(current.author());
        return publications.media(current.id(),media).orElseThrow(PublicationTransactions::missing);
    }
    private static void reset(HttpServletResponse response) {
        var removed=Set.of("content-length","content-range","content-type","content-disposition","accept-ranges");
        var retained=new LinkedHashMap<String,List<String>>();
        for(String name:response.getHeaderNames())if(!removed.contains(name.toLowerCase(Locale.ROOT)))retained.put(name,List.copyOf(response.getHeaders(name)));
        response.reset();retained.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));
    }
    private static ApiFailureException malformed(){return ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);}
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
