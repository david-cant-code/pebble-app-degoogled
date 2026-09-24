// Installs a process-wide seccomp filter that makes socket(AF_INET or AF_INET6, SOCK_DGRAM)
// fail with EPROTONOSUPPORT, and lets every other call of this library's architecture through.
// One-way for the life of the process. The install goes ahead only where the platform's DNS
// client, tried on a thread that carries the filter alone, still reaches the system resolver.
// The Kotlin side (UdpSocketFilter) decodes the packed results.

#include <android/multinetwork.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

// The audit architecture the kernel reports for this ABI and the matching ELF machine. A
// 32-bit ARM process on a 64-bit kernel is AUDIT_ARCH_ARM with ARM EABI syscall numbers
// (linux v6.1, arch/arm64/include/asm/syscall.h, syscall_get_arch). 32-bit x86 would also
// need the socketcall multiplexer handled.
#if defined(__aarch64__)
#define FILTER_AUDIT_ARCH AUDIT_ARCH_AARCH64
#define FILTER_ELF_MACHINE EM_AARCH64
#elif defined(__arm__)
#define FILTER_AUDIT_ARCH AUDIT_ARCH_ARM
#define FILTER_ELF_MACHINE EM_ARM
#else
#error "socket_filter.c has no syscall constants for this ABI"
#endif

// The program loads the low 32 bits of each 64-bit argument at the argument's own offset.
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "socket_filter.c assumes a little-endian seccomp_data layout"
#endif

// Never EACCES or EPERM: libcore raises a failed name lookup as a SecurityException when errno
// holds either (android16-release, libcore, Inet6AddressImpl.lookupHostByName). Mirrored in
// SelfTestReport.datagramRefused.
#define REFUSAL_ERRNO EPROTONOSUPPORT
#define RET_REFUSE (SECCOMP_RET_ERRNO | (REFUSAL_ERRNO & SECCOMP_RET_DATA))
#define ARG_LOW(n) (offsetof(struct seccomp_data, args) + (n) * sizeof(uint64_t))
// The kernel's SOCK_TYPE_MASK: the type without SOCK_NONBLOCK and SOCK_CLOEXEC.
#define SOCKET_TYPE_MASK 0xf

// An architecture other than the compiled-in one is refused for every call, so install() must
// never run where the two disagree; UdpSocketFilter.primaryAbiIsArm and exe_machine_matches()
// are the checks that prevent it.
static struct sock_filter filter_program[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, FILTER_AUDIT_ARCH, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, RET_REFUSE),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, ARG_LOW(0)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET, 2, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET6, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, ARG_LOW(1)),
    BPF_STMT(BPF_ALU | BPF_AND | BPF_K, SOCKET_TYPE_MASK),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SOCK_DGRAM, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, RET_REFUSE),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
};
#define FILTER_PROGRAM_LENGTH (sizeof(filter_program) / sizeof(filter_program[0]))

// Result codes, mirrored in UdpSocketFilter.decode().
enum { KIND_INSTALLED = 0, KIND_ALREADY_INSTALLED = 1, KIND_REFUSED = 2, KIND_UNSUPPORTED = 3 };
enum {
    STAGE_PROBE_SIGNALED = 0,
    STAGE_PROBE_FAILED = 1,
    STAGE_NO_NEW_PRIVS = 2,
    STAGE_SECCOMP_CALL = 3,
    STAGE_THREAD_SYNC = 4,
    STAGE_POST_CHECK = 5,
};
enum {
    REASON_ARCHITECTURE_MISMATCH = 1,
    REASON_KERNEL_LACKS_FILTER_MODE = 2,
    REASON_RESOLVER_UNREACHABLE = 3,
    REASON_RESOLVER_UNCHECKABLE = 4,
};

// The probe child's exit code when PR_SET_NO_NEW_PRIVS failed; above every errno it can report.
#define PROBE_EXIT_NO_NEW_PRIVS 200

static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
static bool installed = false;

static jlong pack(int kind, int sub, int detail) {
    return ((jlong)kind << 40) | ((jlong)sub << 32) | (jlong)(uint32_t)detail;
}

// ENOSYS: no seccomp system call. EINVAL: the kernel did not take this call; a build without
// filter mode answers that way, and it is one of several paths that do (linux v6.1,
// kernel/seccomp.c, seccomp_set_mode_filter). The packed reason stands for any of them.
// probe() and install_locked() pass the same flags and program.
static jlong seccomp_error(int error) {
    if (error == ENOSYS || error == EINVAL) {
        return pack(KIND_UNSUPPORTED, REASON_KERNEL_LACKS_FILTER_MODE, error);
    }
    return pack(KIND_REFUSED, STAGE_SECCOMP_CALL, error);
}

