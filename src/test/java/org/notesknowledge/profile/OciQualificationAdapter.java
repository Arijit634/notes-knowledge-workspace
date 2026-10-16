package org.notesknowledge.profile;

import java.io.*;
import org.notesknowledge.OciInteroperabilityQualification;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;

/** Test-only bridge; public copies and private originals retain distinct adapters. */
public final class OciQualificationAdapter {
    public static OciInteroperabilityQualification.Store open(OciInteroperabilityQualification.Configuration c,AwsCredentialsProvider credentials,SdkHttpClient transport,String prefix){
        var p=new AvatarStorageProperties(true,"oci-s3-private-v1",c.endpoint(),c.region(),c.bucket(),"injected",true,false);
        var store=new OciAvatarStorage(OciAvatarStorage.client(p,credentials,transport),p.bucket(),prefix);
        return new OciInteroperabilityQualification.Store(){
            public void write(String key,byte[] bytes){store.write(key,bytes);}
            public InputStream read(String key,long offset,long length){if(offset!=0)throw new IllegalArgumentException("Avatar Range is not exposed");return store.open(key,length);}
            public void delete(String key){store.delete(key);}
            public void close(){store.close();}
        };
    }
}
