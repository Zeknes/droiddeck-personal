#define _GNU_SOURCE
#include <wayland-client.h>
#include "xdg.h"
#include "viewporter.h"
#include "presentation.h"
#include "pixel.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
struct wl_compositor *c; struct wl_subcompositor *sc; struct wl_shm *shm; struct xdg_wm_base *wm; struct wp_viewporter *vp; struct wp_presentation *pres; struct wp_single_pixel_buffer_manager_v1 *px;
static int ready, framed, presented;
static void ping(void*d,struct xdg_wm_base*w,uint32_t n){(void)d;xdg_wm_base_pong(w,n);} static const struct xdg_wm_base_listener wml={ping};
static void gc(void*d,struct wl_registry*r,uint32_t n,const char*i,uint32_t v){(void)d;(void)v;
#define B(name,iface,var,ver) if(!strcmp(i,name)) var=wl_registry_bind(r,n,&iface,ver)
 B("wl_compositor",wl_compositor_interface,c,4); B("wl_subcompositor",wl_subcompositor_interface,sc,1); B("wl_shm",wl_shm_interface,shm,1); B("xdg_wm_base",xdg_wm_base_interface,wm,1); B("wp_viewporter",wp_viewporter_interface,vp,1); B("wp_presentation",wp_presentation_interface,pres,1); B("wp_single_pixel_buffer_manager_v1",wp_single_pixel_buffer_manager_v1_interface,px,1);
}
static void gr(void*d,struct wl_registry*r,uint32_t n){(void)d;(void)r;(void)n;} static const struct wl_registry_listener rl={gc,gr};
static void cfg(void*d,struct xdg_surface*s,uint32_t serial){(void)d;xdg_surface_ack_configure(s,serial);ready=1;} static const struct xdg_surface_listener xl={cfg};
static void tc(void*d,struct xdg_toplevel*t,int32_t w,int32_t h,struct wl_array*a){(void)d;(void)t;(void)w;(void)h;(void)a;} static void closewin(void*d,struct xdg_toplevel*t){(void)d;(void)t;} static const struct xdg_toplevel_listener tl={.configure=tc,.close=closewin};
static void frame(void*d,struct wl_callback*cb,uint32_t t){(void)d;(void)t;framed++;wl_callback_destroy(cb);} static const struct wl_callback_listener fl={frame};
static void clockid(void*d,struct wp_presentation*p,uint32_t id){(void)d;(void)p;(void)id;} static const struct wp_presentation_listener pl={clockid};
static void syncout(void*d,struct wp_presentation_feedback*f,struct wl_output*o){(void)d;(void)f;(void)o;} static void show(void*d,struct wp_presentation_feedback*f,uint32_t a,uint32_t b,uint32_t n,uint32_t r,uint32_t h,uint32_t l,uint32_t flags){(void)d;(void)f;(void)a;(void)b;(void)n;(void)r;(void)h;(void)l;(void)flags;presented=1;} static void discard(void*d,struct wp_presentation_feedback*f){(void)d;(void)f;presented=-1;} static const struct wp_presentation_feedback_listener pfl={syncout,show,discard};
int main(int argc,char**argv){struct wl_display*d=wl_display_connect(NULL); if(!d)return 2;struct wl_registry*r=wl_display_get_registry(d);wl_registry_add_listener(r,&rl,NULL);wl_display_roundtrip(d);if(!c||!sc||!shm||!wm||!vp||!pres||!px)return 3;xdg_wm_base_add_listener(wm,&wml,NULL);wp_presentation_add_listener(pres,&pl,NULL);
struct wl_surface*p=wl_compositor_create_surface(c),*s=wl_compositor_create_surface(c);struct xdg_surface*x=xdg_wm_base_get_xdg_surface(wm,p);xdg_surface_add_listener(x,&xl,NULL);struct xdg_toplevel*t=xdg_surface_get_toplevel(x);xdg_toplevel_add_listener(t,&tl,NULL);xdg_toplevel_set_title(t,"KWin timing reproduction");struct wl_subsurface*sub=wl_subcompositor_get_subsurface(sc,s,p);wl_subsurface_set_sync(sub);struct wp_viewport*view=wp_viewporter_get_viewport(vp,p);wl_surface_commit(p);while(!ready)wl_display_dispatch(d);
int fd=memfd_create("repro",0);ftruncate(fd,640*480*4);uint32_t*buf=mmap(NULL,640*480*4,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);for(int i=0;i<640*480;i++)buf[i]=0xffff0000;struct wl_shm_pool*pool=wl_shm_create_pool(shm,fd,640*480*4);struct wl_buffer*red=wl_shm_pool_create_buffer(pool,0,640,480,640*4,WL_SHM_FORMAT_XRGB8888);struct wl_buffer*black=wp_single_pixel_buffer_manager_v1_create_u32_rgba_buffer(px,0,0,0,0xffffffff);
wl_surface_attach(s,red,0,0);wl_surface_damage_buffer(s,0,0,640,480);wl_surface_commit(s);wl_surface_attach(p,black,0,0);wp_viewport_set_destination(view,640,480);wl_surface_damage(p,0,0,argc>1?640:0,argc>1?480:0);struct wp_presentation_feedback*f=wp_presentation_feedback(pres,p);wp_presentation_feedback_add_listener(f,&pfl,NULL);struct wl_callback*cb=wl_surface_frame(p);wl_callback_add_listener(cb,&fl,NULL);wl_surface_commit(p);wl_display_roundtrip(d);sleep(2);wl_display_roundtrip(d);printf("damage=%d frame=%d feedback=%d\n",argc>1,framed,presented);return !(framed && presented);}
