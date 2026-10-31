/* Shares the already-qualified narrow syscall/path primitives, not its entry point.
 * This is a NEW boundary/artifact and requires its OWN native qualification.
 */
#define main pdf_unused_entry
#include "pdf-sandbox.c"
#undef main
#include <stdio.h>

static unsigned number(const char *value,unsigned maximum) {
    if(!value||!*value||strlen(value)>8)unavailable();
    for(const char *p=value;*p;p++)if(*p<'0'||*p>'9')unavailable();
    unsigned long n=strtoul(value,NULL,10);if(n>maximum)unavailable();return (unsigned)n;
}
int main(int argc,char **argv) {
    /* Fixed roots: runtime, artifacts, tools, private attempt; fixed plan/clip modes. */
    if(argc!=9||getuid()==0||getuid()!=geteuid()||getgid()!=getegid())unavailable();
    int plan=!strcmp(argv[5],"plan"),verify=!strcmp(argv[5],"verify"),inspect=!strcmp(argv[5],"inspect"),audio=!strcmp(argv[5],"audio");
    int java_mode=plan||verify||inspect;
    if(!java_mode&&!audio&&strcmp(argv[5],"clip"))unavailable();
    unsigned first=number(argv[6],18000),last=number(argv[7],audio?4800000:18000),scale=number(argv[8],1000000);
    if(!scale||(!java_mode&&last<=first)||(audio&&(first||scale!=16000)))unavailable();
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3,.pid=0};
    struct __user_cap_data_struct caps[2]={0};
    if(syscall(SYS_capget,&header,caps))unavailable();
    for(int i=0;i<2;i++)if(caps[i].effective||caps[i].permitted||caps[i].inheritable)unavailable();
    pid_t parent=getppid();if(parent<1||prctl(PR_SET_PDEATHSIG,9)||getppid()!=parent)unavailable();
    if(syscall(SYS_close_range,3u,~0u,0))unavailable();
    for(int i=1;i<=4;i++){int fd=directory(argv[i]);close(fd);}
    char executable[PATH_MAX],classpath[PATH_MAX*2],input[PATH_MAX],output[PATH_MAX];
    if(strlen(argv[1])>PATH_MAX-32||strlen(argv[2])>PATH_MAX-32||strlen(argv[3])>PATH_MAX-32||strlen(argv[4])>PATH_MAX-32)unavailable();
    snprintf(executable,sizeof(executable),"%s/bin/%s",java_mode?argv[1]:argv[3],java_mode?"java":"ffmpeg");
    snprintf(classpath,sizeof(classpath),"%s/classes:%s/libraries/*",argv[2],argv[2]);
    snprintf(input,sizeof(input),"%s/input.mp4",argv[4]);snprintf(output,sizeof(output),"%s/%s",argv[4],audio?"output.wav":"output.mp4");
    int fd=open(executable,O_PATH|O_NOFOLLOW|O_CLOEXEC);struct stat facts;
    if(fd!=3||fstat(fd,&facts)||!S_ISREG(facts.st_mode)||chdir(argv[4]))unavailable();
#if defined(__aarch64__)
    limit(RLIMIT_AS,2ULL*1024*1024*1024);
#else
    limit(RLIMIT_AS,1024ULL*1024*1024);
#endif
    limit(RLIMIT_DATA,512ULL*1024*1024);limit(RLIMIT_CPU,25);limit(RLIMIT_CORE,0);
    limit(RLIMIT_FSIZE,audio?9600044:4194304);limit(RLIMIT_NOFILE,64);limit(RLIMIT_NPROC,256);
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))unavailable();
    int abi=syscall(SYS_landlock_create_ruleset,NULL,0,LANDLOCK_CREATE_RULESET_VERSION);if(abi<3)unavailable();
    struct landlock_ruleset_attr policy={.handled_access_fs=(1ULL<<15)-1};
    int rules=syscall(SYS_landlock_create_ruleset,&policy,sizeof(policy),0);if(rules<0)unavailable();
    uint64_t read=LANDLOCK_ACCESS_FS_READ_FILE|LANDLOCK_ACCESS_FS_READ_DIR;
    allow_path(rules,argv[1],read,1);allow_path(rules,argv[2],read,1);allow_path(rules,argv[3],read,1);
    allow_path(rules,input,LANDLOCK_ACCESS_FS_READ_FILE,0);
    if(!java_mode)allow_path(rules,output,LANDLOCK_ACCESS_FS_READ_FILE|LANDLOCK_ACCESS_FS_WRITE_FILE|LANDLOCK_ACCESS_FS_TRUNCATE,0);
    allow_path(rules,executable,LANDLOCK_ACCESS_FS_READ_FILE|LANDLOCK_ACCESS_FS_EXECUTE,0);
    const char *system[]={"/usr/lib","/usr/lib64","/lib","/lib64"};
    for(size_t n=0;n<sizeof(system)/sizeof(system[0]);n++){char path[PATH_MAX];if(realpath(system[n],path))allow_path(rules,path,read,1);else if(errno!=ENOENT)unavailable();}
