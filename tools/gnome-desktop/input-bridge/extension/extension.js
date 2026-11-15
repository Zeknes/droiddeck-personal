import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';

import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';

const BUS_NAME = 'com.zeknes.DroidDeck.Input';
const OBJECT_PATH = '/com/zeknes/DroidDeck/Input';
const XML = `
<node>
  <interface name="com.zeknes.DroidDeck.Input">
    <method name="GetFocusState">
      <arg type="u" direction="out"/>
      <arg type="u" direction="out"/>
      <arg type="u" direction="out"/>
      <arg type="b" direction="out"/>
    </method>
    <method name="Commit">
      <arg type="s" direction="in"/>
      <arg type="b" direction="out"/>
    </method>
    <method name="Preedit">
      <arg type="s" direction="in"/>
      <arg type="u" direction="in"/>
      <arg type="b" direction="out"/>
    </method>
  </interface>
</node>`;

export default class DroidDeckInputExtension extends Extension {
    enable() {
        this._focus = null;
        this._epoch = 0;
        this._exported = Gio.DBusExportedObject.wrapJSObject(XML, this);
        this._exported.export(Gio.DBus.session, OBJECT_PATH);
        this._name = Gio.bus_own_name_on_connection(
            Gio.DBus.session, BUS_NAME, Gio.BusNameOwnerFlags.NONE, null, null);
    }

    disable() {
        if (this._name)
            Gio.bus_unown_name(this._name);
        this._name = 0;
        if (this._exported)
            this._exported.unexport();
        this._exported = null;
    }

    _sensitive(method) {
        return method.content_purpose === 8 || (method.content_hints & 0xc0) !== 0;
    }

    Commit(text) {
        const method = Main.inputMethod;
        if (!method || !method.currentFocus || this._sensitive(method) || !text)
            return false;
        method.commit(text);
        return true;
    }

    Preedit(text, cursor) {
        const method = Main.inputMethod;
        if (!method || !method.currentFocus || this._sensitive(method))
            return false;
        method.set_preedit_text(text, cursor, cursor, Clutter.PreeditResetMode.CLEAR);
        return true;
    }

    GetFocusState() {
        const method = Main.inputMethod;
        const focus = method ? method.currentFocus : null;
        if (focus !== this._focus) {
            this._focus = focus;
            this._epoch++;
        }
        return [
            this._epoch,
            method ? method.content_purpose : 0,
            method ? method.content_hints : 0,
            focus !== null,
        ];
    }
}
