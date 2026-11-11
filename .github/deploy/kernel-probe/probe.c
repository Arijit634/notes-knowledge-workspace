/* Disposable Linux x86_64 kernel diagnostic, not an application service.
 * No libc, command input, environment parsing, secrets or media processing.
 * Linux UAPI headers are used under their Linux-syscall-note exception.
 */
#if !defined(__linux__) || !defined(__x86_64__)
#error Native Linux x86_64 required
#endif
#include <asm/unistd.h>
#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/fcntl.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/prctl.h>
#include <linux/seccomp.h>
#include <stddef.h>

static long call6(long nr,long a,long b,long c,long d,long e,long f) {
    register long r10 __asm__("r10")=d;
    register long r8 __asm__("r8")=e;
    register long r9 __asm__("r9")=f;
    long result;
    __asm__ volatile("syscall":"=a"(result):"a"(nr),"D"(a),"S"(b),"d"(c),"r"(r10),"r"(r8),"r"(r9):"rcx","r11","memory");
    return result;
}
#define SYS(n,a,b,c) call6(n,(long)(a),(long)(b),(long)(c),0,0,0)
#define OUT(s) SYS(__NR_write,1,s,sizeof(s)-1)
__attribute__((noreturn)) static void finish(int code) {
    SYS(__NR_exit_group,code,0,0);
    for (;;) SYS(__NR_pause,0,0,0);
}
static void boolean(int value) { if(value) OUT("true"); else OUT("false"); }

static int child(void) {
    int result=0;
    if(SYS(__NR_prctl,PR_SET_NO_NEW_PRIVS,1,0)==0) result|=1;
    long abi=SYS(__NR_landlock_create_ruleset,0,0,LANDLOCK_CREATE_RULESET_VERSION);
    if(abi>=3) {
        result|=2;
        struct landlock_ruleset_attr attr={.handled_access_fs=(1ULL<<15)-1};
        long rules=SYS(__NR_landlock_create_ruleset,&attr,sizeof(attr),0);
        long allowed=SYS(__NR_openat,AT_FDCWD,"/probe/allowed",O_PATH|O_CLOEXEC|O_NOFOLLOW);
        struct landlock_path_beneath_attr path={.allowed_access=LANDLOCK_ACCESS_FS_READ_FILE,.parent_fd=(int)allowed};
        if(rules>=0 && allowed>=0
            && call6(__NR_landlock_add_rule,rules,LANDLOCK_RULE_PATH_BENEATH,(long)&path,0,0,0)==0
            && SYS(__NR_landlock_restrict_self,rules,0,0)==0) {
            long yes=SYS(__NR_openat,AT_FDCWD,"/probe/allowed",O_RDONLY|O_CLOEXEC);
            long no=SYS(__NR_openat,AT_FDCWD,"/probe/denied",O_RDONLY|O_CLOEXEC);
            if(yes>=0 && no==-13) result|=4;
            if(yes>=0) SYS(__NR_close,yes,0,0);
            if(no>=0) SYS(__NR_close,no,0,0);
        }
        if(allowed>=0) SYS(__NR_close,allowed,0,0);
        if(rules>=0) SYS(__NR_close,rules,0,0);
    }
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_X86_64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_socket,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={.len=sizeof(filter)/sizeof(filter[0]),.filter=filter};
    if(SYS(__NR_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)==0
        && SYS(__NR_socket,2,1,0)==-1) result|=8;
    return result;
}

__attribute__((noreturn,used)) void probe_main(void) {
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3,.pid=0};
    struct __user_cap_data_struct caps[2]={{0,0,0},{0,0,0}};
    int unprivileged=SYS(__NR_getuid,0,0,0)==65533 && SYS(__NR_geteuid,0,0,0)==65533;
    int empty=SYS(__NR_capget,&header,caps,0)==0 && !caps[0].effective && !caps[1].effective
        && !caps[0].permitted && !caps[1].permitted && !caps[0].inheritable && !caps[1].inheritable;
    int status=0,flags=0;
    long pid=SYS(__NR_fork,0,0,0);
    if(pid==0) finish(child());
    if(pid>0 && call6(__NR_wait4,pid,(long)&status,0,0,0,0)==pid && (status&127)==0) flags=(status>>8)&255;
    OUT("{\"kernelProbe\":true,\"uidUnprivileged\":");boolean(unprivileged);
    OUT(",\"capabilitiesEmpty\":");boolean(empty);
    OUT(",\"noNewPrivileges\":");boolean(flags&1);
    OUT(",\"landlockAbiAtLeast3\":");boolean(flags&2);
    OUT(",\"landlockFilesystemEnforced\":");boolean(flags&4);
    OUT(",\"seccompEnforced\":");boolean(flags&8);
    OUT(",\"applicationDataAccessed\":false,\"providerCalls\":0}\n");
    /* TCP health only: no HTTP routes, request parsing or returned metadata.
     * Drain accepted connections so the health backlog cannot fill up. */
    struct {unsigned short family,port;unsigned int address;unsigned char zero[8];} address={2,0x1027,0,{0}};
    long listener=SYS(__NR_socket,2,1,0);
    if(listener<0 || SYS(__NR_bind,listener,&address,sizeof(address))!=0 || SYS(__NR_listen,listener,16,0)!=0) finish(2);
    for(;;) {
        long peer=call6(__NR_accept4,listener,0,0,O_CLOEXEC,0,0);
        if(peer>=0) SYS(__NR_close,peer,0,0);
        else if(peer!=-4) finish(3);
    }
}
__asm__(".global _start\n_start:\n xor %rbp,%rbp\n andq $-16,%rsp\n call probe_main\n ud2\n");
