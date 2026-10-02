# Soft TEMPEST — Android display-masking overlay

A **defensive** Android app that renders a full-screen, click-through layer of GPU-generated
pixel noise above everything on screen. The goal is to raise the noise floor of the
electromagnetic emission of the display path (soft TEMPEST, Kuhn & Anderson 1998), making
remote reconstruction of the screen image from compromising emanations harder.

It protects **your own device only**. It does not capture, transmit, or attack anything.

> Specification: [`SPEC.md`](SPEC.md).

## Limitations — read before relying on this

This is a prototype and a **complement to hardware shielding, not a replacement**. The
following limits are fundamental to how Android and signal averaging work; the app does not
try to work around them.

1. **`FLAG_SECURE` windows are not covered.** Banking apps, DRM video, password prompts and
   any other window flagged secure are rendered by the system so that application overlays
   never appear above them. Those screens are **not masked** by this app. Doing so would
   require root or a custom ROM, which is out of scope.
2. **Averaging.** A static noise pattern is useless: an attacker averaging *N* captured frames
   reduces it by 1/√N and recovers the image. This app therefore re-randomises the noise
   **every frame** with a fresh 32-bit seed from a CSPRNG, so there is no fixed residue. Even
   so, averaging still reduces the *effect* of the noise; the overlay raises the noise floor,
   it does not make the emission information-free.
3. **No measurement, no proof.** The effectiveness has **not** been measured with RF
   equipment on any real device. Whether, and by how much, the overlay degrades an actual
   attack depends on the panel, the display interface (MIPI DSI/DP), the attacker's antenna,
   distance and integration time. Until you measure it, treat the protection as unknown.
4. **Battery.** Continuous full-screen rendering is expensive. The noise is generated in an
   OpenGL ES 3.0 fragment shader (not on the CPU/Canvas), and rendering pauses while the screen
   is off, but the overlay still costs noticeable battery while active.
5. **Readability trade-off.** Amplitude is the real knob: higher amplitude = more masking =
   harder to read. There is no setting that protects for free.
6. **Alpha blending only.** As a normal overlay window the app can only blend over content;
   it cannot modulate the display's backlight, timing or drive voltages.
7. **Opacity cap from untrusted-touch blocking (Android 12+).** The system drops touches that
   pass through a non-trusted overlay whose *window* alpha exceeds a threshold (0.8 by
   default), even with `FLAG_NOT_TOUCHABLE`. The service therefore sets the window alpha just
   below `InputManager.getMaximumObscuringOpacityForTouch()`, and the shader compensates so the
   amplitude slider still maps to effective opacity up to that cap. Without this, the launcher
   and every other app become unclickable while the overlay is on.

   This is also why the renderer is a `TextureView`, not the `GLSurfaceView` the spec suggests.
   SurfaceFlinger reports every buffer-backed layer to the input dispatcher, and the dispatcher
   combines the opacities of all layers of one UID above the touched app
   (`1 − (1−a)(1−b)`). A `SurfaceView` adds a second layer, so 0.79 window + 0.79 surface
   = 0.96 > 0.8 and touches are dropped. A `TextureView` is composited into the window's own
   buffer, leaving a single layer whose alpha is the window alpha.
8. **Lock screen.** `TYPE_APPLICATION_OVERLAY` is z-ordered below the keyguard, status bar and
   navigation bar (layer 11 vs 17/15/24 in the Android 15 window policy), so the standard
   backend never covers the lock screen. The optional accessibility backend uses
   `TYPE_ACCESSIBILITY_OVERLAY` (layer 31), which does cover it. Neither covers the always-on
   display or a switched-off panel.
9. **"Screen overlay detected".** Permission dialogs and some Settings screens refuse input
   while any overlay is on top (`FLAG_WINDOW_IS_OBSCURED`). Stop the overlay to use them.

## How it works

