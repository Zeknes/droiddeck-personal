#ifndef DROIDDECK_MOBILE_PROBE_H
#define DROIDDECK_MOBILE_PROBE_H
#include <stdio.h>
#include <string.h>
#include <time.h>

static void mobile_probe_write(const char *log, unsigned id, const char *event, int final) {
    static FILE *file;
    static unsigned count;
    static char previous[4096];
    char path[4096];
    if (!log || snprintf(path, sizeof(path), "%s.mobile-frames.log", log) >= (int)sizeof(path)) return;
    if (strcmp(path, previous)) {
        if (file) fclose(file);
        snprintf(previous, sizeof(previous), "%s", path);
        file = fopen(path, "w");
        count = 0;
    }
    if (!file || count >= (final ? 4096u : 4095u)) return;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    fprintf(file, "%lld.%09ld %u %s\n", (long long)ts.tv_sec, ts.tv_nsec, id, event);
    fflush(file);
    count++;
}
#endif
