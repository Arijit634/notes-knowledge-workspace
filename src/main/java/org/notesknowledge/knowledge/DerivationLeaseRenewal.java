package org.notesknowledge.knowledge;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Keep a bounded local transform's existing fenced claim alive. This grants
 * no source or provider authority; all dispatch and completion gates still run. */
final class DerivationLeaseRenewal implements AutoCloseable {
    private final Object lock=new Object();
    private final Thread owner=Thread.currentThread();
    private final boolean initiallyInterrupted=owner.isInterrupted();
    private final BooleanSupplier renew;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> task;
    private boolean closed,lost;

    DerivationLeaseRenewal(BooleanSupplier renew) {
        this(renew,Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("derivation-lease-",0).factory()));
    }
    DerivationLeaseRenewal(BooleanSupplier renew,ScheduledExecutorService scheduler) {
        this.renew=renew;this.scheduler=scheduler;
        task=scheduler.scheduleWithFixedDelay(this::pulse,10,10,TimeUnit.SECONDS);
    }
    private void pulse() {
        synchronized(lock){if(closed||lost)return;}
        boolean current;
        try{current=renew.getAsBoolean();}catch(RuntimeException unavailable){current=false;}
        synchronized(lock){
            if(!closed&&!current){lost=true;owner.interrupt();}
        }
    }
    void requireCurrent() {
        synchronized(lock){if(closed||lost)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);}
    }
    @Override public void close() {
        // An in-flight short heartbeat may finish, but cannot interrupt a
        // caller after this scope has ended or keep scheduling more renewals.
        boolean ownCancellation;
        synchronized(lock){if(closed)return;closed=true;ownCancellation=lost;}
        task.cancel(true);scheduler.shutdownNow();
        // Consume this attempt's lease-loss signal before reusing the caller.
        // Preserve pre-existing interruption and external-only cancellation.
        if(ownCancellation&&!initiallyInterrupted&&Thread.currentThread()==owner)Thread.interrupted();
    }
}
