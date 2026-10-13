# Embedded Termux:X11

Vendored from https://github.com/termux/termux-x11 at commit
`8d536db2e28f2a7c0153f5b7de63da38c1ba5f8c` (6 October 2026).
The Java engine, input handlers, extra keys, AIDL, resources, preferences,
JNI/C/C++ sources, native dependency sources, build recipes, and upstream
licenses are kept inside this folder. Namespace `com.termux.x11` is retained
because the native library registers its Java methods by class name.

`UPSTREAM.json` records the original source hashes, adapted-file hashes, and
native hashes. `removedUpstreamSha256` retains original hashes for the two unused
launch-task interpolators intentionally removed on 7 October 2026.
`UPSTREAM-SUBMODULES.txt` records native dependency commits.
`UPSTREAM-ADAPTATIONS.patch` retains historical adaptations unrelated to the removed keyboard service.
Affected historical hunks were removed; UPSTREAM.json records current adapted source hashes.
`compile-stubs/` contains the original upstream hidden Android framework
stubs; the build creates a compile-only JAR and does not package those stubs.

## Native libraries

`src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libXlorie.so` is extracted from the
official nightly APK published at `2026-10-06T06:47:55Z`. The release body
identifies the same source commit as this import. The downloaded APK was
checked against GitHub's published SHA-256:

`a3b96a5bc1ed15153e43eb2c6542803a82554eaa988f2efe1f2a17d2c7f5147f`

Both ELF architectures are checked; ARM64 LOAD segment alignment supports
16 KB pages. No external APK is installed or loaded at runtime. Android
extracts the app's packaged library into its nativeLibraryDir.

The default build consumes these pinned binaries. Native source rebuilding
can be selected with `-PtermuxX11.buildNative=true` on a Linux Android build
host with NDK `29.0.14206865`, CMake, Python 3, Bison, host GCC, Bash and patch.
Upstream's native build recipes require Unix tools and symlinks. This source
rebuild path has not been executed here. Do not refresh binaries from the
moving nightly tag without updating source, pins, hashes and adaptations
together. All native dependency sources needed by the recipes are vendored;
no submodule checkout is needed for this folder.

For another host application, set `termuxX11.applicationId` to its application
ID. The default is `com.bat.linuxapps`. Android plugin versions and repositories
are supplied by the consuming Gradle project. The module does not reference
host Java/Kotlin classes or depend on `app`, `libtermux-core`, or `libtermux-os`.

## Runtime integration

The host starts `com.termux.x11.MainActivity` in its own APK. Activities,
receivers run in `:x11`. Engine
Activities and receivers are private. Preferences is exported for the launcher
shortcut and App Info entry. The host's LinuxApplication stays
unchanged. LorieApp is adapted to a lazy ContextWrapper, so upstream state
initializes in the X11 component process without replacing the host Application.

The existing CommandExecutor still owns the native server lifetime.
`app/src/main/assets/linux-apps/x11-server.sh` receives this app's sourceDir
and nativeLibraryDir through environment variables and execs app_process
with the bundled CmdEntryPoint. The native engine is loaded from the
explicit packaged-library directory. Upstream Binder/file-descriptor and
Surface transport connect the server to LorieView.

The server remains outside PRoot. DISPLAY stays `:17`, its filesystem socket
is `$TMPDIR/.X11-unix/X17`, and TCP stays disabled. The existing shared /tmp
mapping, guest XKB files, XFCE/dbus launch, audio, ready handshake and tracked
process cleanup stay intact. The preparation marker is bumped so an app
update refreshes the launcher without discarding the rootfs or user home.

## Preferences and gestures (Phase 2)

The complete upstream UI is `LoriePreferences.java`, including its fragments.
It uses the imported preferences.xml, arrays, strings, layouts and styles;
no host preference screen or replacement settings logic is introduced.

By default, swipe upward with three fingers on the desktop to open Preferences.
The gesture uses upstream TouchInputHandler and its existing "open preferences"
action. Only swipeUpAction's XML default is changed. Swipe down still toggles
the additional keys bar; long-press still performs upstream mouse dragging.
The gear key in the extra keys bar, X11 notification, and disconnected-screen
Preferences button retain their upstream actions. The host launcher now
exposes the imported static Preferences shortcut. App Info can resolve
APPLICATION_PREFERENCES with DEFAULT. These OS entry points are configured in source
and require a subsequent build/install for device verification.

A previously saved gesture choice is preserved. To assign the new gesture
on an installation that saved another choice, use Other > Configure response
to user actions > Three finger swipe up > open preferences.

The upstream PreferenceDataStore and LorieApp listeners remain shared by the
display and Preferences components in :x11. Edits use the existing live reload
path for the window, LorieView, input handler and extra keys. Some window
settings recreate the Android activity, as upstream does; the separately
owned X server continues independently. The reflected AndroidX Preference
onSetInitialValue method has an explicit consumer keep rule, corrected from
the upstream rule's platform android.preference name. The audit also guards
all three custom-resolution parsers, cancels reconnect/settings/startup
callbacks when a display closes and serializes Binder death on the main thread.

