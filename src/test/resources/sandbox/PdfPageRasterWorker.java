package org.notesknowledge.notes;

// TRUSTED SYNTHETIC SECURITY PROBE ONLY. Compiled into a private test directory,
// never the application artifact. Deliberately has the fixed worker entry name
// so qualification exercises the identical launch policy without a production
// arbitrary-command seam.
public final class PdfPageRasterWorker {
    public static void main(String[] args)throws Exception {
        var protocol=System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        var input=new java.io.DataInputStream(System.in);
        var out=new java.io.DataOutputStream(protocol);
        try{run(input,out);}catch(Throwable failure){out.writeByte(127);out.writeUTF(failure.getClass().getName());if(failure.getCause()!=null)out.writeUTF(failure.getCause().getClass().getName());else out.writeUTF("none");out.writeUTF(failure.getMessage()!=null&&failure.getMessage().startsWith("Network boundary result=")?failure.getMessage():"Synthetic probe failed");out.flush();System.exit(3);}
    }
    private static void run(java.io.DataInputStream input,java.io.DataOutputStream out)throws Exception {
        String mode=input.readUTF();
        switch(mode){
            case "windows-environment" -> out.writeBoolean(System.getenv().keySet().equals(java.util.Set.of("APPDATA","LOCALAPPDATA","SystemRoot","TEMP","TMP","USERPROFILE")));
            case "linux-environment" -> out.writeBoolean(System.getenv().keySet().equals(java.util.Set.of("LANG")));
            case "files" -> {
                var denied=java.nio.file.Path.of(input.readUTF());
                var artifact=java.nio.file.Path.of(input.readUTF());
                var profile=java.nio.file.Path.of(input.readUTF());
                int directoryCount=input.readInt();
                if(directoryCount<1||directoryCount>256)throw new IllegalArgumentException("Synthetic directory bound");
                var directories=new java.util.ArrayList<java.nio.file.Path>();
                for(int n=0;n<directoryCount;n++)directories.add(java.nio.file.Path.of(input.readUTF()));
                boolean privateDenied=false,artifactDenied=false,profileDenied=false;
                try{java.nio.file.Files.readAllBytes(denied);}catch(java.io.IOException expected){privateDenied=true;}
                try{java.nio.file.Files.write(artifact,new byte[]{1});}catch(java.io.IOException expected){artifactDenied=true;}
                try{java.nio.file.Files.write(profile,new byte[]{1});}catch(java.io.IOException expected){profileDenied=true;}
                out.writeBoolean(privateDenied);out.writeBoolean(artifactDenied);out.writeBoolean(profileDenied);
                out.writeBoolean(java.nio.file.Files.isReadable(java.nio.file.Path.of(System.getProperty("java.home"),"lib/modules")));
                for(var directory:directories){
                    boolean writeDenied=false;
                    try{java.nio.file.Files.write(directory.resolve("forbidden-test.bin"),new byte[]{1});}catch(java.io.IOException expected){writeDenied=true;}
                    out.writeBoolean(writeDenied);
                }
            }
            case "network" -> {
                nativeNetwork(input.readInt(),out);
            }
            case "linux-network" -> {
                var linker=java.lang.foreign.Linker.nativeLinker();
                var layout=java.lang.foreign.Linker.Option.captureStateLayout();
                var error=layout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
                var socket=linker.downcallHandle(linker.defaultLookup().find("socket").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,java.lang.foreign.ValueLayout.JAVA_INT,java.lang.foreign.ValueLayout.JAVA_INT,java.lang.foreign.ValueLayout.JAVA_INT),java.lang.foreign.Linker.Option.captureCallState("errno"));
                try(var arena=java.lang.foreign.Arena.ofConfined()){
                    var state=arena.allocate(layout);
                    for(int family:new int[]{1,2,10})for(int type:new int[]{1,2}){
                        try{int result=(int)socket.invokeExact(state,family,type,0);out.writeBoolean(result==-1&&(int)error.get(state,0L)==1);}
                        catch(Throwable failure){throw new IllegalStateException("Synthetic socket probe unavailable",failure);}
                    }
                }
            }
            case "linux-files" -> {
                for(String file:new String[]{input.readUTF(),input.readUTF()}){
                    boolean denied=false;try{java.nio.file.Files.readAllBytes(java.nio.file.Path.of(file));}catch(java.io.IOException expected){denied=true;}out.writeBoolean(denied);
                }
                for(String file:new String[]{input.readUTF(),input.readUTF()}){
                    boolean denied=false;try{java.nio.file.Files.write(java.nio.file.Path.of(file),new byte[]{1});}catch(java.io.IOException expected){denied=true;}out.writeBoolean(denied);
                }
                out.writeBoolean(java.nio.file.Files.isReadable(java.nio.file.Path.of(System.getProperty("java.home"),"lib/modules")));
            }
            case "linux-fds" -> {
                var linker=java.lang.foreign.Linker.nativeLinker();var i=java.lang.foreign.ValueLayout.JAVA_INT;
                var layout=java.lang.foreign.Linker.Option.captureStateLayout();var error=layout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
                var fcntl=linker.downcallHandle(linker.defaultLookup().find("fcntl").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,i,i),java.lang.foreign.Linker.Option.captureCallState("errno"),java.lang.foreign.Linker.Option.firstVariadicArg(2));
                try(var arena=java.lang.foreign.Arena.ofConfined()){
                    var state=arena.allocate(layout);
                    try{int result=(int)fcntl.invokeExact(state,50,1);out.writeBoolean(result==-1&&(int)error.get(state,0L)==9);}
                    catch(Throwable failure){throw new IllegalStateException("Synthetic descriptor probe unavailable",failure);}
                }
            }
            case "linux-process" -> {
                var linker=java.lang.foreign.Linker.nativeLinker();
                var layout=java.lang.foreign.Linker.Option.captureStateLayout();var error=layout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
                var fork=linker.downcallHandle(linker.defaultLookup().find("fork").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT),java.lang.foreign.Linker.Option.captureCallState("errno"));
                try(var arena=java.lang.foreign.Arena.ofConfined()){
                    var state=arena.allocate(layout);
                    try{int result=(int)fork.invokeExact(state);if(result==0)System.exit(99);out.writeBoolean(result==-1&&(int)error.get(state,0L)==1);}
                    catch(Throwable failure){throw new IllegalStateException("Synthetic process probe unavailable",failure);}
                }
            }
            case "linux-prctl" -> {
                var linker=java.lang.foreign.Linker.nativeLinker();
                var layout=java.lang.foreign.Linker.Option.captureStateLayout();
                var error=layout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
                var i=java.lang.foreign.ValueLayout.JAVA_INT;var l=java.lang.foreign.ValueLayout.JAVA_LONG;
                var prctl=linker.downcallHandle(linker.defaultLookup().find("prctl").orElseThrow(),
                        java.lang.foreign.FunctionDescriptor.of(i,i,l,l,l,l),java.lang.foreign.Linker.Option.captureCallState("errno"));
                try(var arena=java.lang.foreign.Arena.ofConfined()) {
                    var state=arena.allocate(layout);
                    for(long[] call:new long[][]{{1,0},{4,1},{22,0},{50,0},{50,512},{50,0x20010},{50,0x100000010L}}) {
                        try {
                            int result=(int)prctl.invokeExact(state,(int)call[0],call[1],0L,0L,0L);
                            out.writeBoolean(result==-1&&(int)error.get(state,0L)==1);
                        }catch(Throwable failure){throw new IllegalStateException("Synthetic CPU-state boundary probe unavailable",failure);}
                    }
                }
            }
            case "windows-handle" -> {
                long handle=input.readLong();
                var linker=java.lang.foreign.Linker.nativeLinker();var p=java.lang.foreign.ValueLayout.ADDRESS;var i=java.lang.foreign.ValueLayout.JAVA_INT;
                try(var arena=java.lang.foreign.Arena.ofConfined()){
                    var library=java.lang.foreign.SymbolLookup.libraryLookup("kernel32.dll",arena);
                    var layout=java.lang.foreign.Linker.Option.captureStateLayout();var error=layout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
                    var inspect=linker.downcallHandle(library.find("GetHandleInformation").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,p,p),java.lang.foreign.Linker.Option.captureCallState("GetLastError"));
                    var state=arena.allocate(layout);
                    try{int result=(int)inspect.invokeExact(state,java.lang.foreign.MemorySegment.ofAddress(handle),arena.allocate(4,4));out.writeBoolean(result==0&&(int)error.get(state,0L)==6);}
                    catch(Throwable failure){throw new IllegalStateException("Synthetic handle probe unavailable",failure);}
                }
            }
            case "child" -> {
                boolean denied=false;
                try{var child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin/javaw.exe").toString(),"-version").start();child.destroyForcibly();child.waitFor();}
                catch(java.io.IOException expected){denied=true;}
                out.writeBoolean(denied);
            }
            case "memory" -> {
                try(var arena=java.lang.foreign.Arena.ofConfined()){
                    arena.allocate(16L*1024*1024).fill((byte)1); // Native positive control, not Java heap.
                    boolean rejected=false;
                    try{arena.allocate(768L*1024*1024).fill((byte)1);}catch(OutOfMemoryError expected){rejected=true;}
                    out.writeBoolean(rejected);
                }
            }
            case "wait" -> {out.writeBoolean(true);out.flush();Thread.sleep(60000);}
            case "cpu" -> {out.writeBoolean(true);out.flush();while(true){Thread.onSpinWait();}}
            case "output" -> {byte[] bytes=new byte[8192];for(int i=0;i<1024;i++){out.write(bytes);out.flush();}}
            default -> throw new IllegalArgumentException("Synthetic mode");
        }
        out.flush();
    }
    private static void nativeNetwork(int port,java.io.DataOutputStream out)throws Exception {
        var linker=java.lang.foreign.Linker.nativeLinker();
        var p=java.lang.foreign.ValueLayout.ADDRESS;var i=java.lang.foreign.ValueLayout.JAVA_INT;var l=java.lang.foreign.ValueLayout.JAVA_LONG;
        try(var arena=java.lang.foreign.Arena.ofConfined()){
            var library=java.lang.foreign.SymbolLookup.libraryLookup("ws2_32.dll",arena);
            var startup=linker.downcallHandle(library.find("WSAStartup").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,java.lang.foreign.ValueLayout.JAVA_SHORT,p));
            var stateLayout=java.lang.foreign.Linker.Option.captureStateLayout();
            var state=arena.allocate(stateLayout);
            var error=stateLayout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("WSAGetLastError"));
            var capture=java.lang.foreign.Linker.Option.captureCallState("WSAGetLastError");
            var socket=linker.downcallHandle(library.find("socket").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(l,i,i,i),capture);
            var close=linker.downcallHandle(library.find("closesocket").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,l));
            var connect=linker.downcallHandle(library.find("connect").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,l,p,i),capture);
            var send=linker.downcallHandle(library.find("sendto").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,l,p,i,i,p,i),capture);
            var cleanup=linker.downcallHandle(library.find("WSACleanup").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i));
            try {
                int started=(int)startup.invokeExact((short)0x202,arena.allocate(512,8));if(started!=0)throw new IllegalStateException("Winsock bootstrap unavailable");
                try{
                    var address=arena.allocate(16,4);address.set(java.lang.foreign.ValueLayout.JAVA_SHORT,0,(short)2);address.set(java.lang.foreign.ValueLayout.JAVA_BYTE,2,(byte)(port>>>8));address.set(java.lang.foreign.ValueLayout.JAVA_BYTE,3,(byte)port);address.set(java.lang.foreign.ValueLayout.JAVA_BYTE,4,(byte)127);address.set(java.lang.foreign.ValueLayout.JAVA_BYTE,7,(byte)1);
                    for(int type:new int[]{1,2}){
                        long handle=(long)socket.invokeExact(state,2,type,type==1?6:17);boolean denied;int result=-1,reason;
                        if(handle==-1){reason=(int)error.get(state,0L);denied=reason==10013;}
                        else {try{result=type==1?(int)connect.invokeExact(state,handle,address,16):(int)send.invokeExact(state,handle,arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE,(byte)1),1,0,address,16);reason=(int)error.get(state,0L);denied=result==-1&&reason==10013;}finally{int ignored=(int)close.invokeExact(handle);}}
                        out.writeBoolean(denied);
                        out.writeInt(result);out.writeInt(reason);
                    }
                }finally{int ignored=(int)cleanup.invokeExact();}
            }catch(Throwable failure){throw new IllegalStateException("Synthetic native network unavailable",failure);}
        }
    }
}
