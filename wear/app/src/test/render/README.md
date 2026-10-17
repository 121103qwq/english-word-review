# MainActivity render regression

`MainActivityRenderTest` runs as a local Robolectric JVM test with native Android graphics. It uses only synthetic word packages and isolated app data; it does not open private bootstrap content, read a token value, contact GitHub, install an APK, or start an emulator.

Run from `wear/` in PowerShell:

```powershell
$env:GRADLE_USER_HOME = 'D:\Codex\wear-words\cache\gradle'
$env:WORDWATCH_SEED_FILE = $null
$env:WORDWATCH_KEYSTORE = $null
$env:WORDWATCH_SIGN_PASS = $null
.\gradlew.bat :app:testDebugUnitTest --tests cn.wordwatch.app.render.MainActivityRenderTest
```

Generated circular PNGs are written to `app/build/reports/wordwatch-render/`. The suite measures and draws the Android view hierarchy at 192dp and 227dp round-watch configurations, checks v1 choice and review screens plus v2 input/recall content, keyboard-free default and explicit input modes, same-day session restore and prior-day expiry, update/settings/list states, five-word personal-limit steps and its optional editor, long text wrapping, tap-target height, empty and future packages, and scroll access to bottom actions. Home actions at normal and large font sizes, and the default input-question flip action, must fit entirely inside the circle without scrolling. Fixtures include synthetic optional fields and six long `notes.sections` blocks. Robolectric draws the Activity View tree; the system IME is a separate surface and is not included. The PNGs are useful rendering artifacts; Robolectric does not replace final on-watch acceptance.

Robolectric's optional flat runtime cache is `app/build/robolectric-runtime/`. The test configuration uses it only when the current SDK 35 JAR is present; a clean checkout leaves the property unset so Robolectric can fetch its runtime from Maven Central. Test temporary files stay under `app/build/tmp/test-runtime/`. The render tests shadow only `AtomicFile.rename` so file replacement follows Android's POSIX semantics on Windows; production persistence code is unchanged. No emulator or device is needed.