| Component | Role |
|-----------|------|
| `MainActivity` | Permission flow (`SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`), Start/Stop switch, amplitude slider, noise-mode selection, disclaimer |
| `NoiseOverlayWindow` | The click-through full-screen window itself (`FLAG_NOT_TOUCHABLE \| FLAG_NOT_FOCUSABLE \| FLAG_LAYOUT_IN_SCREEN \| FLAG_LAYOUT_NO_LIMITS`, `PixelFormat.TRANSLUCENT`, `MATCH_PARENT × MATCH_PARENT`), shared by both backends; pauses rendering on screen off |
| `OverlayService` | Standard backend: foreground service (`specialUse`) adding the window as `TYPE_APPLICATION_OVERLAY`; notification with a Stop action |
| `NoiseAccessibilityService` | Optional trusted backend: accessibility service adding the window as `TYPE_ACCESSIBILITY_OVERLAY` (above the lock screen, no opacity cap); takes over from `OverlayService` while enabled |
| `OverlayController` / `OverlayState` | Start/Stop routing between the backends and the shared running state |
| `NoiseTextureView` | Transparent `TextureView` with its own EGL thread (RGBA8888, OpenGL ES 3.0, continuous rendering paced to the display refresh rate) |
| `NoiseRenderer` | Draws one full-screen triangle each frame, uploads `uTime`, `uAmplitude`, `uMode`, `uResolution` and a fresh random `uSeed` |
| `Shaders` | GLSL ES 3.00 vertex + fragment shader |
| `NoiseSettings` | `SharedPreferences` store; the service observes it, so slider/mode changes apply live |
| `BootReceiver` | On `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, restarts the overlay if it was running when the device shut down and "Restore on boot" is enabled |

### Shader design

* **Per-pixel, per-frame PRNG.** The fragment shader hashes `(gl_FragCoord.xy, uSeed)` with a
  PCG-style integer hash (`pcg3d` from Jarzynski & Olano, *Hash Functions for GPU Rendering*).
  Integer hashing was chosen over `fract(sin(dot(...)) * 43758.5453)` because the sine trick has
  visible structure and precision problems on mobile GPUs. `uSeed` is a new `SecureRandom` value
  each frame, additionally salted with `uTime`.
* **Modes** (`uMode`):
  * `MODE_WHITE` — independent uniform value per colour channel per pixel; constant alpha.
  * `MODE_HFREQ_HORIZONTAL` — the noise is a **second difference along X**,
    `n(x) = h(x-1) − 2h(x) + h(x+1)`. Its frequency response `4·sin²(ω/2)` is zero at DC and
    maximal at the horizontal Nyquist rate, so the energy sits in the highest horizontal spatial
    frequencies — the ones that correspond to the pixel clock and radiate best. The sign of `n`
    selects black or white, its magnitude drives the alpha.
  * `MODE_TEMPORAL` — horizontal-HF noise where every row picks one of four independent
    sub-patterns by a per-frame hash and every pixel receives a random sign flip, for extra
    decorrelation along the scan (time) axis. The service also requests the panel's highest
    refresh rate so more independent frames are emitted per second.
* **Output** is written with premultiplied alpha (`vec4(rgb·a, a)`) because SurfaceFlinger
  composites premultiplied surfaces; the effective blend is `a·noise + (1−a)·content`, with
  `a` driven by the amplitude slider.
* No static component: everything is a function of the fresh seed, so nothing survives
  averaging as a fixed pattern.

## Build

Requirements: JDK 17 (JDK 21 also works), Android SDK with platform 35 and build-tools 35.0.0.
The Gradle wrapper is committed and pinned (Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21).

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### Versioning & releases

Versions are [semantic versions](https://semver.org) derived from git tags `vMAJOR.MINOR.PATCH`;
nothing is hard-coded in the build files.

| State of the checkout | `versionName` | `versionCode` |
|-----------------------|---------------|---------------|
| exactly on tag `v1.2.3` | `1.2.3` | `1020300` |
| 5 commits after `v1.2.3` | `1.2.3-dev.5+<sha>` | `1020305` |
| no tag reachable | `0.0.0-dev.<commits>+<sha>` | `<commits>` |

`versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + min(commits since tag, 99)`, so it is
monotonic and Android accepts every release as an upgrade. The APK is named
`softtempest-<versionName>-<buildType>.apk`. `./gradlew printVersionName` shows the value.

**Automated releases** (`.github/workflows/release.yml`): every push to `main`

1. computes the next version from the commit messages since the last tag, following
   [Conventional Commits](https://www.conventionalcommits.org): `fix:` → patch, `feat:` → minor,
   a `!` after the type or a `BREAKING CHANGE:` footer → major, anything else → patch;
2. creates the tag `vX.Y.Z` (the first release is `v0.1.0`);
3. builds the release APK with that version baked in;
4. publishes a GitHub Release with the changelog and `softtempest-X.Y.Z-release.apk` attached.

Run the workflow manually from the Actions tab to force a `patch`, `minor` or `major` bump.
Add `[skip ci]` to a commit message to push to `main` without releasing.

**CI builds** (`.github/workflows/build.yml`): pushes to other branches and pull requests build a
debug APK and upload it as the artifact `softtempest-<versionName>-debug`.

**Signing.** Release builds are signed with a keystore supplied through repository secrets. Without
it the workflow falls back to a throw-away debug key, which means every release has a different
signature and Android refuses to update over a previous install. To fix that once:

```bash
keytool -genkeypair -v -keystore release.jks -alias softtempest \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # paste into the SIGNING_KEYSTORE_BASE64 secret
```

Then set the secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`
(`softtempest`) and `SIGNING_KEY_PASSWORD` under *Settings → Secrets and variables → Actions*.
Keep `release.jks` safe and private: anyone holding it can sign updates for installed copies.

