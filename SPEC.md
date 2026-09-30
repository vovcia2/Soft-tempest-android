# Specyfikacja dla Claude Code — Android soft-TEMPEST display masking overlay

## Cel projektu

Zbuduj **defensywną** aplikację na Androida, która renderuje pełnoekranową warstwę
szumu pikseli nad treścią ekranu. Celem jest podniesienie poziomu szumu w emisji
elektromagnetycznej toru wyświetlania (soft TEMPEST wg Kuhn & Anderson, 1998), czyli
utrudnienie zdalnej rekonstrukcji obrazu z promieniowania ujawniającego. To jest
narzędzie ochronne dla własnego urządzenia użytkownika — nie przechwytuje, nie nadaje,
nie atakuje niczego.

## Model zagrożenia i świadome ograniczenia (WAŻNE — wpływa na projekt)

Zaimplementuj to z pełną świadomością poniższych granic i **umieść je w README**:

1. **`FLAG_SECURE`** — okna oznaczone jako bezpieczne (bankowość, DRM, ekrany haseł)
   system renderuje tak, że overlay się nad nimi nie pojawi. Tych ekranów aplikacja
   NIE osłoni bez roota/custom ROM. To fundamentalne ograniczenie — nie próbuj go
   obchodzić.
2. **Uśrednianie N ramek** — statyczny szum spada jako √N przy uśrednianiu przez
   atakującego. Dlatego szum MUSI być losowany niezależnie w każdej klatce
   (time-seeded PRNG w shaderze). Naiwny statyczny overlay = złudzenie ochrony.
3. **Bez pomiaru nie ma dowodu skuteczności** — to jest prototyp/uzupełnienie
   ekranowania sprzętowego, nie zamiennik. README ma to jasno mówić.
4. **Bateria** — ciągłe renderowanie pełnoekranowe jest kosztowne; rendering przez GPU
   (shader), nie Canvas.

## Stack techniczny

- Język: **Kotlin**
- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- Rendering: **`GLSurfaceView` + OpenGL ES 3.0**, fragment shader generujący szum
- Trwałość: **foreground service** hostujący okno overlay (wymagana notyfikacja)
- Build: **Gradle (Kotlin DSL)**, AGP + Gradle w wersjach aktualnie stabilnych —
  Claude Code ma dobrać kompatybilną parę i przypiąć ją w `gradle-wrapper.properties`
  oraz w workflow; jeśli build się wywali na wersjach, rozwiąż konflikt AGP/Gradle/JDK
  zanim przejdziesz dalej.
- JDK do builda: **17**

## Funkcjonalność

1. **Uprawnienie `SYSTEM_ALERT_WINDOW`** — ekran startowy sprawdza `Settings.canDrawOverlays()`
   i kieruje użytkownika do `ACTION_MANAGE_OVERLAY_PERMISSION`, jeśli brak.
2. **Foreground service** (`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`
   lub odpowiedni typ) trzymający okno overlay przy życiu; notyfikacja z akcją
   Start/Stop.
3. **Okno overlay** przez `WindowManager`:
   - typ `TYPE_APPLICATION_OVERLAY`
   - flagi `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS`
     (click-through, obejmuje cały ekran)
   - format `PixelFormat.TRANSLUCENT`
   - rozmiar `MATCH_PARENT` × `MATCH_PARENT`
4. **Renderer** — `GLSurfaceView` z `setEGLContextClientVersion(3)`,
   `setZOrderOnTop(true)`, przezroczyste tło (`setEGLConfigChooser(8,8,8,8,16,0)` +
   `GLES.glClearColor(0,0,0,0)`), `renderMode = RENDERMODE_CONTINUOUSLY`.
5. **UI sterowania** (Activity z Compose lub prostymi widokami):
   - przełącznik Start/Stop overlaya
   - suwak **amplitudy/opacity** szumu (0–1), realny kompromis czytelność↔ochrona
   - wybór **trybu szumu** (patrz niżej)
   - krótki disclaimer o ograniczeniach
6. Zapis ustawień w `SharedPreferences`/DataStore.

## Projekt shadera szumu

Fragment shader (GLES 3.0), uniformy: `uTime` (float), `uAmplitude` (float),
`uMode` (int), `uResolution` (vec2).

- **Per-pixel, per-frame PRNG** — hash z `gl_FragCoord.xy` + `uTime`, żeby szum zmieniał
  się co klatkę (kluczowe wobec uśredniania √N). Użyj hasha typu
  `fract(sin(dot(...)) * 43758.5453)` albo lepszego integer-hash (PCG-style) dla
  mniejszej korelacji.
