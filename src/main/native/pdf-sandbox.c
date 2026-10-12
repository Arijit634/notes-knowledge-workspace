#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/sched.h>
#include <linux/seccomp.h>
#include <limits.h>
#include <stddef.h>
#ifdef PDF_SANDBOX_DIAGNOSTIC
#include <stdio.h>
#endif
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Fixed PDF worker launcher, never a shell or a general executable service.
 * Build on the target architecture. A build is NOT platform qualification.
 * The application must verify the exact helper/runtime/artifact identities and
 * supervise wall time before this can be registered as a production runtime.
 * All writable filesystem access is denied: input/output use pipes, scratch=0.
 */
#if defined(__aarch64__)
#define PDF_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define PDF_ARCH AUDIT_ARCH_X86_64
#else
#error Unsupported PDF sandbox architecture
#endif
#ifndef LANDLOCK_ACCESS_FS_TRUNCATE
#define LANDLOCK_ACCESS_FS_TRUNCATE (1ULL << 14)
#endif

static _Noreturn void fail_at(int line) {
#ifdef PDF_SANDBOX_DIAGNOSTIC
    /* Qualification build only: fixed source line and errno, no arguments,
     * paths, media, environment, credentials or library error strings. */
    dprintf(2, "pdf_sandbox_boundary_%d_errno_%d\n", line, errno);
#else
    (void)line;
#endif
    _exit(125);
}
#define unavailable() fail_at(__LINE__)

static void limit(int resource, rlim_t value) {
    struct rlimit ceiling = { value, value };
    if (setrlimit(resource, &ceiling)) unavailable();
}

/* Open every ancestor through O_NOFOLLOW; do not bless an operator path merely
 * because realpath() followed its links to a different tree. Keep bounded paths.
 */
static int directory(const char *path) {
    if (!path || path[0] != '/' || strlen(path) >= PATH_MAX) unavailable();
    int current = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (current < 0) unavailable();
    char copy[PATH_MAX];
    memcpy(copy, path, strlen(path) + 1);
    char *save = NULL;
    for (char *part = strtok_r(copy, "/", &save); part; part = strtok_r(NULL, "/", &save)) {
        if (!strcmp(part, ".") || !strcmp(part, "..")) unavailable();
        int next = openat(current, part, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(current);
        if (next < 0) unavailable();
        current = next;
    }
    return current;
}

static void allow_path(int rules, const char *path, uint64_t access, int is_directory) {
    int fd = is_directory ? directory(path) : open(path, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    struct stat facts;
    if (fd < 0 || fstat(fd, &facts) || S_ISLNK(facts.st_mode)) unavailable();
    struct landlock_path_beneath_attr rule = { .allowed_access = access, .parent_fd = fd };
    if (syscall(SYS_landlock_add_rule, rules, LANDLOCK_RULE_PATH_BENEATH, &rule, 0)) unavailable();
    close(fd);
}

#define ALLOW(number) \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (number), 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)

static void syscall_boundary(void) {
    /* clone3 cannot be inspected safely by classic BPF. ENOSYS selects glibc's
     * clone fallback; only same-process pthread creation is allowed there.
     * RLIMIT_NPROC is a hard real-UID-wide ceiling, not a per-worker quota.
     * The deployment must budget this shared ceiling for the application UID.
     */
    const unsigned thread_flags = CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND |
        CLONE_THREAD | CLONE_SYSVSEM | CLONE_SETTLS | CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID;
    const unsigned required = CLONE_VM | CLONE_SIGHAND | CLONE_THREAD;
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PDF_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_clone3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_clone, 0, 8),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, ~thread_flags),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, required),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, required, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        /* Execution only from the pre-opened descriptor, never a path selected
         * by the worker. CLOEXEC removes this descriptor at the first exec.
         */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execveat, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        ALLOW(SYS_read), ALLOW(SYS_write), ALLOW(SYS_readv), ALLOW(SYS_writev),
        ALLOW(SYS_pread64), ALLOW(SYS_lseek), ALLOW(SYS_close), ALLOW(SYS_close_range),
        ALLOW(SYS_openat), ALLOW(SYS_newfstatat), ALLOW(SYS_fstat), ALLOW(SYS_statx),
        ALLOW(SYS_readlinkat), ALLOW(SYS_getdents64), ALLOW(SYS_faccessat), ALLOW(SYS_getcwd),
        ALLOW(SYS_fcntl), ALLOW(SYS_dup), ALLOW(SYS_dup3),
        ALLOW(SYS_mmap), ALLOW(SYS_munmap), ALLOW(SYS_mprotect), ALLOW(SYS_madvise),
        ALLOW(SYS_mremap), ALLOW(SYS_brk), ALLOW(SYS_membarrier),
        ALLOW(SYS_futex), ALLOW(SYS_set_tid_address), ALLOW(SYS_set_robust_list),
        ALLOW(SYS_rseq), ALLOW(SYS_sched_getaffinity), ALLOW(SYS_sched_yield),
        ALLOW(SYS_getpid), ALLOW(SYS_getppid), ALLOW(SYS_gettid),
        ALLOW(SYS_getuid), ALLOW(SYS_geteuid), ALLOW(SYS_getgid), ALLOW(SYS_getegid),
        ALLOW(SYS_clock_gettime), ALLOW(SYS_clock_getres), ALLOW(SYS_clock_nanosleep),
        ALLOW(SYS_nanosleep), ALLOW(SYS_gettimeofday), ALLOW(SYS_getrusage),
        /* Do not let a confined same-UID process change another process's limits
         * or signal another process through the thread-targeted syscall.
         */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prlimit64, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)getpid(), 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_tgkill, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)getpid(), 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        ALLOW(SYS_getrandom), ALLOW(SYS_uname),
        ALLOW(SYS_rt_sigaction), ALLOW(SYS_rt_sigprocmask), ALLOW(SYS_rt_sigreturn),
        ALLOW(SYS_sigaltstack), ALLOW(SYS_restart_syscall),
        ALLOW(SYS_exit), ALLOW(SYS_exit_group),
