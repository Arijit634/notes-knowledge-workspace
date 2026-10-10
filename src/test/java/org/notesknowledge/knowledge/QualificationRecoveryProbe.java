package org.notesknowledge.knowledge;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Separate, offline JVM. Only connects to the parent's synthetic Testcontainers database. */
public final class QualificationRecoveryProbe {
    public static void main(String[] args)throws Exception {
        String stage="journal";
        try {
        try(var journal=new QualificationJournal(Path.of(args[0]),"offline-recovery-v1")) {
            if(args[1].equals("interrupt")) {
                journal.reserve(QualificationJournal.hash("uncertain-child-sdk"),"synthetic-child","embedding");
                Runtime.getRuntime().halt(77); // Deliberate process death AFTER the forced reservation.
            }
            int expected=Integer.parseInt(args[1]);
            if(journal.reservations("embedding")!=expected)throw new AssertionError("Counter changed across process restart");
            stage="database-connection";
            if(args.length>2)try(var connection=DriverManager.getConnection(args[2],"synthetic_migrator","synthetic-derivation-password")) {
                stage="root-readback";
                try(var query=connection.prepareStatement("select count(*) from knowledge.private_derived_representation r where r.owner_user_id=?::uuid and r.state='ready'")) {
                    query.setString(1,args[3]);try(var rows=query.executeQuery()){rows.next();if(rows.getInt(1)!=80)throw new AssertionError("Persisted roots lost");}
                }
                stage="vector-readback";
                try(var query=connection.prepareStatement("select count(*) from knowledge.private_derived_segment where owner_user_id=?::uuid and vector_dims(embedding)=768")) {
                    query.setString(1,args[3]);try(var rows=query.executeQuery()){rows.next();if(rows.getInt(1)!=expected)throw new AssertionError("Persisted vectors lost");}
                }
                stage="persisted-distance";
                try(var query=connection.prepareStatement("select s.embedding <=> (select embedding from knowledge.private_derived_segment where owner_user_id=?::uuid limit 1) from knowledge.private_derived_segment s where s.owner_user_id=?::uuid order by 1 limit 1")) {
                    query.setString(1,args[3]);query.setString(2,args[3]);try(var rows=query.executeQuery()){if(!rows.next()||Math.abs(rows.getDouble(1))>0.00001)throw new AssertionError("Persisted vector scoring failed");}
                }
            }
        }
        } catch(Throwable failure){System.out.println(stage+":"+failure.getClass().getSimpleName()+":"+(failure instanceof java.sql.SQLException sql?sql.getSQLState():"none"));System.exit(1);}
    }
    private QualificationRecoveryProbe(){ }
}