static long set_filter(unsigned int flags) {
    struct sock_fprog program = {.len = FILTER_PROGRAM_LENGTH, .filter = filter_program};
    return syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, flags, &program);
}

// True unless the file at path is a readable ELF header for another machine. An unreadable
// header does not stop the install. Under binary translation this read can see an executable
// of this library's own architecture, so UdpSocketFilter.install checks the device's ABI list
// before it calls in here.
static bool exe_machine_matches(const char *path) {
    unsigned char header[EI_NIDENT + 4];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return true;
    ssize_t count = read(fd, header, sizeof(header));
    close(fd);
    if (count != (ssize_t)sizeof(header) || memcmp(header, ELFMAG, SELFMAG) != 0) return true;
    // e_machine: 16 bits at offset 18, in the byte order EI_DATA names.
    unsigned machine = header[EI_DATA] == ELFDATA2MSB
        ? ((unsigned)header[18] << 8) | header[19]
        : ((unsigned)header[19] << 8) | header[18];
    return machine == FILTER_ELF_MACHINE;
}

#ifndef NDEBUG
// Debug builds only: lets a test make the probe child die of SIGSYS (1) or exit with
// probe_test_exit_code (2) in place of the real calls, make a post-check that saw the refusal
// report probe_test_exit_code in its place (3), make the resolver check first create an IPv6
// datagram socket the way LineageOS's DNS client does (4), stall it past its timeout (5), or keep
// its thread alive for a while after it answers (6).
static int probe_test_mode = 0;
static int probe_test_exit_code = 0;

// Has the kernel raise SIGSYS in the calling (child) process, the way a platform policy that
// traps the install's calls would: a filter that traps getpriority, then that call.
static void trap_self(void) {
    struct sock_filter trap[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getpriority, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {.len = sizeof(trap) / sizeof(trap[0]), .filter = trap};
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program);
    syscall(__NR_getpriority, 0, 0);
}
#endif

// Makes the install's calls, and the resolver check's flags-0 form of the seccomp call, in a
// forked child first, so a platform that answers them with a signal ends the child and not the
// app. The child runs in a copy of a multithreaded process: async-signal-safe calls only.
// Returns true when the child made every call and exited 0; otherwise *failure is the result.
static bool probe(jlong *failure) {
    pid_t child = fork();
    if (child < 0) {
        *failure = pack(KIND_REFUSED, STAGE_PROBE_FAILED, errno);
        return false;
    }
    if (child == 0) {
        // Give SIGSYS its default action, so a trapped call ends the child. Raw calls: in an
        // app process libc's signal() is interposed and leaves the kernel's handler in place
        // (seen on Android 17). An all-zero kernel sigaction is SIG_DFL with an empty mask
        // on both ABIs.
        uint64_t zeroed_action[8] = {0};
        uint64_t sigsys_mask = UINT64_C(1) << (SIGSYS - 1);
        syscall(__NR_rt_sigaction, SIGSYS, zeroed_action, NULL, sizeof(uint64_t));
        syscall(__NR_rt_sigprocmask, SIG_UNBLOCK, &sigsys_mask, NULL, sizeof(uint64_t));
#ifndef NDEBUG
        if (probe_test_mode == 1) trap_self();
        if (probe_test_mode == 2) _exit(probe_test_exit_code);
#endif
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) _exit(PROBE_EXIT_NO_NEW_PRIVS);
        if (set_filter(0) != 0) _exit(errno);
        if (set_filter(SECCOMP_FILTER_FLAG_TSYNC) != 0) _exit(errno);
        _exit(0);
    }
    int status = 0;
    int attempts = 0;
    while (waitpid(child, &status, 0) < 0) {
        if (errno != EINTR || ++attempts > 16) {
            *failure = pack(KIND_REFUSED, STAGE_PROBE_FAILED, errno);
            return false;
        }
    }
    if (WIFSIGNALED(status)) {
        *failure = pack(KIND_REFUSED, STAGE_PROBE_SIGNALED, WTERMSIG(status));
        return false;
    }
    int code = WEXITSTATUS(status);
    if (code == 0) return true;
    *failure = code == PROBE_EXIT_NO_NEW_PRIVS
        ? pack(KIND_REFUSED, STAGE_NO_NEW_PRIVS, 0)
        : seccomp_error(code);
    return false;
}

