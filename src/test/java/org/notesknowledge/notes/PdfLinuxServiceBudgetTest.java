package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("SECURITY")
class PdfLinuxServiceBudgetTest {
    @Test void sharedUidThreadExhaustionDeniesBeforeAnotherWorkerStarts()throws Exception {
        if(!System.getProperty("os.name").equals("Linux")){
            assertThatThrownBy(PdfLinuxServiceBudget::require).isInstanceOf(java.io.IOException.class);return;
        }
        var ready=new CountDownLatch(129);var release=new CountDownLatch(1);var threads=new ArrayList<Thread>();
        try {
            for(int n=0;n<129;n++)threads.add(Thread.ofPlatform().daemon(true).stackSize(64*1024).start(()->{
                ready.countDown();try{release.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(PdfLinuxServiceBudget::require).isInstanceOf(java.io.IOException.class);
        }finally{
            release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            for(var thread:threads){long left=end-System.nanoTime();if(left>0)thread.join(java.time.Duration.ofNanos(left));}
            assertThat(threads).allMatch(t->!t.isAlive());
        }
    }
}
