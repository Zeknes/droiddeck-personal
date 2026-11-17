#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdarg.h>
#include <sys/prctl.h>

int prctl(int option, ...) {
    va_list args;
    va_start(args, option);
    unsigned long a2 = va_arg(args, unsigned long);
    unsigned long a3 = va_arg(args, unsigned long);
    unsigned long a4 = va_arg(args, unsigned long);
    unsigned long a5 = va_arg(args, unsigned long);
    va_end(args);
    int (*next)(int, unsigned long, unsigned long, unsigned long, unsigned long) = dlsym(RTLD_NEXT, "prctl");
    int result = next(option, a2, a3, a4, a5);
    return option == PR_SET_DUMPABLE && result == PR_SET_DUMPABLE ? 0 : result;
}