#define RESOLVER_CHECK_TIMEOUT_MS 1000
#define RESOLVER_CHECK_THREAD_NAME "gravel-dnscheck"

// A DNS header that claims one question and carries none. The resolver rejects it as unparseable
// before it sends anything upstream (android16-release, DnsResolver, DnsProxyListener.cpp,
// ResNSendHandler::run).
static const uint8_t unparseable_query[12] = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};

// Attaches the filter to the calling thread alone, then hands the query to the platform's DNS
// client, which opens the resolver proxy with the same dns_open_proxy that getaddrinfo uses
// (android16-release, netd, client/NetdClient.cpp, resNetworkSend, netdClientInitDnsOpenProxy;
// bionic, libc/dns/net/getaddrinfo.c, android_getaddrinfo_proxy). outcome[0..1] is {0, 0} when
// the client reached the resolver, otherwise {reason, errno}.
static void check_resolver_on_this_thread(int outcome[3]) {
    outcome[0] = REASON_RESOLVER_UNCHECKABLE;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 || set_filter(0) != 0) {
        outcome[1] = errno;
        return;
    }
#ifndef NDEBUG
    if (probe_test_mode == 4) {
        int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            outcome[0] = REASON_RESOLVER_UNREACHABLE;
            outcome[1] = ECONNREFUSED;
            return;
        }
        close(fd);
    }
    if (probe_test_mode == 5) {
        struct timespec stall = {.tv_sec = RESOLVER_CHECK_TIMEOUT_MS / 1000 + 1};
        nanosleep(&stall, NULL);
    }
#endif
    if (__builtin_available(android 29, *)) {
        int fd = android_res_nsend(NETWORK_UNSPECIFIED, unparseable_query, sizeof(unparseable_query), 0);
        if (fd < 0) {
            outcome[0] = REASON_RESOLVER_UNREACHABLE;
            outcome[1] = -fd;
            return;
        }
        android_res_cancel(fd);
        outcome[0] = 0;
        outcome[1] = 0;
    } else {
        outcome[1] = ENOSYS;
    }
}

static void *resolver_check_thread(void *arg) {
    int fd = (int)(intptr_t)arg;
    pthread_setname_np(pthread_self(), RESOLVER_CHECK_THREAD_NAME);
    int outcome[3];
    check_resolver_on_this_thread(outcome);
    outcome[2] = gettid();
    send(fd, outcome, sizeof(outcome), MSG_NOSIGNAL);
    close(fd);
#ifndef NDEBUG
    if (probe_test_mode == 6) {
        struct timespec linger = {.tv_nsec = 200 * 1000000L};
        nanosleep(&linger, NULL);
    }
#endif
    return NULL;
}

// poll() for one descriptor, with timeout_ms counted across interruptions.
static int poll_readable(int fd, int timeout_ms) {
    struct pollfd ready = {.fd = fd, .events = POLLIN};
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    int remaining = timeout_ms;
    for (;;) {
        int count = poll(&ready, 1, remaining);
        if (count >= 0 || errno != EINTR) return count;
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long elapsed = (now.tv_sec - start.tv_sec) * 1000L + (now.tv_nsec - start.tv_nsec) / 1000000L;
        if (elapsed >= timeout_ms) return 0;
        remaining = timeout_ms - (int)elapsed;
    }
}

// Runs the check on a thread of its own and waits for it. True when it passed, with *check_thread
// set to that thread's id; otherwise *failure is the result to report.
static bool resolver_check(jlong *failure, pid_t *check_thread) {
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, fds) != 0) {
        *failure = pack(KIND_UNSUPPORTED, REASON_RESOLVER_UNCHECKABLE, errno);
        return false;
    }
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    int error = pthread_create(&thread, &attributes, resolver_check_thread, (void *)(intptr_t)fds[1]);
    pthread_attr_destroy(&attributes);
    if (error != 0) {
        close(fds[0]);
        close(fds[1]);
        *failure = pack(KIND_UNSUPPORTED, REASON_RESOLVER_UNCHECKABLE, error);
        return false;
    }
    int outcome[3] = {REASON_RESOLVER_UNCHECKABLE, ETIMEDOUT, 0};
    int count = poll_readable(fds[0], RESOLVER_CHECK_TIMEOUT_MS);
    if (count < 0) {
        outcome[1] = errno;
    } else if (count > 0) {
        ssize_t received = recv(fds[0], outcome, sizeof(outcome), MSG_WAITALL);
        if (received != (ssize_t)sizeof(outcome)) {
            outcome[0] = REASON_RESOLVER_UNCHECKABLE;
            outcome[1] = received < 0 ? errno : EPIPE;
        }
    }
    close(fds[0]);
    if (outcome[0] == 0) {
        *check_thread = outcome[2];
        return true;
    }
    *failure = pack(KIND_UNSUPPORTED, outcome[0], outcome[1]);
    return false;
}

