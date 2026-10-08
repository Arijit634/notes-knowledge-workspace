package org.notesknowledge.websupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Shared mechanics only; callers supply current authorized scope and allowlisted ordering. */
public final class ScopedCursorPage {
    public record Position(Instant before,UUID id) { }
    private final OpaqueCursorCodec codec;
    private final OpaqueCursorCodec.ExpectedCursorContext context;
    public ScopedCursorPage(OpaqueCursorCodec codec,String route,String scope,String filter,String sort) {
        this.codec=codec;
        this.context=new OpaqueCursorCodec.ExpectedCursorContext(new OpaqueCursorCodec.RouteFamily(route),
            new OpaqueCursorCodec.ScopeFingerprint(digest(scope)),new OpaqueCursorCodec.FilterFingerprint(digest(filter)),
            new OpaqueCursorCodec.SortCode(sort));
    }
    public Position position(String cursor) {
        if(cursor==null)return new Position(null,null);
        var tuple=codec.decode(cursor,context).position().scalars();
        if(tuple.size()!=2||!(tuple.get(0) instanceof OpaqueCursorCodec.EpochMillisValue time)
            ||!(tuple.get(1) instanceof OpaqueCursorCodec.UuidValue id))throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        return new Position(Instant.ofEpochMilli(time.value()),id.value());
    }
    public String next(Instant time,UUID id) {
        return codec.encode(context,new OpaqueCursorCodec.OrderingTuple(List.of(
            new OpaqueCursorCodec.EpochMillisValue(time.toEpochMilli()),new OpaqueCursorCodec.UuidValue(id))),Duration.ofHours(24));
    }
    public static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
