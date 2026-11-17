# DroidDeck Pure

- Canonical repository: https://github.com/Zeknes/droiddeck-pure. Plasma Mobile is the only offered and launched desktop. Do not add KDE Desktop, GNOME, LXQt or other environment choices or installers. Shared KDE/Plasma/KWin dependencies remain required. GNOME development is discontinued.
- The independent application ID is `com.zeknes.droiddeck.pure`. It coexists with the historical `com.zeknes.droiddeck.mobileprobe` app and has separate private data. Never uninstall, clear or automatically migrate the historical installation. Keep Pure's existing signing key for future Pure upgrades; its private signing-directory name is retained.
- Use `DroidDeck Pure` for the application name. `tools/build_local.sh` produces `DroidDeck-Pure-<versionName>.apk`; build distributable packages from committed, clean `main` on ROG. GitHub Actions is disabled at repository level for local distribution. Do not change workflow files, re-enable cloud builds, or publish tags/releases without an explicit request.
- The corner build watermark is hidden by default and after resetting settings. Preserve explicit user choices, its settings toggle, real source commit/branch metadata, version details and diagnostic copying.
- `droiddeck-performance` provides bounded CPU/GPU diagnostics and an idempotent Plasma application entry. Do not equate guest benchmark FPS or KWin virtual refresh with Android panel presentation. Keep thermal limits and report unavailable measurements honestly. Android supplies networking; a missing guest Wi-Fi manager is not a connectivity defect.
- Plasma navigation is Android-hosted and independent of guest panels: Back minimizes, Home shows the Linux desktop, Overview uses the Mobile task switcher, and Keyboard toggles the existing IME. Keep a visible wake handle when the capsule idles, 48 dp targets, persisted/clamped placement, one light haptic per real tap that follows the system setting, and no input interception outside its bounds. A collapsed handle ends its drag at the nearest left/right side and keeps the height it was dragged to, and idle collapse lands on that side too. The session hands Qt programs its GPU driver; the Mobile shell wrapper alone chooses the renderer, and on Adreno 840 it is the OpenGL RHI's software device: the shell's Zink surfaces ended in a `double free or corruption`, QPainter ended in the same abort, and the software scene graph it tried next left the app drawer's grid and the quick settings labels unpainted while KWin composites with Qt Quick on llvmpipe (`KWIN_COMPOSE=Q`). KWin takes its Zink compositor only without Zink on Adreno 840 (Zink `couldn't allocate memory`, `GL_OUT_OF_MEMORY`, then a KWin crash); other GPUs and KWin on them keep the configured GPU path. On Adreno 840, only the two Settings programs also use an explicit software OpenGL RHI entry because their native Zink windows did not paint; games and other applications retain their GPU launchers.
- USB guest SSH is opt-in through `droiddeck-maintenance --public-key`; bind only 127.0.0.1:2226 with public-key authentication, preserve existing keys and user data, and never embed private keys in builds. Keep the SFTP PRoot return-value adapter confined to that subsystem.
- Preserve upstream copyright, licences and notices. Internal `com.droiddeck.launcher` namespaces and `droiddeck-*` runtime commands remain unchanged.

# Rules

- Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.

# Working on the device

- `dd-lab-build` builds only committed work. Check `git status` and `gh api user` for the identity before committing; if `git config user.name` is empty, use the `gh` login's name and noreply email for this repo, never a guess.
- Do not commit or start a build in the same step: a build started next to a failed commit fails too. Commit first, check it, then build.
- Uncommitted changes in the tree may not be yours. Read `git diff` before committing them and tell the user what they do.
- If `dd-lab-build` says "could not read DroidDeck's state", loopback adb is down and `$ADB_SERIAL` is stale. Run `adb devices`; the port changes whenever wireless debugging is toggled. Ask the user to turn it on rather than retrying.
- A build that "did not start" can be adb, not the app. Read `~/.cache/dd-lab-build.json` and `.log` for the real reason; they can be stale from an earlier build, so compare their times with `date`.
- Run `dd-lab-build` with `run_in_background` and read its output file. A foreground `sleep` is blocked; wait with Monitor and an until-loop.
- The build restarts DroidDeck and ends your process. A turn that ends this way is not a failure.
