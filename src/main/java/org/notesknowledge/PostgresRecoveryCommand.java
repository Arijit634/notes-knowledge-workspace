package org.notesknowledge;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Offline PostgreSQL18 operator command. Not a Spring bean or application/API capability.
 * libpq owns selected service authentication. No credentials, SQL diagnostics or dump bytes are logged.
 * Restore is into a fresh EMPTY database, with runtime CONNECT quarantined until independent reconciliation.
 */
public final class PostgresRecoveryCommand {
    private static final byte[] MAGIC="NKWPG18GCM2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int CHUNK=65536;
    private PostgresRecoveryCommand(){ }
    public static void main(String[] args){
        try{
            if(args.length!=2||!Set.of("backup","restore").contains(args[0]))throw new IllegalArgumentException();
            var env=System.getenv();String service=required(env,"PGSERVICE");
            if(!service.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException();
            Path scratch=Path.of(required(env,"NKW_BACKUP_SCRATCH")).toAbsolutePath().normalize();
            privateDirectory(scratch);
            Path keyPath=Path.of(required(env,"NKW_BACKUP_KEY_FILE")).toAbsolutePath().normalize();
            if(Files.isSymbolicLink(keyPath)||!Files.isRegularFile(keyPath,LinkOption.NOFOLLOW_LINKS)
                    ||!Files.getPosixFilePermissions(keyPath).equals(PosixFilePermissions.fromString("rw-------")))throw new IllegalArgumentException();
            if(Files.size(keyPath)!=32)throw new IllegalArgumentException();
            long maximum=Long.parseLong(required(env,"NKW_BACKUP_MAX_BYTES"));
            if(maximum<1024||maximum>10L*1024*1024*1024)throw new IllegalArgumentException();
            Path file=Path.of(args[1]).toAbsolutePath().normalize();
            requirePostgres18();
            byte[] key=Files.readAllBytes(keyPath);
            try{
                if(args[0].equals("backup"))backup(service,file,scratch,key,maximum);
                else {
                    if(!"true".equals(env.get("NKW_RESTORE_EMPTY_DATABASE_CONFIRMED"))
                            ||!"true".equals(env.get("NKW_RESTORE_MAINTENANCE_CONFIRMED")))throw new IllegalArgumentException();
                    String runtime=required(env,"NKW_RUNTIME_DATABASE_ROLE");
                    if(!runtime.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException();
                    restore(service,file,scratch,key,maximum,runtime);
                }
            }finally{Arrays.fill(key,(byte)0);}
            System.out.println(args[0].equals("backup")?"encrypted_backup_created":"restore_complete_quarantined_reconciliation_required");
        }catch(Exception failed){System.err.println("postgres_recovery_failed_no_traffic_authorized");System.exit(1);}
    }
    private static void backup(String service,Path output,Path scratch,byte[] key,long maximum)throws Exception{
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)||!output.getParent().equals(scratch))throw new IllegalArgumentException();
        Process dump=bounded(process("pg_dump","--dbname=service="+service,"--format=custom","--no-owner","--no-acl","--no-comments").start());
        boolean success=false;
        try(var input=dump.getInputStream();var target=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
            seal(input,target,key,maximum);
            if(!dump.waitFor(15,TimeUnit.MINUTES)||dump.exitValue()!=0)throw new IOException();
            success=true;
        }finally{terminate(dump);if(!success)Files.deleteIfExists(output);}
    }
    private static void restore(String service,Path input,Path scratch,byte[] key,long maximum,String runtime)throws Exception{
        if(Files.isSymbolicLink(input)||!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)
                ||!Files.getPosixFilePermissions(input).equals(PosixFilePermissions.fromString("rw-------"))
                ||Files.size(input)>maximum+(maximum/CHUNK+2)*20+128)throw new IllegalArgumentException();
        privateDirectory(input.getParent());
        {
            // Authenticate the ENTIRE encrypted backup before starting pg_restore.
            // Bounded authenticated records avoid whole-dump GCM heap buffering and
            // never leave a plaintext dump behind if the operator process crashes.
            try(var source=Files.newInputStream(input)){open(source,OutputStream.nullOutputStream(),key,maximum);}
            String count=scalar(service,"select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where c.relkind in ('r','p','v','m','S') and n.nspname not like 'pg_%' and n.nspname<>'information_schema'");
            if(!count.equals("0"))throw new IllegalStateException();
            String database=scalar(service,"select current_database()");
            if(!database.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException();
            scalar(service,"revoke connect on database \""+database+"\" from public,\""+runtime+"\"");
            if(!scalar(service,"select has_database_privilege('"+runtime+"',current_database(),'CONNECT')").equals("f"))throw new IllegalStateException();
            // No --clean/drop database and no restore into existing application/development data.
            Process restore=bounded(process("pg_restore","--dbname=service="+service,"--single-transaction","--exit-on-error","--no-owner","--no-acl")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
            try{
                try(var source=Files.newInputStream(input);var target=restore.getOutputStream()){open(source,target,key,maximum);}
                if(!restore.waitFor(15,TimeUnit.MINUTES)||restore.exitValue()!=0)throw new IOException();
            }finally{terminate(restore);}
            String tables=scalar(service,"select count(*) from information_schema.tables where table_type='BASE TABLE' and table_schema in ('identity','profile','notes','knowledge','publishing','discovery','moderation')");
            String migrations=scalar(service,"select count(*) from public.flyway_schema_history where success and version is not null");
            if(!tables.equals("38")||!migrations.equals("19"))throw new IllegalStateException();
            // CONNECT remains denied, even on success. Schema/Flyway checks alone cannot
            // prove restored session/capability/job/public authority is current.
        }
    }
    static void seal(InputStream input,OutputStream output,byte[] key,long maximum)throws Exception{
        byte[] nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);
        var target=new DataOutputStream(output);target.write(MAGIC);target.write(nonce);long total=0;
        for(int sequence=0;;sequence++){
            byte[] block=input.readNBytes(CHUNK);total+=block.length;
            try{
                if(total>maximum)throw new IOException();target.writeInt(block.length);
                target.write(cipher(Cipher.ENCRYPT_MODE,key,nonce,sequence,block.length).doFinal(block));
                if(block.length==0){target.flush();return;}
            }finally{Arrays.fill(block,(byte)0);}
        }
    }
    static void open(InputStream input,OutputStream output,byte[] key,long maximum)throws Exception{
        if(!Arrays.equals(input.readNBytes(MAGIC.length),MAGIC))throw new IOException();
        byte[] nonce=input.readNBytes(12);if(nonce.length!=12)throw new IOException();
        var source=new DataInputStream(input);long total=0;
        for(int sequence=0;;sequence++){
            int length=source.readInt();if(length<0||length>CHUNK||total+length>maximum)throw new IOException();
            byte[] encrypted=source.readNBytes(length+16);if(encrypted.length!=length+16)throw new IOException();
            byte[] plaintext=cipher(Cipher.DECRYPT_MODE,key,nonce,sequence,length).doFinal(encrypted);
            try{if(length==0){if(source.read()!=-1)throw new IOException();return;}output.write(plaintext);total+=length;}
            finally{Arrays.fill(plaintext,(byte)0);}
        }
    }
    private static Cipher cipher(int mode,byte[] key,byte[] nonce,int sequence,int length)throws Exception{
        byte[] iv=nonce.clone();for(int i=0;i<4;i++)iv[8+i]^=(byte)(sequence>>>(24-i*8));
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(MAGIC);cipher.updateAAD(nonce);cipher.updateAAD(java.nio.ByteBuffer.allocate(8).putInt(sequence).putInt(length).array());return cipher;
    }
    private static ProcessBuilder process(String... args){
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD);
        process.environment().put("PGCONNECT_TIMEOUT","10");process.environment().put("PGOPTIONS","-c statement_timeout=900000");
        return process;
    }
    private static String scalar(String service,String sql)throws Exception{
        Process process=bounded(process("psql","--no-psqlrc","--tuples-only","--no-align","--quiet","--set=ON_ERROR_STOP=1","--dbname=service="+service,"--command",sql).start(),30);
        try{
            byte[] result=process.getInputStream().readNBytes(129);
            if(result.length>128||!process.waitFor(30,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException();
            return new String(result,java.nio.charset.StandardCharsets.US_ASCII).strip();
        }finally{terminate(process);}
    }
    private static void terminate(Process process){if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}
    private static Process bounded(Process process){return bounded(process,900);}
    private static Process bounded(Process process,int seconds){process.onExit().orTimeout(seconds,TimeUnit.SECONDS).exceptionally(failure->{terminate(process);return process;});return process;}
    private static void requirePostgres18()throws Exception{
        for(String command:List.of("pg_dump","pg_restore","psql")){
            Process child=bounded(process(command,"--version").start(),10);
            try{String version=new String(child.getInputStream().readNBytes(256),java.nio.charset.StandardCharsets.US_ASCII);
                if(!child.waitFor(10,TimeUnit.SECONDS)||child.exitValue()!=0||!version.matches("(?s).*\\(PostgreSQL\\) 18\\.[0-9]+.*"))throw new IllegalStateException();
            }finally{terminate(child);}
        }
    }
    private static String required(Map<String,String> env,String name){String v=env.get(name);if(v==null||v.isBlank())throw new IllegalArgumentException();return v;}
    private static void privateDirectory(Path path)throws IOException{
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
                ||!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------")))throw new IOException();
    }
}
