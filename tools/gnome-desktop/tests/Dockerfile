FROM droiddeck-ubuntu-base:26.04.1
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        gnome-shell mutter mutter-dev-bin gnome-session-bin \
        weston wayland-utils \
        dbus dbus-x11 libglib2.0-bin \
        pipewire wireplumber \
        ibus python3-gi python3-gi-cairo gir1.2-ibus-1.0 gir1.2-gtk-4.0 \
        libgl1-mesa-dri libegl-mesa0 fonts-noto-cjk xkb-data x11-xkb-utils \
        procps psmisc patch diffutils proot \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
RUN useradd -m -s /bin/bash tester
USER tester
WORKDIR /home/tester
