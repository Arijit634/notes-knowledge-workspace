/* Qualification only: bounded synthetic linked-list cleanup and append checks. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "libavutil/encryption_info.h"
#include "libavutil/intreadwrite.h"
#include "libavutil/mem.h"

int main(void) {
    AVEncryptionInitInfo *head = NULL;
    for (unsigned i = 0; i < 100000; i++) {
        AVEncryptionInitInfo *node = av_encryption_init_info_alloc(0, 0, 0, 0);
        if (!node) return 2;
        node->next = head;
        head = node;
    }
    /* The unpatched recursive cleanup exhausts the qualification's 256 KiB
     * stack. The patched cleanup must use constant stack space. */
    av_encryption_init_info_free(head);
#if NKW_PATCHED_APPEND
    uint8_t *data = NULL;
    size_t size = 0;
    unsigned allocated = 0;
    AVEncryptionInitInfo *node = av_encryption_init_info_alloc(4, 0, 0, 0);
    if (!node) return 2;
    for (unsigned i = 0; i < 10000; i++) {
        AV_WB32(node->system_id, i);
        if (av_encryption_init_info_append_side_data(&data, &size, &allocated, node) < 0) return 3;
    }
    av_encryption_init_info_free(node);
    if (!data || AV_RB32(data) != 10000) return 4;
    head = av_encryption_init_info_get_side_data(data, size);
    AVEncryptionInitInfo *cursor = head;
    for (unsigned i = 0; i < 10000; i++) {
        if (!cursor || cursor->system_id_size != 4 || AV_RB32(cursor->system_id) != i) return 5;
        cursor = cursor->next;
    }
    if (cursor) return 6;
    av_encryption_init_info_free(head);
    av_free(data);
    puts("{\"ffmpegEncryptionRegression\":true,\"cleanupNodes\":100000,\"appendRecords\":10000,\"boundedStackKiB\":256}");
#endif
    return 0;
}
