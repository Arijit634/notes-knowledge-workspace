package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("SECURITY")
class PdfWorkerAttemptStoreTest {
    @TempDir Path root;
    private boolean linux(){return System.getProperty("os.name").equals("Linux");}
    private Path directory()throws Exception {
        return Files.createDirectory(root.resolve("attempts"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    private boolean unsupported() {
        if(linux())return false;
        assertThatThrownBy(()->new PdfWorkerAttemptStore(root)).isInstanceOf(java.io.IOException.class);
        return true; // Unsupported platform must deny, never skip or pretend POSIX proof.
    }
    private Path record(Path directory,String name,String body)throws Exception {
        Path file=Files.createFile(directory.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.writeString(file,body);
    }
    @Test void activeAttemptIsKeptUntilObservedTermination()throws Exception {
        if(unsupported())return;
        Path directory=directory();Process child=new ProcessBuilder("/bin/sleep","30").start();
        try(var store=new PdfWorkerAttemptStore(directory)){
            var attempt=store.record(child);
            try(var entries=Files.list(directory)){assertThat(entries.map(p->p.getFileName().toString()).toList()).hasSize(2);}
            assertThatThrownBy(attempt::close).isInstanceOf(java.io.IOException.class);
            child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();
            attempt.close();attempt.close();
            try(var entries=Files.list(directory)){assertThat(entries.map(p->p.getFileName().toString()).toList()).containsExactly("worker.lock");}
        }finally{child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
    }
    @Test void anotherSupervisorCannotAcquireTheSameDirectory()throws Exception {
        if(unsupported())return;
        Path directory=directory();try(var store=new PdfWorkerAttemptStore(directory)){
            assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        }
        try(var reopened=new PdfWorkerAttemptStore(directory)){assertThat(reopened).isNotNull();}
    }
    @Test void abandonedExitedAttemptIsRecoveredByTheNextSupervisor()throws Exception {
        if(unsupported())return;
        Path directory=directory();Process child=new ProcessBuilder("/bin/sleep","30").start();
        try {
            try(var store=new PdfWorkerAttemptStore(directory)){store.record(child);}
            child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();
            try(var reopened=new PdfWorkerAttemptStore(directory);var files=Files.list(directory)){
                assertThat(files.map(p->p.getFileName().toString()).toList()).containsExactly("worker.lock");
            }
        }finally{child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
    }
    @Test void liveMatchingCreationIdentityDeniesRecoveryWithoutKillingIt()throws Exception {
        if(unsupported())return;
        Path directory=directory();long start=ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        Path record=record(directory,"attempt-"+"a".repeat(32)+".state","nkw-pdf-attempt-v1\n"+ProcessHandle.current().pid()+"\n"+start+"\n");
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        assertThat(ProcessHandle.current().isAlive()).isTrue();assertThat(record).exists();
    }
    @Test void reusedPidCreationMismatchRemovesOnlyTheStaleRecord()throws Exception {
        if(unsupported())return;
        Path directory=directory();long start=ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        Path record=record(directory,"attempt-"+"b".repeat(32)+".state","nkw-pdf-attempt-v1\n"+ProcessHandle.current().pid()+"\n"+(start-1)+"\n");
        try(var store=new PdfWorkerAttemptStore(directory)){assertThat(record).doesNotExist();assertThat(ProcessHandle.current().isAlive()).isTrue();}
    }
    @Test void malformedOrUnexpectedInventoryIsNotDeleted()throws Exception {
        if(unsupported())return;
        Path directory=directory();Path unknown=record(directory,"unrelated","synthetic");
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        assertThat(unknown).hasContent("synthetic");
        Files.delete(unknown);Path malformed=record(directory,"attempt-"+"c".repeat(32)+".state","malformed");
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        assertThat(malformed).hasContent("malformed");
    }
    @Test void incompleteUnpublishedRecordIsRemovedWithoutTreatingPartialPidAsAuthority()throws Exception {
        if(unsupported())return;
        Path directory=directory();Path pending=record(directory,"attempt-"+"f".repeat(32)+".pending","nkw-pdf-attempt-v1\n1");
        try(var store=new PdfWorkerAttemptStore(directory)){assertThat(pending).doesNotExist();assertThat(ProcessHandle.current().isAlive()).isTrue();}
    }
    @Test void unknownInventoryPreventsPartialRecoveryToo()throws Exception {
        if(unsupported())return;
        Path directory=directory();Path pending=record(directory,"attempt-"+"f".repeat(32)+".pending","");
        Path unknown=record(directory,"unknown","synthetic");
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        assertThat(pending).exists();assertThat(unknown).hasContent("synthetic");
    }
    @Test void linksAndHardLinksAreDeniedWithoutTouchingTheirTargets()throws Exception {
        if(unsupported())return;
        Path directory=directory();Path target=record(root,"outside","synthetic");
        Path entry=directory.resolve("attempt-"+"d".repeat(32)+".state");Files.createSymbolicLink(entry,target);
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);assertThat(target).hasContent("synthetic");
        Files.delete(entry);Files.createLink(entry,target);
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);assertThat(target).hasContent("synthetic");
    }
    @Test void directoryAndRecordPermissionsAreMandatory()throws Exception {
        if(unsupported())return;
        Path directory=directory();Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Path file=record(directory,"attempt-"+"e".repeat(32)+".state","synthetic");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
    }
    @Test void inventoryLimitIsEnforcedBeforeRecovery()throws Exception {
        if(unsupported())return;
        Path directory=directory();for(int n=0;n<65;n++)record(directory,"attempt-"+String.format("%032x",n)+".state","synthetic");
        assertThatThrownBy(()->new PdfWorkerAttemptStore(directory)).isInstanceOf(java.io.IOException.class);
        try(var files=Files.list(directory)){assertThat(files.count()).isEqualTo(66);}
    }
}