#if defined(__x86_64__)
        ALLOW(SYS_arch_prctl), ALLOW(SYS_access), ALLOW(SYS_readlink), ALLOW(SYS_stat),
        ALLOW(SYS_getrlimit), ALLOW(SYS_poll),
#endif
        ALLOW(SYS_ppoll), ALLOW(SYS_pselect6),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)
    };
    struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)) unavailable();
}

int main(int argc, char **argv) {
    if (argc != 3 || getuid() == 0 || getuid() != geteuid() || getgid() != getegid()) unavailable();
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0 };
    struct __user_cap_data_struct capabilities[2] = {0};
    if (syscall(SYS_capget, &header, capabilities)) unavailable();
    for (size_t n = 0; n < 2; n++) {
        if (capabilities[n].effective || capabilities[n].permitted || capabilities[n].inheritable) unavailable();
    }
    pid_t parent = getppid();
    if (parent < 1 || prctl(PR_SET_PDEATHSIG, 9) || getppid() != parent) unavailable();
    if (syscall(SYS_close_range, 3u, ~0u, 0)) unavailable();
    char executable[PATH_MAX], classpath[PATH_MAX * 2], home[PATH_MAX + 32], temp[PATH_MAX + 32];
    if (strlen(argv[1]) >= PATH_MAX - 16 || strlen(argv[2]) >= PATH_MAX - 32) unavailable();
    int runtime = directory(argv[1]), artifacts = directory(argv[2]);
    close(runtime); close(artifacts);
    if (chdir(argv[2])) unavailable();
    strcpy(executable, argv[1]); strcat(executable, "/bin/java");
    strcpy(classpath, argv[2]); strcat(classpath, "/classes:");
    strcat(classpath, argv[2]); strcat(classpath, "/libraries/*");
    strcpy(home, "-Duser.home="); strcat(home, argv[2]);
    strcpy(temp, "-Djava.io.tmpdir="); strcat(temp, argv[2]);
    int executable_fd = open(executable, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    struct stat executable_facts;
    if (executable_fd != 3 || fstat(executable_fd, &executable_facts) || !S_ISREG(executable_facts.st_mode)) unavailable();
    limit(RLIMIT_AS, 1024ULL * 1024 * 1024);
    limit(RLIMIT_CPU, 10); limit(RLIMIT_CORE, 0); limit(RLIMIT_FSIZE, 0);
    limit(RLIMIT_NOFILE, 64); limit(RLIMIT_NPROC, 256);
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) unavailable();
    int abi = syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 3) unavailable();
    struct landlock_ruleset_attr policy = { .handled_access_fs = (1ULL << 15) - 1 };
    int rules = syscall(SYS_landlock_create_ruleset, &policy, sizeof(policy), 0);
    if (rules < 0) unavailable();
    uint64_t read = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR;
    allow_path(rules, argv[1], read, 1); allow_path(rules, argv[2], read, 1);
    allow_path(rules, executable, LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_READ_FILE, 0);
    /* Distribution-specific loader/library locations are deliberately fixed and
     * optional only when absent. Never grant /etc, /home, /tmp or /proc globally.
     * These directories contain trusted OS resources, not application secrets.
     */
    const char *system[] = { "/usr/lib", "/usr/lib64", "/lib", "/lib64",
        "/usr/share/fonts", "/usr/share/fontconfig", "/etc/fonts" };
    for (size_t n = 0; n < sizeof(system) / sizeof(system[0]); n++) {
        char resolved[PATH_MAX];
        if (realpath(system[n], resolved)) allow_path(rules, resolved, read, 1);
        else if (errno != ENOENT) unavailable();
    }
    /* Landlock also checks the ELF interpreter. Grant execution only to this
     * fixed platform loader, not the entire system library tree. */
