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

## How it works

| Component | Role |
|-----------|------|
| `MainActivity` | Permission flow (`SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`), Start/Stop switch, amplitude slider, noise-mode selection, disclaimer |
| `OverlayService` | Foreground service (`specialUse`) that adds a `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_TOUCHABLE \| FLAG_NOT_FOCUSABLE \| FLAG_LAYOUT_IN_SCREEN \| FLAG_LAYOUT_NO_LIMITS`, `PixelFormat.TRANSLUCENT`, `MATCH_PARENT × MATCH_PARENT`; notification with a Stop action |
| `NoiseGLSurfaceView` | `GLSurfaceView` with an ES 3.0 context, RGBA8888 config, `setZOrderOnTop(true)`, `RENDERMODE_CONTINUOUSLY` |
| `NoiseRenderer` | Draws one full-screen triangle each frame, uploads `uTime`, `uAmplitude`, `uMode`, `uResolution` and a fresh random `uSeed` |
| `Shaders` | GLSL ES 3.00 vertex + fragment shader |
| `NoiseSettings` | `SharedPreferences` store; the service observes it, so slider/mode changes apply live |

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

### GitHub Actions

`.github/workflows/build.yml` builds the debug APK on every push to `main`, on pull requests
and on manual dispatch, and uploads it as the `app-debug-apk` artifact (Actions tab → run →
Artifacts). The debug keystore is generated automatically, so no secrets are needed.

## Usage

1. Install the APK and open the app.
2. Tap **Grant permission** and enable *Display over other apps* for Soft TEMPEST.
3. Turn on the **Noise overlay** switch (Android 13+ will also ask for notification permission,
   which the foreground service needs for its status notification).
4. Adjust **amplitude** and pick a **mode**; changes apply immediately.
5. Stop from the switch or from the notification's **Stop** action.

## Project layout

```
settings.gradle.kts / build.gradle.kts / gradle.properties
gradle/libs.versions.toml            # pinned plugin and library versions
gradle/wrapper/                      # committed wrapper (jar + properties)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/pl/vovcia/softtempest/
    MainActivity.kt  OverlayService.kt  NoiseGLSurfaceView.kt
    NoiseRenderer.kt Shaders.kt        Settings.kt
app/src/main/res/                    # layout, strings, icons
.github/workflows/build.yml
```

## License

MIT — see [`LICENSE`](LICENSE).
