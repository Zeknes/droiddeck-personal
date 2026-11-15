import GLib from 'gi://GLib';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';

export function init() {
  let attempts = 0;
  GLib.timeout_add(GLib.PRIORITY_DEFAULT, 200, () => {
    if (++attempts > 100)
      return GLib.SOURCE_REMOVE;
    for (const actor of global.get_window_actors()) {
      const w = actor.meta_window;
      if (w.title === 'Inner entry') {
        Main.overview.hide();
        Main.activateWindow(w);
      }
    }
    if (global.display.focus_window?.get_title() === 'Inner entry') {
      console.log('TEST_ACTIVATED');
      return GLib.SOURCE_REMOVE;
    }
    return GLib.SOURCE_CONTINUE;
  });
}

export async function run() {
  return new Promise(resolve => GLib.timeout_add_seconds(
    GLib.PRIORITY_DEFAULT, 45, () => { resolve(); return GLib.SOURCE_REMOVE; }));
}

export const METRICS = {};