#if defined(__aarch64__)
    const char *loader = "/lib/ld-linux-aarch64.so.1";
#else
    const char *loader = "/lib64/ld-linux-x86-64.so.2";
#endif
    char loader_path[PATH_MAX];
    if (!realpath(loader, loader_path)) unavailable();
    allow_path(rules, loader_path, LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_READ_FILE, 0);
    if (access("/etc/ld.so.cache", F_OK) == 0) {
        allow_path(rules, "/etc/ld.so.cache", LANDLOCK_ACCESS_FS_READ_FILE, 0);
    }
    char own_proc[PATH_MAX];
    /* /proc/self is an OS-controlled link resolved only for this same pid. */
    if (!realpath("/proc/self", own_proc)) unavailable();
    allow_path(rules, own_proc, read, 1);
    allow_path(rules, "/dev/null", LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE, 0);
    allow_path(rules, "/dev/urandom", LANDLOCK_ACCESS_FS_READ_FILE, 0);
    allow_path(rules, "/dev/random", LANDLOCK_ACCESS_FS_READ_FILE, 0);
    if (syscall(SYS_landlock_restrict_self, rules, 0)) unavailable();
    close(rules);
    syscall_boundary();
    char *const arguments[] = { executable, "-Xmx192m", "-Xss256k", "-XX:+UseSerialGC",
        "-XX:MaxDirectMemorySize=32m", "-XX:ReservedCodeCacheSize=32m",
        "-XX:CompressedClassSpaceSize=32m", "-XX:MaxMetaspaceSize=96m",
        "-XX:ActiveProcessorCount=2", "-XX:-UsePerfData", "-XX:+DisableAttachMechanism",
        "-XX:ErrorFile=/dev/null", "-Djava.awt.headless=true", home, temp,
        "-cp", classpath, "org.notesknowledge.notes.PdfPageRasterWorker", NULL };
    char *const environment[] = { "LANG=C.UTF-8", NULL };
    syscall(SYS_execveat, executable_fd, "", arguments, environment, AT_EMPTY_PATH);
    unavailable();
}
