package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

/** Fault control only; the separate fork suite proves real child timeout/crash/OOM behavior. */
@Tag("FAST")
class AttachmentCustodyCleanupTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"success", "invalid", "timeout", "crash", "database", "storage"})
    void privateInputCustodyIsRemovedForEveryOutcome(String outcome) throws Exception {
        Path custody = Files.createDirectory(temporary.resolve("owned-input"));
        var transactions = mock(AttachmentTransactions.class);
        var repository = mock(AttachmentRepository.class);
        var store = mock(PrivateAttachmentObjectStore.class);
        var validator = mock(AttachmentMediaValidator.class);
        var rates = mock(RateControlService.class);
        var media = new AttachmentMediaValidator.Validated("audio", "audio/wav", 46, null, null, 0.001, null);
        when(validator.validate(any(), any(), any())).thenReturn(media);
        if (outcome.equals("invalid")) when(validator.validate(any(), any(), any()))
                .thenThrow(ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT));
        if (outcome.equals("timeout") || outcome.equals("crash")) when(validator.validate(any(), any(), any()))
                .thenThrow(ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
        if (outcome.equals("database")) when(transactions.accept(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("Synthetic database failure"));
        if (outcome.equals("storage")) doThrow(new IllegalStateException("Synthetic storage failure"))
                .when(store).write(any(), any(), anyLong());
        var service = new AttachmentUploadService(transactions, repository, store, validator, rates,
                new RateKeyDeriver("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="), Clock.systemUTC());
        var file = new MockMultipartFile("file", "synthetic.wav", "audio/wav", AttachmentMediaValidatorTest.wav(8000, 1, 1));
        UUID owner = UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");
        try (var directories = mockStatic(AttachmentParserRuntime.class)) {
            directories.when(AttachmentParserRuntime::privateDirectory).thenReturn(custody);
            directories.when(() -> AttachmentParserRuntime.removeDirectory(custody)).thenCallRealMethod();
            if (outcome.equals("success")) service.upload(owner, owner, new MockHttpServletRequest(), file);
            else assertThatThrownBy(() -> service.upload(owner, owner, new MockHttpServletRequest(), file))
                    .isInstanceOf(outcome.equals("database") ? DataAccessResourceFailureException.class : ApiFailureException.class);
        }
        assertThat(Files.exists(custody)).isFalse();
        verify(store, times(outcome.equals("success") ? 0 : 1)).delete(any());
    }
}
