package org.notesknowledge.notes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/** Windows x64 execution primitive, not a qualified runtime or a Spring bean.
 * No media is parsed here. The fixed worker exchanges bytes through exactly three
 * inherited handles, never a media path. Integration must additionally qualify
 * artifact identity, profile storage, resource limits and crash scavenging.
 */
final class WindowsPdfProcess extends Process implements AutoCloseable {
    private static final Semaphore RUNTIME_ACL_LOCK=new Semaphore(1);
    private static volatile boolean cleanupPoisoned;
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("nkw-pdf-deadline").factory());
    private final Arena arena=Arena.ofShared();
    private final Native api;
    private final String profile=profileName();
    private final List<AclGrant> grants=new java.util.ArrayList<>();
    private MemorySegment sid=MemorySegment.NULL,job=MemorySegment.NULL,process=MemorySegment.NULL;
    private boolean profileCreated,closed,locked;
    private long pid;
    private ProcessHandle processHandle;
    private Path profileDirectory;
    private Pipe input,output;
    private Integer status;
    private java.util.concurrent.ScheduledFuture<?> deadline;
    private volatile IOException deadlineCleanupFailure;
    private volatile boolean deadlineExpired;

    private static String profileName() {
        // Temporary OS isolation namespace, not a persisted domain UUID or authority.
        byte[] nonce=new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        return "NkwPdfWorker-"+java.util.HexFormat.of().formatHex(nonce);
    }

    static WindowsPdfProcess start(Path runtime,Path artifacts,String expectedIdentity)throws IOException {
        if(cleanupPoisoned||!System.getProperty("os.name").startsWith("Windows")||!System.getProperty("os.arch").equals("amd64"))throw unavailable();
        PdfRuntimeIdentity.require(expectedIdentity,runtime,artifacts,null);
        requireDirectory(runtime);requireDirectory(artifacts);
        if(!Files.isRegularFile(runtime.resolve("bin/javaw.exe"),LinkOption.NOFOLLOW_LINKS))throw unavailable();
        WindowsPdfProcess child=new WindowsPdfProcess();
        try {
            child.launch(runtime.toRealPath(),artifacts.toRealPath());
            child.deadline=DEADLINES.schedule(()->{
                try{
                    if(child.isAlive())child.deadlineExpired=true;
                    // Reap profiles/ACL grants even if an exited worker's caller
                    // forgot to close its custody object.
                    child.close();
                }catch(IOException|RuntimeException failure){child.deadlineCleanupFailure=unavailable();cleanupPoisoned=true;}
                catch(Error failure){cleanupPoisoned=true;throw failure;}
            },30,TimeUnit.SECONDS);
            return child;
        }
        catch(InterruptedException failure){Thread.currentThread().interrupt();try{child.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw unavailable();}
        catch(IOException|RuntimeException failure){try{child.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw unavailable();}
        catch(Error failure){try{child.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    private WindowsPdfProcess()throws IOException {
        try{api=new Native(arena);}catch(RuntimeException failure){arena.close();throw unavailable();}
        catch(Error failure){arena.close();throw failure;}
    }
    private static void requireDirectory(Path path)throws IOException {
        Path absolute=path.toAbsolutePath().normalize();
        for(Path current=absolute;current!=null;current=current.getParent()) {
            var facts=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(facts.isSymbolicLink()||facts.isOther())throw unavailable();
        }
        if(!Files.isDirectory(absolute,LinkOption.NOFOLLOW_LINKS))throw unavailable();
    }
    private MemorySegment wide(String value) {
        if(value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid fixed argument");
        return wideBlock(value+'\0');
    }
    private MemorySegment wideBlock(String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment result=arena.allocate(bytes.length,2);result.copyFrom(MemorySegment.ofArray(bytes));return result;
    }
    private static String quote(Path path)throws IOException {
        String value=path.toString();if(value.contains("\"")||value.contains("\0"))throw unavailable();return '"'+value+'"';
    }
    private MemorySegment block(long size){return arena.allocate(size,8);}
    private MemorySegment pointer(MemorySegment value){MemorySegment result=block(8);result.set(ValueLayout.ADDRESS,0,value);return result;}
    private MemorySegment pointerValue(MemorySegment value){return value.get(ValueLayout.ADDRESS,0);}
    private void check(int result)throws IOException {if(result==0)throw unavailable();}

    private void launch(Path runtime,Path artifacts)throws IOException,InterruptedException {
        if(!RUNTIME_ACL_LOCK.tryAcquire(15,TimeUnit.SECONDS))throw unavailable();locked=true;
        if(cleanupPoisoned)throw unavailable();
        var profileOut=block(8);
        if(api.i("CreateAppContainerProfile",wide(profile),wide(profile),wide("Bounded PDF worker"),MemorySegment.NULL,0,profileOut)!=0)throw unavailable();
        sid=pointerValue(profileOut);profileCreated=true;
        var sidTextOut=block(8);check(api.i("ConvertSidToStringSidW",sid,sidTextOut));
        MemorySegment sidText=pointerValue(sidTextOut);
        try {
            var folderOut=block(8);if(api.i("GetAppContainerFolderPath",sidText,folderOut)!=0)throw unavailable();
            MemorySegment folder=pointerValue(folderOut);
            try{profileDirectory=Path.of(folder.reinterpret(65536).getString(0,StandardCharsets.UTF_16LE));}
            finally{api.v("CoTaskMemFree",folder);}
        }finally{api.p("LocalFree",sidText);}
        requireDirectory(profileDirectory);
        grants.add(grant(profileDirectory,sid,2)); // Replace package write authority with RX.
        grants.add(grant(runtime,sid));grants.add(grant(artifacts,sid));
        // LPAC must be able to bootstrap the OS runtime, not access the network.
        // Microsoft's registryRead capability does not grant arbitrary registry access.
        var registryOut=block(8);
        check(api.i("ConvertStringSidToSidW",wide("S-1-15-3-1024-1065365936-1281604716-3511738428-1654721687-432734479-3232135806-4053264122-3456934681"),registryOut));
        MemorySegment registrySid=pointerValue(registryOut);
        try {
            var capability=block(16);capability.set(ValueLayout.ADDRESS,0,registrySid);capability.set(ValueLayout.JAVA_INT,8,4);
            var caps=block(24);caps.set(ValueLayout.ADDRESS,0,sid);caps.set(ValueLayout.ADDRESS,8,capability);caps.set(ValueLayout.JAVA_INT,16,1);
            var length=block(8);api.i("InitializeProcThreadAttributeList",MemorySegment.NULL,5,0,length);
            long size=length.get(ValueLayout.JAVA_LONG,0);if(size<1||size>65536)throw unavailable();
            var attributes=arena.allocate(size,8);check(api.i("InitializeProcThreadAttributeList",attributes,5,0,length));
            try {
                attribute(attributes,0x20009,caps,24);
                var restricted=block(4);restricted.set(ValueLayout.JAVA_INT,0,1);
                attribute(attributes,0x2000f,restricted,4); // LPAC, not ALL APPLICATION PACKAGES.
                attribute(attributes,0x2000e,restricted,4); // No child process creation.
                job=api.p("CreateJobObjectW",MemorySegment.NULL,MemorySegment.NULL);requireHandle(job);
                var limits=block(144);limits.set(ValueLayout.JAVA_LONG,0,100_000_000L);
                limits.set(ValueLayout.JAVA_INT,16,0x2000|0x400|0x100|0x200|8|2);limits.set(ValueLayout.JAVA_INT,40,1);
                limits.set(ValueLayout.JAVA_LONG,112,512L*1024*1024);limits.set(ValueLayout.JAVA_LONG,120,512L*1024*1024);
                check(api.i("SetInformationJobObject",job,9,limits,144));
                attribute(attributes,0x2000d,pointer(job),8);
                var security=block(24);security.set(ValueLayout.JAVA_INT,0,24);security.set(ValueLayout.JAVA_INT,16,1);
                var inRead=block(8);var inWrite=block(8);var outRead=block(8);var outWrite=block(8);
                check(api.i("CreatePipe",inRead,inWrite,security,4096));
                input=new Pipe(pointerValue(inWrite),false);MemorySegment childInput=pointerValue(inRead);
                MemorySegment childOutput=MemorySegment.NULL,childError=MemorySegment.NULL;
                try {
                    check(api.i("SetHandleInformation",input.handle,1,0));
                    check(api.i("CreatePipe",outRead,outWrite,security,4096));
                    output=new Pipe(pointerValue(outRead),true);childOutput=pointerValue(outWrite);
                    check(api.i("SetHandleInformation",output.handle,1,0));
                    childError=api.p("CreateFileW",wide("NUL"),0x40000000,3,security,3,0,MemorySegment.NULL);requireHandle(childError);
                    var handles=block(24);handles.set(ValueLayout.ADDRESS,0,childInput);handles.set(ValueLayout.ADDRESS,8,childOutput);handles.set(ValueLayout.ADDRESS,16,childError);
                    attribute(attributes,0x20002,handles,24);
                    var startup=block(112);startup.set(ValueLayout.JAVA_INT,0,112);startup.set(ValueLayout.JAVA_INT,60,0x100);
                    startup.set(ValueLayout.ADDRESS,80,childInput);startup.set(ValueLayout.ADDRESS,88,childOutput);startup.set(ValueLayout.ADDRESS,96,childError);startup.set(ValueLayout.ADDRESS,104,attributes);
                    Path executable=runtime.resolve("bin/javaw.exe");
                    quote(artifacts); // Reject quote injection before building the fixed wildcard.
                    String classpath='"'+artifacts.resolve("classes").toString()+";"+artifacts.resolve("libraries")+"\\*\"";
                    String command=quote(executable)+" -Xmx192m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=32m -XX:CompressedClassSpaceSize=32m -XX:MaxMetaspaceSize=96m -XX:ActiveProcessorCount=2 -Djava.awt.headless=true -XX:-UsePerfData -XX:+DisableAttachMechanism -XX:ErrorFile=NUL -Djava.io.tmpdir="+quote(artifacts)+" -Duser.home="+quote(artifacts)+" -cp "+classpath+" org.notesknowledge.notes.PdfPageRasterWorker";
                    // No inherited provider configuration or credentials; only OS path bootstrap.
                    String environment="APPDATA="+artifacts+'\0'+"LOCALAPPDATA="+artifacts+'\0'+"SystemRoot="+System.getenv("SystemRoot")+'\0'+"TEMP="+artifacts+'\0'+"TMP="+artifacts+'\0'+"USERPROFILE="+artifacts+'\0'+'\0';
                    var processInfo=block(24);
                    check(api.i("CreateProcessW",wide(executable.toString()),wide(command),MemorySegment.NULL,MemorySegment.NULL,1,0x80000|0x400|4|0x8000000,wideBlock(environment),wide(artifacts.toString()),startup,processInfo));
                    process=processInfo.get(ValueLayout.ADDRESS,0);MemorySegment thread=processInfo.get(ValueLayout.ADDRESS,8);
                    pid=Integer.toUnsignedLong(processInfo.get(ValueLayout.JAVA_INT,16));
                    try{processHandle=ProcessHandle.of(pid).orElseThrow(WindowsPdfProcess::unavailable);if(api.i("ResumeThread",thread)==-1)throw unavailable();}
                    finally{api.i("CloseHandle",thread);}
                }finally{api.i("CloseHandle",childInput);if(childOutput.address()!=0)api.i("CloseHandle",childOutput);if(childError.address()!=0&&childError.address()!=-1)api.i("CloseHandle",childError);}
            }finally{api.v("DeleteProcThreadAttributeList",attributes);}
        }finally{api.p("LocalFree",registrySid);}
    }
    private void attribute(MemorySegment list,long key,MemorySegment value,long size)throws IOException {
        check(api.i("UpdateProcThreadAttribute",list,0,key,value,size,MemorySegment.NULL,MemorySegment.NULL));
    }
    private static void requireHandle(MemorySegment handle)throws IOException {if(handle.address()==0||handle.address()==-1)throw unavailable();}

    private AclGrant grant(Path path,MemorySegment identity)throws IOException {
        return grant(path,identity,1);
    }
    private AclGrant grant(Path path,MemorySegment identity,int mode)throws IOException {
        var acl=block(8);var descriptor=block(8);
        if(api.i("GetNamedSecurityInfoW",wide(path.toString()),1,4,MemorySegment.NULL,MemorySegment.NULL,acl,MemorySegment.NULL,descriptor)!=0)throw unavailable();
        MemorySegment security=pointerValue(descriptor);boolean retained=false;
        try {
            var control=block(4);var revision=block(4);
            check(api.i("GetSecurityDescriptorControl",security,control,revision));
            int restoreFlags=4|((Short.toUnsignedInt(control.get(ValueLayout.JAVA_SHORT,0))&0x1000)!=0?0x80000000:0x20000000);
            var entry=block(48);entry.set(ValueLayout.JAVA_INT,0,0x1200a9);entry.set(ValueLayout.JAVA_INT,4,mode);entry.set(ValueLayout.JAVA_INT,8,3);
            entry.set(ValueLayout.JAVA_INT,28,0);entry.set(ValueLayout.JAVA_INT,32,5);entry.set(ValueLayout.ADDRESS,40,identity);
            var changed=block(8);if(api.i("SetEntriesInAclW",1,entry,pointerValue(acl),changed)!=0)throw unavailable();
            MemorySegment replacement=pointerValue(changed);
            try{if(api.i("SetNamedSecurityInfoW",wide(path.toString()),1,4,MemorySegment.NULL,MemorySegment.NULL,replacement,MemorySegment.NULL)!=0)throw unavailable();}
            finally{api.p("LocalFree",replacement);}
            retained=true;return new AclGrant(path,security,pointerValue(acl),restoreFlags);
        }finally{if(!retained)api.p("LocalFree",security);}
    }
    private record AclGrant(Path path,MemorySegment descriptor,MemorySegment acl,int restoreFlags){ }

    @Override public OutputStream getOutputStream(){return new OutputStream(){
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{java.util.Objects.checkFromIndexSize(offset,length,bytes.length);input.write(bytes,offset,length);}
        @Override public void close()throws IOException{input.close();}
    };}
    @Override public InputStream getInputStream(){return new InputStream(){
        @Override public int read()throws IOException{byte[] b=new byte[1];return read(b)==-1?-1:Byte.toUnsignedInt(b[0]);}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{java.util.Objects.checkFromIndexSize(offset,length,bytes.length);return output.read(bytes,offset,length);}
        @Override public void close()throws IOException{output.close();}
    };}
    @Override public InputStream getErrorStream(){return InputStream.nullInputStream();}
    @Override public long pid(){return pid;}
    @Override public ProcessHandle toHandle(){if(processHandle==null)throw new IllegalStateException("Worker not started");return processHandle;}
    @Override public int waitFor()throws InterruptedException {while(!waitFor(1,TimeUnit.SECONDS)){ }return exitValue();}
    @Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException {
        long start=System.nanoTime(),duration=unit.toNanos(timeout);
        while(isAlive()){if(System.nanoTime()-start>=duration)return false;Thread.sleep(10);}return true;
    }
    @Override public synchronized boolean isAlive(){
        if(status!=null)return false;if(closed)return false;
        if(api.i("WaitForSingleObject",process,0)==0){
            var code=block(4);if(api.i("GetExitCodeProcess",process,code)==0)throw new IllegalStateException("Worker state unavailable");status=code.get(ValueLayout.JAVA_INT,0);return false;
        }return true;
    }
    @Override public synchronized int exitValue(){if(isAlive()||status==null)throw new IllegalThreadStateException();return status;}
    @Override public void destroy(){destroyForcibly();}
    @Override public Process destroyForcibly(){try{close();}catch(IOException failure){throw new IllegalStateException("Worker cleanup unavailable");}return this;}
    @Override public synchronized void close()throws IOException {
        if(closed){if(deadlineCleanupFailure!=null)throw unavailable();return;}
        if(deadline!=null)deadline.cancel(false);
        boolean clean=true,completed=false;
        try{
            if(job.address()!=0){clean&=api.i("CloseHandle",job)!=0;job=MemorySegment.NULL;}
            if(process.address()!=0){
                if(api.i("WaitForSingleObject",process,5000)!=0){
                    clean&=api.i("TerminateProcess",process,2)!=0;
                    clean&=api.i("WaitForSingleObject",process,5000)==0;
                }
                var code=block(4);if(api.i("GetExitCodeProcess",process,code)!=0)status=code.get(ValueLayout.JAVA_INT,0);
                clean&=api.i("CloseHandle",process)!=0;process=MemorySegment.NULL;
            }
            if(input!=null)input.close();if(output!=null)output.close();
            for(int i=grants.size()-1;i>=0;i--){var grant=grants.get(i);clean&=api.i("SetNamedSecurityInfoW",wide(grant.path().toString()),1,grant.restoreFlags(),MemorySegment.NULL,MemorySegment.NULL,grant.acl(),MemorySegment.NULL)==0;api.p("LocalFree",grant.descriptor());}
            if(sid.address()!=0){api.p("FreeSid",sid);sid=MemorySegment.NULL;}
            if(profileCreated){clean&=api.i("DeleteAppContainerProfile",wide(profile))==0;profileCreated=false;}
            completed=true;
        }finally{
            if(!completed||!clean)cleanupPoisoned=true;
            closed=true;
            try{arena.close();}finally{if(locked){locked=false;RUNTIME_ACL_LOCK.release();}}
        }
        if(!clean)throw unavailable();
    }

    private final class Pipe implements AutoCloseable {
        final MemorySegment handle;final boolean readable;boolean shut;long transferred;
        Pipe(MemorySegment handle,boolean readable){this.handle=handle;this.readable=readable;}
        synchronized int read(byte[] bytes,int offset,int length)throws IOException {
            if(length==0)return 0;if(shut||!readable)throw unavailable();
            try(var memory=Arena.ofConfined()) {
                int bound=(int)Math.min(Math.min(length,8192),PdfPageRasterWorker.MAX_PNG_BYTES+13L-transferred);if(bound<1)throw unavailable();
                var buffer=memory.allocate(bound);var count=memory.allocate(4,4);
                var result=api.readFile(handle,buffer,bound,count);
                if(result.success()==0){if(result.error()==109)return -1;throw unavailable();}
                int received=count.get(ValueLayout.JAVA_INT,0);if(received<0||received>bound)throw unavailable();
                transferred+=received;if(transferred>PdfPageRasterWorker.MAX_PNG_BYTES+12L)throw unavailable();
                MemorySegment.copy(buffer,ValueLayout.JAVA_BYTE,0,bytes,offset,received);return received==0?-1:received;
            }
        }
        synchronized void write(byte[] bytes,int offset,int length)throws IOException {
            if(shut||readable||length>10L*1024*1024+12-transferred)throw unavailable();
            try(var memory=Arena.ofConfined()) {
                var buffer=memory.allocate(Math.min(length,8192));var count=memory.allocate(4,4);
                while(length>0){int chunk=Math.min(length,8192);MemorySegment.copy(bytes,offset,buffer,ValueLayout.JAVA_BYTE,0,chunk);
                    check(api.i("WriteFile",handle,buffer,chunk,count,MemorySegment.NULL));int written=count.get(ValueLayout.JAVA_INT,0);if(written<1||written>chunk)throw unavailable();
                    offset+=written;length-=written;transferred+=written;
                }
            }
        }
        @Override public synchronized void close()throws IOException{if(!shut){shut=true;check(api.i("CloseHandle",handle));}}
    }

    private static IOException unavailable(){return new IOException("Isolated PDF worker unavailable");}
    private static final class Native {
        private static final ValueLayout.OfInt I=ValueLayout.JAVA_INT;
        private static final ValueLayout.OfLong L=ValueLayout.JAVA_LONG;
        private static final java.lang.foreign.AddressLayout P=ValueLayout.ADDRESS;
        private final Map<String,MethodHandle> calls=new HashMap<>();
        private final java.lang.foreign.StructLayout callState=Linker.Option.captureStateLayout();
        private final java.lang.invoke.VarHandle lastError=callState.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
        Native(Arena arena){
            Path system=Path.of(System.getenv("SystemRoot"),"System32");
            var kernel=SymbolLookup.libraryLookup(system.resolve("kernel32.dll"),arena);
            var advapi=SymbolLookup.libraryLookup(system.resolve("advapi32.dll"),arena);
            var userenv=SymbolLookup.libraryLookup(system.resolve("userenv.dll"),arena);
            var ole32=SymbolLookup.libraryLookup(system.resolve("ole32.dll"),arena);
            bind(userenv,"CreateAppContainerProfile",I,P,P,P,P,I,P);bind(userenv,"DeleteAppContainerProfile",I,P);
            bind(userenv,"GetAppContainerFolderPath",I,P,P);bindVoid(ole32,"CoTaskMemFree",P);
            bind(advapi,"ConvertStringSidToSidW",I,P,P);bind(advapi,"ConvertSidToStringSidW",I,P,P);bind(advapi,"FreeSid",P,P);
            bind(advapi,"GetNamedSecurityInfoW",I,P,I,I,P,P,P,P,P);bind(advapi,"SetNamedSecurityInfoW",I,P,I,I,P,P,P,P);bind(advapi,"SetEntriesInAclW",I,I,P,P,P);
            bind(advapi,"GetSecurityDescriptorControl",I,P,P,P);
            bind(kernel,"InitializeProcThreadAttributeList",I,P,I,I,P);bind(kernel,"UpdateProcThreadAttribute",I,P,I,L,P,L,P,P);bindVoid(kernel,"DeleteProcThreadAttributeList",P);
            bind(kernel,"CreateJobObjectW",P,P,P);bind(kernel,"SetInformationJobObject",I,P,I,P,I);
            bind(kernel,"CreatePipe",I,P,P,P,I);bind(kernel,"SetHandleInformation",I,P,I,I);bind(kernel,"CreateFileW",P,P,I,I,P,I,I,P);
            bind(kernel,"CreateProcessW",I,P,P,P,P,I,I,P,P,P,P);bind(kernel,"ResumeThread",I,P);
            bind(kernel,"WaitForSingleObject",I,P,I);bind(kernel,"GetExitCodeProcess",I,P,P);bind(kernel,"TerminateProcess",I,P,I);bind(kernel,"CloseHandle",I,P);bind(kernel,"LocalFree",P,P);
            calls.put("ReadFileCaptured",Linker.nativeLinker().downcallHandle(kernel.find("ReadFile").orElseThrow(),FunctionDescriptor.of(I,P,P,I,P,P),Linker.Option.captureCallState("GetLastError")));
            bind(kernel,"WriteFile",I,P,P,I,P,P);
        }
        private void bind(SymbolLookup library,String name,java.lang.foreign.MemoryLayout result,java.lang.foreign.MemoryLayout... arguments){calls.put(name,Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(),FunctionDescriptor.of(result,arguments)));}
        private void bindVoid(SymbolLookup library,String name,java.lang.foreign.MemoryLayout... arguments){calls.put(name,Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(),FunctionDescriptor.ofVoid(arguments)));}
        private Object call(String name,Object... arguments){try{return calls.get(name).invokeWithArguments(arguments);}catch(Error failure){throw failure;}catch(Throwable failure){throw new IllegalStateException("Windows boundary operation unavailable");}}
        int i(String name,Object... arguments){return (int)call(name,arguments);}
        MemorySegment p(String name,Object... arguments){return (MemorySegment)call(name,arguments);}
        void v(String name,Object... arguments){call(name,arguments);}
        record ReadResult(int success,int error){ }
        ReadResult readFile(MemorySegment handle,MemorySegment buffer,int size,MemorySegment count){
            try(var memory=Arena.ofConfined()) {
                var state=memory.allocate(callState);
                int success=(int)call("ReadFileCaptured",state,handle,buffer,size,count,MemorySegment.NULL);
                return new ReadResult(success,(int)lastError.get(state,0L));
            }
        }
    }
}