## Usage

1. Install the APK and open the app.
2. Tap **Grant permission** and enable *Display over other apps* for Soft TEMPEST.
3. Turn on the **Noise overlay** switch (Android 13+ will also ask for notification permission,
   which the foreground service needs for its status notification).
4. Adjust **amplitude** and pick a **mode**; changes apply immediately.
5. Stop from the switch or from the notification's **Stop** action.
6. **Lock screen & full strength (optional).** Enable the *Soft TEMPEST noise overlay*
   accessibility service from the card in the app. The overlay then becomes a trusted system
   layer: it covers the lock screen, status bar and navigation bar, the 80 % opacity cap no
   longer applies, touches still pass through, and the system starts it at boot before the
   first unlock. The service requests no accessibility events and cannot read screen content
   (see `res/xml/accessibility_service_config.xml`). On Android 13+ a sideloaded APK is
   blocked by "Restricted settings" until you allow it from App info. Some banking apps refuse
   to run while any accessibility service is enabled; that is their policy, not something the
   app can influence.
7. **Restore on boot** (on by default) brings the overlay back after a reboot or app update if
   it was running. The last explicit Start/Stop is what gets restored; a process kill by the
   system does not count as a stop. Restoration happens after the first unlock, because the
   settings live in credential-encrypted storage.

## Project layout

```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts                 # also: git-tag semver, APK naming, release signing
gradle/libs.versions.toml            # pinned plugin and library versions
gradle/wrapper/                      # committed wrapper (jar + properties)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/pl/vovcia/softtempest/
    MainActivity.kt  OverlayService.kt  NoiseTextureView.kt
    NoiseRenderer.kt Shaders.kt        Settings.kt  BootReceiver.kt
app/src/main/res/                    # layout, strings, icons
.github/workflows/build.yml          # CI debug build for branches and PRs
.github/workflows/release.yml        # semver tag + release APK on every push to main
```

## License

MIT — see [`LICENSE`](LICENSE).
