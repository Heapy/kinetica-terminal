#ifndef KINETICA_TERMINAL_GRAPHICS_MEDIA_H
#define KINETICA_TERMINAL_GRAPHICS_MEDIA_H
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

/* void pointers preserve arbitrary POSIX name bytes across Kotlin/Native interop. */
static inline int kinetica_graphics_open_file(const void *path) {
    return open((const char *)path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
}
static inline int kinetica_graphics_unlink_file(const void *path) {
    return unlink((const char *)path);
}
static inline int kinetica_graphics_open_shm(const void *name) {
    return shm_open((const char *)name, O_RDONLY, 0);
}
static inline int kinetica_graphics_unlink_shm(const void *name) {
    return shm_unlink((const char *)name);
}
#endif
