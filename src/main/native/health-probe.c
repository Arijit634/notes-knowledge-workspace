#define _POSIX_C_SOURCE 200809L
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Status only, fixed private listener. Never reads configuration or logs data. */
static int64_t milliseconds(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC,&now)) return -1;
    return (int64_t)now.tv_sec*1000+now.tv_nsec/1000000;
}
static int wait_for(int fd,short events,int64_t deadline) {
    for (;;) {
        int64_t now=milliseconds(),left=deadline-now;
        if (now<0 || left<=0 || left>2500) return 0;
        struct pollfd item={.fd=fd,.events=events};
        int result=poll(&item,1,(int)left);
        if (result<0 && errno==EINTR) continue;
        return result>0 && !(item.revents&POLLNVAL);
    }
}
int main(int argc,char **argv) {
    (void)argv;
    if (argc!=1 || getuid()==0 || getuid()!=geteuid()) return 1;
    int64_t started=milliseconds();
    if (started<0) return 1;
    int64_t deadline=started+2500;
    int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0),passed=0;
    if (fd<0) return 1;
    int flags=fcntl(fd,F_GETFL);
    if (flags<0 || fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0) goto done;
    struct sockaddr_in target={.sin_family=AF_INET,.sin_port=htons(8081)};
    target.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if (connect(fd,(struct sockaddr *)&target,sizeof(target))<0 && errno!=EINPROGRESS) goto done;
    if (!wait_for(fd,POLLOUT,deadline)) goto done;
    int error=0;socklen_t length=sizeof(error);
    if (getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&length)<0 || error) goto done;
    static const char request[]="GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    size_t sent=0;
    while (sent<sizeof(request)-1) {
        if (!wait_for(fd,POLLOUT,deadline)) goto done;
        ssize_t amount=send(fd,request+sent,sizeof(request)-1-sent,MSG_NOSIGNAL);
        if (amount<0 && (errno==EINTR || errno==EAGAIN || errno==EWOULDBLOCK)) continue;
        if (amount<=0) goto done;
        sent+=(size_t)amount;
    }
    char status[13];size_t used=0;
    while (used<sizeof(status)) {
        if (!wait_for(fd,POLLIN,deadline)) goto done;
        ssize_t amount=recv(fd,status+used,sizeof(status)-used,0);
        if (amount<0 && (errno==EINTR || errno==EAGAIN || errno==EWOULDBLOCK)) continue;
        if (amount<=0) goto done;
        used+=(size_t)amount;
    }
    passed=(!memcmp(status,"HTTP/1.1",8) || !memcmp(status,"HTTP/1.0",8))
        && !memcmp(status+8," 200",4) && (status[12]==' ' || status[12]=='\r');
done:
    close(fd);
    return passed?0:1;
}
