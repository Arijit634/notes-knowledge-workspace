package org.notesknowledge;

import java.io.*;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST")
class PostgresRecoveryCommandTest {
    @Test void authenticatedBackupRoundTripsAndRejectsTamperingBeforeRestoreCanStart()throws Exception{
        byte[] key=new byte[32];Arrays.fill(key,(byte)17);
        byte[] original="Synthetic backup content, not production data".repeat(3000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encrypted=new ByteArrayOutputStream();PostgresRecoveryCommand.seal(new ByteArrayInputStream(original),encrypted,key,200000);
        var restored=new ByteArrayOutputStream();PostgresRecoveryCommand.open(new ByteArrayInputStream(encrypted.toByteArray()),restored,key,200000);
        assertThat(restored.toByteArray()).isEqualTo(original);
        byte[] damaged=encrypted.toByteArray();damaged[damaged.length-1]^=1;
        assertThatThrownBy(()->PostgresRecoveryCommand.open(new ByteArrayInputStream(damaged),new ByteArrayOutputStream(),key,200000)).isInstanceOf(Exception.class);
        byte[] truncated=Arrays.copyOf(encrypted.toByteArray(),encrypted.size()-20);
        assertThatThrownBy(()->PostgresRecoveryCommand.open(new ByteArrayInputStream(truncated),new ByteArrayOutputStream(),key,200000)).isInstanceOf(Exception.class);
        byte[] wrong=new byte[32];Arrays.fill(wrong,(byte)18);
        assertThatThrownBy(()->PostgresRecoveryCommand.open(new ByteArrayInputStream(encrypted.toByteArray()),new ByteArrayOutputStream(),wrong,200000)).isInstanceOf(Exception.class);
    }
    @Test void backupAndRestoreBothEnforceTheOperatorCapacityBound(){
        byte[] key=new byte[32],data=new byte[10000];
        assertThatThrownBy(()->PostgresRecoveryCommand.seal(new ByteArrayInputStream(data),new ByteArrayOutputStream(),key,1024)).isInstanceOf(IOException.class);
    }
}