// The process-wide install. A thread that carries a filter of its own makes the sync fail with
// that thread's id, and a failed sync attaches nothing (linux v6.1, kernel/seccomp.c,
// seccomp_can_sync_threads, seccomp_set_mode_filter). The check thread carries one until its exit
// completes, after it has answered, so the sync is retried while it names that thread.
static long set_filter_synced(pid_t check_thread) {
    struct timespec pause = {.tv_nsec = 1000000L};
    long result = set_filter(SECCOMP_FILTER_FLAG_TSYNC);
    for (int attempt = 0; check_thread != 0 && result == check_thread && attempt < 1000; attempt++) {
        nanosleep(&pause, NULL);
        result = set_filter(SECCOMP_FILTER_FLAG_TSYNC);
    }
    return result;
}

// check_resolver is false only in a forked test child, which must stay async-signal-safe.
static jlong install_locked(const char *exe_path, bool check_resolver) {
    if (installed) return pack(KIND_ALREADY_INSTALLED, 0, 0);
    if (!exe_machine_matches(exe_path)) {
        return pack(KIND_UNSUPPORTED, REASON_ARCHITECTURE_MISMATCH, 0);
    }
    jlong failure = 0;
    pid_t check_thread = 0;
    if (!probe(&failure)) return failure;
    if (check_resolver && !resolver_check(&failure, &check_thread)) return failure;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return pack(KIND_REFUSED, STAGE_NO_NEW_PRIVS, errno);
    }
    // A positive result is a thread that could not be synchronized (set_filter_synced).
    long result = set_filter_synced(check_thread);
    if (result < 0) return seccomp_error(errno);
    if (result > 0) return pack(KIND_REFUSED, STAGE_THREAD_SYNC, (int)result);
    // The program is attached from here on, whatever the check below reports.
    installed = true;

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    int error = fd >= 0 ? 0 : errno;
    if (fd >= 0) close(fd);
#ifndef NDEBUG
    if (probe_test_mode == 3 && error == REFUSAL_ERRNO) error = probe_test_exit_code;
#endif
    if (error != REFUSAL_ERRNO) return pack(KIND_REFUSED, STAGE_POST_CHECK, error);
    return pack(KIND_INSTALLED, 0, 0);
}

JNIEXPORT jlong JNICALL
Java_com_anopticlabs_gravel_socketfilter_UdpSocketFilter_nativeInstall(
    JNIEnv *env, jobject thiz, jstring exe_path) {
    (void)thiz;
    const char *path = (*env)->GetStringUTFChars(env, exe_path, NULL);
    if (path == NULL) return pack(KIND_REFUSED, STAGE_PROBE_FAILED, ENOMEM);
    pthread_mutex_lock(&install_lock);
    jlong result = install_locked(path, true);
    pthread_mutex_unlock(&install_lock);
    (*env)->ReleaseStringUTFChars(env, exe_path, path);
    return result;
}

static jint socket_outcome(int domain, int type) {
    int fd = socket(domain, type, 0);
    if (fd < 0) return errno;
    close(fd);
    return 0;
}

// Tries seven sockets from the calling thread: 0 for created, else the errno. The first four
// are the datagram sockets the filter refuses; order mirrored in UdpSocketFilter.selfTest().
JNIEXPORT jintArray JNICALL
Java_com_anopticlabs_gravel_socketfilter_UdpSocketFilter_nativeSelfTest(
    JNIEnv *env, jobject thiz) {
    (void)thiz;
    const jint outcomes[] = {
        socket_outcome(AF_INET, SOCK_DGRAM),
        socket_outcome(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC),
        socket_outcome(AF_INET6, SOCK_DGRAM),
        socket_outcome(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC),
        socket_outcome(AF_INET, SOCK_STREAM),
        socket_outcome(AF_INET6, SOCK_STREAM),
        socket_outcome(AF_UNIX, SOCK_DGRAM),
    };
    jsize count = sizeof(outcomes) / sizeof(outcomes[0]);
    jintArray array = (*env)->NewIntArray(env, count);
    if (array != NULL) (*env)->SetIntArrayRegion(env, array, 0, count, outcomes);
    return array;
}