#if defined(__aarch64__)
    const char *loader="/lib/ld-linux-aarch64.so.1";
#else
    const char *loader="/lib64/ld-linux-x86-64.so.2";
#endif
    char loader_path[PATH_MAX];if(!realpath(loader,loader_path))unavailable();
    allow_path(rules,loader_path,LANDLOCK_ACCESS_FS_READ_FILE|LANDLOCK_ACCESS_FS_EXECUTE,0);
    if(access("/etc/ld.so.cache",F_OK)==0)allow_path(rules,"/etc/ld.so.cache",LANDLOCK_ACCESS_FS_READ_FILE,0);
    char proc[PATH_MAX];if(!realpath("/proc/self",proc))unavailable();allow_path(rules,proc,read,1);
    allow_path(rules,"/dev/null",LANDLOCK_ACCESS_FS_READ_FILE|LANDLOCK_ACCESS_FS_WRITE_FILE,0);
    allow_path(rules,"/dev/urandom",LANDLOCK_ACCESS_FS_READ_FILE,0);allow_path(rules,"/dev/random",LANDLOCK_ACCESS_FS_READ_FILE,0);
    if(syscall(SYS_landlock_restrict_self,rules,0))unavailable();
    close(rules);syscall_boundary();
    /* Durable parent record must exist before parsing or transforming any byte. */
    unsigned char ready=0;if(syscall(SYS_read,0,&ready,1)!=1||ready!=0xa5)unavailable();
    char filter[512],ticks[32];
    snprintf(filter,sizeof(filter),"trim=start_frame=%u:end_frame=%u,setpts=PTS-STARTPTS,scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=0",first,last);
    snprintf(ticks,sizeof(ticks),"%u",scale);
    char *const java[]={executable,"-Xmx96m","-Xss256k","-XX:+UseSerialGC","-XX:MaxDirectMemorySize=16m",
        "-XX:ReservedCodeCacheSize=32m","-XX:CompressedClassSpaceSize=32m","-XX:MaxMetaspaceSize=64m",
        "-XX:ActiveProcessorCount=2","-XX:-UsePerfData","-XX:+DisableAttachMechanism","-XX:ErrorFile=/dev/null",
        "-Djava.io.tmpdir=/nonexistent","-Duser.home=/nonexistent","-cp",classpath,"org.notesknowledge.notes.VideoTimelineWorker",inspect?"audio":verify?"canonical":"upload",NULL};
    char *const ffmpeg[]={executable,"-hide_banner","-loglevel","error","-nostdin","-y","-xerror","-err_detect","explode",
        "-threads","1","-protocol_whitelist","file","-i","input.mp4","-map","0:v:0","-an","-sn","-dn","-map_metadata","-1",
        "-map_chapters","-1","-vf",filter,"-fps_mode","passthrough","-enc_time_base","demux","-c:v","libopenh264",
        "-profile:v","constrained_baseline","-threads","1","-g","25","-bf","0","-b:v","600k","-maxrate","800k","-bufsize","800k",
        "-pix_fmt","yuv420p","-video_track_timescale",ticks,"-movie_timescale",ticks,"-use_editlist","0","-movflags","+faststart",
        "-brand","mp42","-write_btrt","0","-fflags","+bitexact","-flags:v","+bitexact","output.mp4",NULL};
    char audio_filter[256];
    snprintf(audio_filter,sizeof(audio_filter),"aresample=16000:async=1:first_pts=0,apad=whole_len=%u,atrim=end_sample=%u,asetpts=N/SR/TB",last,last);
    char *const audio_ffmpeg[]={executable,"-hide_banner","-loglevel","error","-nostdin","-y","-xerror","-err_detect","explode",
        "-threads","1","-protocol_whitelist","file","-i","input.mp4","-map","0:a:0","-vn","-sn","-dn","-map_metadata","-1","-map_chapters","-1",
        "-af",audio_filter,"-ar","16000","-ac","1","-c:a","pcm_s16le","-f","wav","-rf64","never","-fflags","+bitexact","-flags:a","+bitexact","output.wav",NULL};
    char libraries[PATH_MAX+32];snprintf(libraries,sizeof(libraries),"LD_LIBRARY_PATH=%s",argv[3]);
    char *const environment[]={"LANG=C.UTF-8",libraries,NULL};
    syscall(SYS_execveat,fd,"",java_mode?java:audio?audio_ffmpeg:ffmpeg,environment,AT_EMPTY_PATH);unavailable();
}
