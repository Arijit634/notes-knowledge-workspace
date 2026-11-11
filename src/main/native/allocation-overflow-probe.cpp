// Qualification-only check of the shipped libstdc++ aligned-allocation path.
// CVE-2026-95619 concerns implementations that round near-SIZE_MAX allocations.
// No application input is used and no large successful allocation is permitted.
#include <cstdio>
#include <cstdint>
#include <new>

int main() {
    const std::size_t sizes[] = {SIZE_MAX, SIZE_MAX - 1, SIZE_MAX - 8,
        SIZE_MAX - 1024, SIZE_MAX - 1025, SIZE_MAX - 65536};
    const std::size_t alignments[] = {8, 16, 32, 1024, 65536};
    for (std::size_t size : sizes) {
        for (std::size_t alignment : alignments) {
            auto align = static_cast<std::align_val_t>(alignment);
            void *value = ::operator new(size, align, std::nothrow);
            if (value != nullptr) {
                ::operator delete(value, align);
                return 1;
            }
        }
    }
    std::puts("{\"alignedAllocationOverflowRejected\":true,\"cases\":30}");
    return 0;
}
