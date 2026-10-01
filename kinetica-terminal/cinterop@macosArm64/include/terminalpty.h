#ifndef KINETICA_TERMINAL_PTY_H
#define KINETICA_TERMINAL_PTY_H
#include <util.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <libproc.h>
#include <string.h>

/* Inspect only the shell process owned by this PTY; no shell injection or rc-file hooks. */
static inline int kinetica_pty_cwd(int pid, char *directory, size_t capacity) {
    struct proc_vnodepathinfo info;
    if (proc_pidinfo(pid, PROC_PIDVNODEPATHINFO, 0, &info, sizeof(info)) != sizeof(info)) return -1;
    size_t length = strnlen(info.pvi_cdir.vip_path, sizeof(info.pvi_cdir.vip_path));
    if (length == 0 || length >= capacity) { errno = ENAMETOOLONG; return -1; }
    memcpy(directory, info.pvi_cdir.vip_path, length);
    directory[length] = 0;
    return 0;
}

/* All argv/environment allocation happens BEFORE fork. The child executes only libc's
 * async-signal-safe calls, never returning into the multithreaded Kotlin runtime. */
static inline int kinetica_pty_spawn(const char *path, char *const argv[], char *const envp[],
                                    const char *directory, int columns, int rows, int *child) {
    int errors[2];
    if (pipe(errors) < 0) return -1;
    fcntl(errors[0], F_SETFD, FD_CLOEXEC);
    fcntl(errors[1], F_SETFD, FD_CLOEXEC);
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns };
    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &size);
    if (pid == 0) {
        close(errors[0]);
        if (chdir(directory) == 0) execve(path, argv, envp);
        int error = errno;
        (void)write(errors[1], &error, sizeof(error));
        _exit(127);
    }
    int error = errno;
    close(errors[1]);
    if (pid < 0) { close(errors[0]); errno = error; return -1; }
    ssize_t result;
    do { result = read(errors[0], &error, sizeof(error)); } while (result < 0 && errno == EINTR);
    close(errors[0]);
    if (result != 0) {
        if (result < 0) { error = errno; kill(pid, SIGKILL); }
        close(master); while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        errno = error; return -1;
    }
    fcntl(master, F_SETFD, FD_CLOEXEC);
    fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);
    *child = pid;
    return master;
}

static inline int kinetica_pty_resize(int fd, int columns, int rows) {
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns };
    return ioctl(fd, TIOCSWINSZ, &size);
}

/* Called off the UI thread after closing the master. Reap exactly this child, with a bounded
 * grace period for a shell that handles HUP; do not leave zombies or kill an unrelated process. */
static inline int kinetica_pty_status(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
static inline int kinetica_pty_reap(int pid) {
    int status = 0;
    for (int i = 0; i < 50; ++i) {
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) return kinetica_pty_status(status);
        if (result < 0 && errno != EINTR) return -1;
        usleep(10000);
    }
    kill(-pid, SIGKILL);
    kill(pid, SIGKILL);
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    return kinetica_pty_status(status);
}
#endif