- **Tryby**:
  - `MODE_WHITE` — szum biały, równomierny alpha.
  - `MODE_HFREQ_HORIZONTAL` — energia skoncentrowana w wysokich częstotliwościach
    **poziomych** (to one najlepiej promieniują, bo odpowiadają zegarowi pikseli).
    Realizacja: moduluj szum funkcją o wysokiej częstotliwości wzdłuż osi X, albo
    różnicuj sąsiednie piksele w poziomie.
  - `MODE_TEMPORAL` — dodatkowo szybka zmienność czasowa (kilka pod-wzorów na klatkę,
    jeśli odświeżanie panelu na to pozwala).
- Wyjście: `fragColor = vec4(noiseRGB, uAmplitude * noiseAlpha)` — blending alfa nad
  treścią. Amplituda z suwaka steruje widocznością.
- Zadbaj o brak banding/stałych wzorów, które nie znikają przy uśrednianiu.

## Struktura plików (proponowana)

```
/ (repo root)
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew  /  gradlew.bat
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/<pkg>/
│       │   ├── MainActivity.kt          # UI, uprawnienia, sterowanie
│       │   ├── OverlayService.kt        # foreground service + okno WindowManager
│       │   ├── NoiseGLSurfaceView.kt    # GLSurfaceView
│       │   ├── NoiseRenderer.kt         # Renderer, uniformy, pętla klatek
│       │   ├── Shaders.kt               # kod GLSL (vertex + fragment)
│       │   └── Settings.kt              # DataStore/SharedPreferences
│       └── res/                          # layouty/ikony/strings
├── .github/workflows/build.yml
├── README.md                             # w tym sekcja ograniczeń z tego SPEC
└── SPEC.md                               # ten plik
```

## Manifest — kluczowe wpisy

- `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>`
- `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
- serwis z `android:foregroundServiceType="specialUse"` + deklaracja `<property>`
  z uzasadnieniem użycia (wymagane od Androida 14 dla `specialUse`).
- `MainActivity` jako launcher.

## GitHub Actions — build APK

Utwórz `.github/workflows/build.yml`. Build debugowego APK (debug keystore generuje
się automatycznie dla `assembleDebug`, więc nie trzeba sekretów do pierwszego działania).
Artefakt do pobrania z zakładki Actions.

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        run: chmod +x ./gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
```

Uwagi dla Claude Code:
- Wygeneruj wrapper (`gradle wrapper`) i **commituj** `gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties` — bez tego workflow
  nie zbuduje.
- Jeśli chcesz podpisany release APK, dodaj osobny job czytający keystore z
  `secrets` (base64) — ale to opcjonalne; zacznij od `assembleDebug`.
- Dopilnuj, by `compileSdk`/AGP nie wymagały wyższego JDK niż 17; w razie potrzeby
  podbij `setup-java` do 21 i zsynchronizuj z AGP.
- Dodaj cache Gradle (`actions/setup-java` z `cache: gradle`) dla szybszych buildów.

## Kolejność pracy dla Claude Code

1. Zainicjuj projekt Gradle (Kotlin DSL) + wrapper, ustaw wersje, zrób pusty build
   `assembleDebug`, żeby workflow przeszedł na gołym szkielecie.
2. Dodaj `MainActivity` + flow uprawnienia overlay.
3. Dodaj `OverlayService` + okno `WindowManager` z pustym (przezroczystym) widokiem.
4. Dodaj `GLSurfaceView` + renderer + shader biały szum per-frame.
5. Dodaj tryby szumu (HF poziomy, temporal) i suwak amplitudy.
6. Uzupełnij README sekcją ograniczeń z tego SPEC.
7. Na każdym etapie utrzymuj zielony build w Actions.

## Definicja „gotowe" (MVP)

- APK buduje się w GitHub Actions i jest do pobrania jako artefakt.
- Po nadaniu uprawnienia i wciśnięciu Start pojawia się click-through szum na całym
  ekranie (poza `FLAG_SECURE`), znikający po Stop.
- Szum zmienia się co klatkę; suwak amplitudy działa; tryby przełączają charakter szumu.
- README zawiera uczciwy opis ograniczeń (FLAG_SECURE, √N, konieczność pomiaru,
  status „uzupełnienie ekranowania, nie zamiennik").
