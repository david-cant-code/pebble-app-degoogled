// Installs a process-wide seccomp filter that makes socket(AF_INET or AF_INET6, SOCK_DGRAM)
// fail with EACCES, and lets every other system call through. One-way for the life of the
// process. The Kotlin side (UdpSocketFilter) decodes the packed results.

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
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

#define RET_REFUSE (SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA))
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
enum { REASON_ARCHITECTURE_MISMATCH = 1, REASON_KERNEL_LACKS_FILTER_MODE = 2 };

// The probe child's exit code when PR_SET_NO_NEW_PRIVS failed; above every errno it can report.
#define PROBE_EXIT_NO_NEW_PRIVS 200

static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
static bool installed = false;

static jlong pack(int kind, int sub, int detail) {
    return ((jlong)kind << 40) | ((jlong)sub << 32) | (jlong)(uint32_t)detail;
}

// ENOSYS: no seccomp system call. EINVAL: a kernel built without filter mode, or one that
// rejects the call's flags or program (linux v6.1, kernel/seccomp.c, seccomp_set_mode_filter
// and seccomp_prepare_filter). probe() and install_locked() pass the same flags and program.
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
// probe_test_exit_code (2) in place of the real calls, or make a post-check that saw EACCES
// report probe_test_exit_code in its place (3).
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

// Makes the install's two calls in a forked child first, so a platform that answers them with
// a signal ends the child and not the app. The child runs in a copy of a multithreaded process:
// async-signal-safe calls only. Returns true when the child made both calls and exited 0;
// otherwise *failure is the result to report.
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

static jlong install_locked(const char *exe_path) {
    if (installed) return pack(KIND_ALREADY_INSTALLED, 0, 0);
    if (!exe_machine_matches(exe_path)) {
        return pack(KIND_UNSUPPORTED, REASON_ARCHITECTURE_MISMATCH, 0);
    }
    jlong failure = 0;
    if (!probe(&failure)) return failure;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return pack(KIND_REFUSED, STAGE_NO_NEW_PRIVS, errno);
    }
    // With TSYNC a positive return is the id of a thread that could not be synchronized, and
    // the filter is then attached to no thread (linux v6.1, kernel/seccomp.c,
    // seccomp_attach_filter).
    long result = set_filter(SECCOMP_FILTER_FLAG_TSYNC);
    if (result < 0) return seccomp_error(errno);
    if (result > 0) return pack(KIND_REFUSED, STAGE_THREAD_SYNC, (int)result);
    // The program is attached from here on, whatever the check below reports.
    installed = true;

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    int error = fd >= 0 ? 0 : errno;
    if (fd >= 0) close(fd);
#ifndef NDEBUG
    if (probe_test_mode == 3 && error == EACCES) error = probe_test_exit_code;
#endif
    if (error != EACCES) return pack(KIND_REFUSED, STAGE_POST_CHECK, error);
    return pack(KIND_INSTALLED, 0, 0);
}

JNIEXPORT jlong JNICALL
Java_com_anopticlabs_gravel_socketfilter_UdpSocketFilter_nativeInstall(
    JNIEnv *env, jobject thiz, jstring exe_path) {
    (void)thiz;
    const char *path = (*env)->GetStringUTFChars(env, exe_path, NULL);
    if (path == NULL) return pack(KIND_REFUSED, STAGE_PROBE_FAILED, ENOMEM);
    pthread_mutex_lock(&install_lock);
    jlong result = install_locked(path);
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
            results[0] = install_locked(path);
            results[1] = install_locked(path);
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