// The program as (code, jt, jf, k) per instruction, for the test that evaluates it.
JNIEXPORT jintArray JNICALL
Java_com_anopticlabs_gravel_socketfilter_UdpSocketFilter_nativeProgram(
    JNIEnv *env, jobject thiz) {
    (void)thiz;
    jint flat[FILTER_PROGRAM_LENGTH * 4];
    for (size_t i = 0; i < FILTER_PROGRAM_LENGTH; i++) {
        flat[i * 4] = filter_program[i].code;
        flat[i * 4 + 1] = filter_program[i].jt;
        flat[i * 4 + 2] = filter_program[i].jf;
        flat[i * 4 + 3] = (jint)filter_program[i].k;
    }
    jintArray array = (*env)->NewIntArray(env, FILTER_PROGRAM_LENGTH * 4);
    if (array != NULL) (*env)->SetIntArrayRegion(env, array, 0, FILTER_PROGRAM_LENGTH * 4, flat);
    return array;
}

// The compiled-in constants, so the test holds no per-ABI literal: audit architecture, socket
// syscall number, the allow action, the refuse action.
JNIEXPORT jlongArray JNICALL
Java_com_anopticlabs_gravel_socketfilter_UdpSocketFilter_nativeConstants(
    JNIEnv *env, jobject thiz) {
    (void)thiz;
    const jlong constants[] = {
        (jlong)(uint32_t)FILTER_AUDIT_ARCH,
        (jlong)__NR_socket,
        (jlong)(uint32_t)SECCOMP_RET_ALLOW,
        (jlong)(uint32_t)RET_REFUSE,
    };
    jlongArray array = (*env)->NewLongArray(env, 4);
    if (array != NULL) (*env)->SetLongArrayRegion(env, array, 0, 4, constants);
    return array;
}

#ifndef NDEBUG
// Attaches the filter to the calling thread alone: 0 or the errno.
JNIEXPORT jint JNICALL
Java_com_anopticlabs_gravel_socketfilter_ProbeTestHooks_attachFilterToThisThread(
    JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return errno;
    return set_filter(0) == 0 ? 0 : errno;
}

JNIEXPORT void JNICALL
Java_com_anopticlabs_gravel_socketfilter_ProbeTestHooks_setProbeBehavior(
    JNIEnv *env, jobject thiz, jint mode, jint exit_code) {
    (void)env;
    (void)thiz;
    probe_test_mode = mode;
    probe_test_exit_code = exit_code;
}

// Runs the install twice in a forked child that starts as not installed, and returns both packed
// results. An install that gets as far as attaching the program can happen once per process, so
// a test that needs another one takes it here. The child makes async-signal-safe calls only.
JNIEXPORT jlongArray JNICALL
Java_com_anopticlabs_gravel_socketfilter_ProbeTestHooks_installTwiceInAChild(
    JNIEnv *env, jobject thiz, jstring exe_path) {
    (void)thiz;
    const char *path = (*env)->GetStringUTFChars(env, exe_path, NULL);
    if (path == NULL) return NULL;
    jlong results[2] = {0, 0};
    ssize_t count = -1;
    int fds[2];
    if (pipe(fds) == 0) {
        pid_t child = fork();
        if (child == 0) {
            installed = false;
            results[0] = install_locked(path, false);
            results[1] = install_locked(path, false);
            _exit(write(fds[1], results, sizeof(results)) == (ssize_t)sizeof(results) ? 0 : 1);
        }
        close(fds[1]);
        if (child > 0) {
            int attempts = 0;
            while ((count = read(fds[0], results, sizeof(results))) < 0) {
                if (errno != EINTR || ++attempts > 16) break;
            }
            waitpid(child, NULL, 0);
        }
        close(fds[0]);
    }
    (*env)->ReleaseStringUTFChars(env, exe_path, path);
    if (count != (ssize_t)sizeof(results)) return NULL;
    jlongArray array = (*env)->NewLongArray(env, 2);
    if (array != NULL) (*env)->SetLongArrayRegion(env, array, 0, 2, results);
    return array;
}
#endif