Available upstream groups:

- Output: native/scaled/exact/custom resolution, scaling/filtering/stretch,
  orientation, PiP, fullscreen/cutout and screen idle timeout. Select Never
  under Screen idle timeout for keep-screen-on behavior.
- Pointer: trackpad, simulated touchscreen, direct touch, two-finger right
  click, external pointer capture and captured pointer transforms/speed.
- Keyboard: scancodes, additional keys and their configuration, soft keyboard
  toggle, and upstream hardware keyboard and Samsung DeX options.
- Other: clipboard, notifications, configurable user actions, secondary
  display preference storage; version is on the Preferences main screen.

## Verification status

See [the complete static audit](C:/Users/hamza/Desktop/MyApplication/docs/termux-x11-audit.md)
for PASS/MODIFIED/GAP findings and every adaptation. Hard host-process death
has no parent-death server guard; upstream debug-only native logcat cleanup
also has inherited issues. Normal tracked cleanup is checked, but universal
orphan-free/FD-leak-free behavior is not claimed.

Both phases are source-complete. Live Phase 1 rendering and Phase 2 settings
behavior remain for the user to verify; no APK was installed during this
integration. Phase 2 was explicitly requested while those live checks remain
pending. No Gradle/build, emulator or phone commands ran for Phase 2.

`python tools/check-x11-runtime.py --static-only` checks source/provenance,
resource and Bash syntax contracts without running the mock server fixtures.
The default `python tools/check-x11-runtime.py` also checks imported source/native hashes,
preference keys and option arrays, navigation, the gesture source path, live
reload wiring, AndroidX reflection keep rules, process/export declarations, launcher/App Info entry points,
resolution guards, callback cleanup, native ELF dependencies/alignment, both ABIs, shell syntax and mocked server arguments/cleanup. It also
verifies adapted source hashes, including the gesture default and removed keyboard service options. `tools/check-static.ps1` validates the
host source/resource/packaging rules. Neither check executes Android UI code.

The initial Phase 1 debug build and 17 existing unit tests passed before the
user requested that builds stop. The running release build was cancelled;
final Phase 1 and all Phase 2 changes received source/static checks only.
There is no verified release APK result. The earlier desktop supervisor
signal check passed. An unrelated desktop-worker check could not start on
Windows because Python fcntl is unavailable; its source was not changed.

For user device verification: open a live XFCE session, swipe up with three
fingers, change display resolution/scaling and pointer mode, exercise extra
keys/soft keyboard, then return to the same session. Check fullscreen,
orientation, keep-screen-on and PiP plus hardware mouse/keyboard settings
on the corresponding hardware. Compiled and live behavior are unverified.

## License boundary

Upstream Termux:X11 is GPLv3; its LICENSE and dependency notices are retained.
The same full GPL text is packaged in `src/main/assets/licenses/GPL-3.0.txt`,
and the host's license notices identify the upstream commit and local changes.
Retained native dependency license texts are packaged byte for byte under
`src/main/assets/licenses/native-dependencies/`, with an attribution index.
These notices do not establish a source distribution offer or resolve the
combined application's licensing obligations. Before distribution, arrange
delivery of the complete corresponding source, including modifications and
required build materials, under the applicable GPL terms.
Adapted imported files carry modification notices. This folder contains its
corresponding source and build recipes, but publishing only this folder does
not automatically satisfy all obligations for a combined Android application.

`:x11` is a separate process under the same application UID, not Android's
isolatedProcess security sandbox. Folder/process separation is not a guarantee
that a proprietary host may distribute this combined APK without GPL
obligations for the host. Distribution requires an appropriate licensing
assessment or upstream permission; no such exception is established by this
integration. See https://www.gnu.org/licenses/gpl-faq.en.html#MereAggregation
and https://www.gnu.org/licenses/gpl-faq.en.html#GPLInProprietarySystem.

### Owner intent and verified process audit (2026-10-09)

The owner intends the host to remain proprietary and uses this separate source module, :x11 UI process and command-line X server process to preserve an independent-work boundary. GPLv3 section 5 allows genuine aggregates of independent programs. Process/folder separation alone does not automatically establish that condition. The host currently packages X11 in the same APK and communicates through Intents, X11 sockets and custom files/messages. See [the verified license/process note](../docs/termux-license-boundary.md) and root AGENTS.md for evidence, source-delivery scope and remaining uncertainty. Preserve this architecture and its attribution; do not record a blanket closed-source guarantee.

### Product branding adaptation (2026-10-12)

The activity/task label, display notification title/channel name, disconnected-screen artwork and task/notification icons now use Linux Apps Desktop and artwork copied from the host's existing launcher/terminal vectors. The original upstream artwork remains as source provenance. The old notification channel identifier is retained internally to preserve user channel preferences; its visible name is updated. Java package names, IPC identifiers and the separate :x11/server processes are unchanged. Attribution, GPL notices and native dependency licenses remain available in the app's license notices and in this source module. UPSTREAM.json records the hashes of these adapted and added files. These changes do not change the license or settle the distribution assessment described above.
