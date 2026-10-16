package org.notesknowledge.publishing;

import java.io.*;
import org.notesknowledge.OciInteroperabilityQualification;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;

/** Test-only bridge. No production visibility or storage API is widened. */
public final class OciQualificationAdapter {
    public static OciInteroperabilityQualification.Store open(OciInteroperabilityQualification.Configuration c,AwsCredentialsProvider credentials,SdkHttpClient transport){
        var p=new PublicMediaStorageProperties(true,"oci-s3-private-v1",c.endpoint(),c.region(),c.bucket(),"injected",true,false);
        var store=new OciPublicMediaObjectStore(OciPublicMediaObjectStore.client(p,credentials,transport),p.bucket());
        return new OciInteroperabilityQualification.Store(){
            public void write(String key,byte[] bytes){store.write(key,new ByteArrayInputStream(bytes),bytes.length);}
            public InputStream read(String key,long offset,long length){return store.openRange(key,offset,length);}
            public void delete(String key){store.delete(key);}
            public void close(){store.close();}
        };
    }
}
