#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifdef SERVICE_BOUNDARY_DIAGNOSTIC
#include <stdio.h>
#include <string.h>
#endif

/* Managed x86_64 host boundary, installed before ANY app/proxy/cache process.
 * No command, path, policy or limit is selected by environment/user arguments.
 * The native PDF/video workers still install their stricter existing boundary.
 */
#if !defined(__linux__) || !defined(__x86_64__)
#error This managed service profile requires native Linux x86_64 qualification
#endif
static _Noreturn void unavailable(void) { _exit(125); }
static void boundary(void) {
    if(getuid()!=65533||geteuid()!=65533||getgid()!=65533||getegid()!=65533)unavailable();
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3,.pid=0};
    struct __user_cap_data_struct caps[2]={{0}};
    if(syscall(SYS_capget,&header,caps))unavailable();
    for(int i=0;i<2;i++)if(caps[i].effective||caps[i].permitted||caps[i].inheritable)unavailable();
    struct rlimit processes={300,300},cores={0,0};
    if(setrlimit(RLIMIT_NPROC,&processes)||setrlimit(RLIMIT_CORE,&cores)||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))unavailable();
    if(syscall(SYS_landlock_create_ruleset,NULL,0,LANDLOCK_CREATE_RULESET_VERSION)<3)unavailable();
    uint64_t writes=LANDLOCK_ACCESS_FS_WRITE_FILE|LANDLOCK_ACCESS_FS_REMOVE_DIR|LANDLOCK_ACCESS_FS_REMOVE_FILE
        |LANDLOCK_ACCESS_FS_MAKE_CHAR|LANDLOCK_ACCESS_FS_MAKE_DIR|LANDLOCK_ACCESS_FS_MAKE_REG
        |LANDLOCK_ACCESS_FS_MAKE_SOCK|LANDLOCK_ACCESS_FS_MAKE_FIFO|LANDLOCK_ACCESS_FS_MAKE_BLOCK
        |LANDLOCK_ACCESS_FS_MAKE_SYM|LANDLOCK_ACCESS_FS_REFER|LANDLOCK_ACCESS_FS_TRUNCATE;
    struct landlock_ruleset_attr ruleset={.handled_access_fs=writes};
    int rules=syscall(SYS_landlock_create_ruleset,&ruleset,sizeof(ruleset),0);
    int temporary=open("/tmp",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);struct stat facts;
    if(rules<0||temporary<0||fstat(temporary,&facts)||facts.st_uid!=0||!S_ISDIR(facts.st_mode))unavailable();
    struct landlock_path_beneath_attr allow={.allowed_access=writes,.parent_fd=temporary};
    if(syscall(SYS_landlock_add_rule,rules,LANDLOCK_RULE_PATH_BENEATH,&allow,0)
        ||syscall(SYS_landlock_restrict_self,rules,0))unavailable();
    close(temporary);close(rules);
    /* Prevent cross-process memory modification or creating another mount/user
     * namespace to evade custody. Normal networking/JVM/child exec remain intact.
     */
#define DENY(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(n),0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_X86_64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        /* Reject the x32 syscall namespace, not just its native-number aliases. */
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,0x40000000,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        DENY(SYS_ptrace),DENY(SYS_process_vm_writev),DENY(SYS_mount),DENY(SYS_umount2),
        DENY(SYS_pivot_root),DENY(SYS_unshare),DENY(SYS_setns),DENY(SYS_bpf),
        DENY(SYS_fsopen),DENY(SYS_fsconfig),DENY(SYS_fsmount),DENY(SYS_move_mount),
        DENY(SYS_open_tree),DENY(SYS_mount_setattr),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={.len=sizeof(filter)/sizeof(filter[0]),.filter=filter};
    if(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program))unavailable();
#undef DENY
}
int main(int argc,char **argv) {
#ifdef SERVICE_BOUNDARY_DIAGNOSTIC
    int once=argc==2&&!strcmp(argv[1],"--once");
    if(argc!=1&&!once)unavailable();
#else
    (void)argv;
    if(argc!=1)unavailable();
#endif
    if(syscall(SYS_close_range,3u,~0u,0))unavailable();
    boundary();
#ifdef SERVICE_BOUNDARY_DIAGNOSTIC
    int file=open("/provenance/service-write-probe",O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
    if(file>=0||errno!=EACCES)unavailable();
    errno=0;if(truncate("/provenance/service-write-probe",0)==0||errno!=EACCES)unavailable();
    if(symlink("/provenance/service-write-probe","/tmp/service-boundary-link"))unavailable();
    errno=0;file=open("/tmp/service-boundary-link",O_WRONLY|O_CLOEXEC);
    if(file>=0||errno!=EACCES||unlink("/tmp/service-boundary-link"))unavailable();
    struct rlimit raised={301,301};errno=0;
    if(setrlimit(RLIMIT_NPROC,&raised)==0||errno!=EPERM)unavailable();
    errno=0;if(syscall(SYS_unshare,0)==0||errno!=EPERM)unavailable();
    int temporary_file=open("/tmp/service-boundary-write",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
    if(temporary_file<0||write(temporary_file,"x",1)!=1||close(temporary_file)||unlink("/tmp/service-boundary-write"))unavailable();
    puts("{\"serviceBoundary\":true,\"trustedWriteDenied\":true,\"privateTemporaryWriteAllowed\":true,\"processLimitRaiseDenied\":true,\"namespaceEscapeDenied\":true}");
    fflush(stdout);
    execl("/jdk/bin/java","/jdk/bin/java","-Xmx64m","-XX:ActiveProcessorCount=1","-XX:+UseSerialGC",
        "-Dnkw.media.service-boundary=landlock-managed-v1","-cp","/qualification:/opt/pdf/artifacts/classes:/opt/pdf/artifacts/libraries/*",
        "org.notesknowledge.notes.ManagedBoundaryQualification",once?"--once":NULL,(char *)NULL);
#else
    execl("/app/start-full.sh","/app/start-full.sh",(char *)NULL);
#endif
    unavailable();
}
