#define _GNU_SOURCE
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <wayland-server.h>
#include "droiddeck_ext.h"

static struct wl_resource *target;
void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }
const char *droiddeck_client_name(struct wl_client *client) { (void)client; return "probe"; }
struct wl_resource *droiddeck_ime_target(void) { return target; }
void droiddeck_surface_scene_origin(struct wl_resource *surface, int *x, int *y) { (void)surface; *x = *y = 0; }
void droiddeck_inject_key(uint32_t key, int pressed) { (void)key; (void)pressed; }
void droiddeck_on_text_input(int enabled, const char *program, int x, int y, int w, int h) {
    (void)program; (void)x; (void)y; (void)w; (void)h;
    printf("focus %d\n", enabled); fflush(stdout);
}

static void surface_destroy(struct wl_client *client, struct wl_resource *res) { (void)client; wl_resource_destroy(res); }
static const struct wl_surface_interface surface_impl = { .destroy = surface_destroy };
static void create_surface(struct wl_client *client, struct wl_resource *res, uint32_t id) {
    (void)res;
    target = wl_resource_create(client, &wl_surface_interface, 1, id);
    wl_resource_set_implementation(target, &surface_impl, NULL, NULL);
    droiddeck_text_input_refocus();
}
static void create_region(struct wl_client *client, struct wl_resource *res, uint32_t id) { (void)client; (void)res; (void)id; }
static const struct wl_compositor_interface compositor_impl = { create_surface, create_region };
static void bind_compositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data; (void)version;
    struct wl_resource *res = wl_resource_create(client, &wl_compositor_interface, 1, id);
    wl_resource_set_implementation(res, &compositor_impl, NULL, NULL);
}
static void seat_release(struct wl_client *client, struct wl_resource *res) { (void)client; wl_resource_destroy(res); }
static const struct wl_seat_interface seat_impl = { .release = seat_release };
static void bind_seat(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data; (void)version;
    struct wl_resource *res = wl_resource_create(client, &wl_seat_interface, 1, id);
    wl_resource_set_implementation(res, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(res, WL_SEAT_CAPABILITY_KEYBOARD);
}
int main(void) {
    struct wl_display *display = wl_display_create();
    if (!display) return 1;
    wl_global_create(display, &wl_compositor_interface, 1, NULL, bind_compositor);
    wl_global_create(display, &wl_seat_interface, 1, NULL, bind_seat);
    text_input_init(display);
    if (wl_display_add_socket(display, "outer-probe") < 0) return 2;
    puts("ready"); fflush(stdout);
    struct wl_event_loop *loop = wl_display_get_event_loop(display);
    for (int i = 0; i < 1000; i++) {
        if (wl_event_loop_dispatch(loop, 10) < 0) return 3;
        wl_display_flush_clients(display);
        if (i == 300) { text_input_host_preedit("中文", 6, 1, 2); wl_display_flush_clients(display); }
        if (i == 500) { text_input_host_commit("中文", 6); wl_display_flush_clients(display); }
        if (i == 700) { target = NULL; droiddeck_text_input_refocus(); wl_display_flush_clients(display); }
    }
    wl_display_destroy_clients(display);
    wl_display_destroy(display);
    return 0;
}
