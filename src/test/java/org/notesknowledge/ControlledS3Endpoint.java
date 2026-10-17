package org.notesknowledge;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import software.amazon.awssdk.http.*;

/** External synthetic TLS server only; never an application bean/JAR payload.
 * Reuses the accepted SDK transport/signature oracle, not a replacement store bean.
 * No request/header/body/exception logging or credential-file discovery. */
public final class ControlledS3Endpoint {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Explicit synthetic key store and object directory required");
        var keyStore=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(Path.of(args[0]))){keyStore.load(input,"synthetic-keystore-password".toCharArray());}
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(keyStore,"synthetic-keystore-password".toCharArray());
        var tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),null,null);
        var server=HttpsServer.create(new InetSocketAddress(443),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        var transport=new ControlledS3Transport(Path.of(args[1]));transport.customerDomain=true;
        server.createContext("/",exchange->{
            try {
                byte[] body=exchange.getRequestBody().readNBytes(25*1024*1024+1);
                if(body.length>25*1024*1024)throw new IOException();
                var uri=URI.create("https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com"+exchange.getRequestURI());
                var request=SdkHttpFullRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(exchange.getRequestMethod()));
                exchange.getRequestHeaders().forEach(request::putHeader);
                var call=transport.prepareRequest(HttpExecuteRequest.builder().request(request.build())
                    .contentStreamProvider(()->new ByteArrayInputStream(body)).build());
                var response=call.call();byte[] bytes;
                try(var input=response.responseBody().orElseThrow()){bytes=input.readNBytes(25*1024*1024+1);}
                response.httpResponse().headers().forEach((name,values)->exchange.getResponseHeaders().put(name,values));
                int status=response.httpResponse().statusCode();
                exchange.sendResponseHeaders(status,status==204?-1:bytes.length);
                if(status!=204)exchange.getResponseBody().write(bytes);
                java.util.Arrays.fill(body,(byte)0);java.util.Arrays.fill(bytes,(byte)0);
            }catch(Exception|AssertionError failure){
                byte[] safe="<Error><Code>ServiceUnavailable</Code></Error>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                try{exchange.sendResponseHeaders(503,safe.length);exchange.getResponseBody().write(safe);}catch(IOException ignored){ /* Peer already closed. */ }
            }finally{exchange.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));
        server.start();System.out.println("controlled_s3_endpoint_ready");
    }
}
