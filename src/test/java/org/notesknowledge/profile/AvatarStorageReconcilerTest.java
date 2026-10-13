package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("FAST") @Tag("SECURITY")
class AvatarStorageReconcilerTest {
    @Test void sweepsAreBoundedAdvanceIndependentCursorsAndRestartOnlyResetsScanPosition(){
        var avatars=mock(AvatarService.class);var publicAvatars=mock(PublicAvatarService.class);
        var id=UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");String key="private-avatar/"+"a".repeat(64);
        when(avatars.reconcilePendingPage(null,5)).thenReturn(id);when(avatars.reconcile(null,5)).thenReturn(key);
        when(avatars.reconcilePendingPage(id,5)).thenThrow(new IllegalStateException("synthetic"));
        var privateSweeper=new PrivateAvatarStorageReconciler(avatars,Clock.systemUTC());
        privateSweeper.sweep();privateSweeper.sweep();
        verify(avatars).reconcilePendingPage(null,5);verify(avatars).reconcilePendingPage(id,5);
        verify(avatars).reconcile(null,5);verify(avatars).reconcile(key,5);verifyNoMoreInteractions(avatars);
        new PrivateAvatarStorageReconciler(avatars,Clock.systemUTC()).sweep();
        verify(avatars,times(2)).reconcilePendingPage(null,5);
        String publicKey="public-profile-avatar/"+"a".repeat(64);
        when(publicAvatars.reconcile(null,5)).thenReturn(publicKey);
        var publicSweeper=new PublicAvatarStorageReconciler(publicAvatars,Clock.systemUTC());
        publicSweeper.sweep();publicSweeper.sweep();
        verify(publicAvatars).reconcile(null,5);verify(publicAvatars).reconcile(publicKey,5);verifyNoMoreInteractions(publicAvatars);
    }
    @Test void pendingCleanupContinuesPastFailedObjectsAndInventoryProgressCanContainNoOldObjects(){
        var assets=mock(AvatarAssetRepository.class);var objects=mock(AvatarObjectStore.class);
        var service=new AvatarService(mock(AvatarValidator.class),objects,mock(AvatarTransactions.class),assets,Clock.systemUTC());
        var id=UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");String key="private-avatar/"+"a".repeat(64);
        when(assets.pendingAfter(null,1)).thenReturn(List.of(new AvatarAsset(id,key)));
        when(assets.pendingAfter(id,1)).thenReturn(List.of());doThrow(new IllegalStateException("synthetic")).when(objects).delete(key);
        assertThat(service.reconcilePendingPage(null,1)).isEqualTo(id);assertThat(service.reconcilePendingPage(id,1)).isNull();
        verify(assets,never()).cleaned(any(),any());
        when(objects.scanBefore(any(),isNull(),eq(5))).thenReturn(new AvatarObjectStore.InventoryPage(List.of(),key));
        assertThat(service.reconcile(null,5)).isEqualTo(key);
        for(int invalid:new int[]{0,101}){
            assertThatThrownBy(()->service.reconcilePendingPage(null,invalid)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
            assertThatThrownBy(()->service.reconcile(null,invalid)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        }
    }
}
