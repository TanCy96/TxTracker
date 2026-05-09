# TxTracker — Local Setup

One-time setup before you can build and run the app.

## 1. Install JDK 17

Android Gradle Plugin 8.x requires JDK 17 (not 21+, not 11).

- Download **Eclipse Temurin 17** for Windows: https://adoptium.net/temurin/releases/?version=17
- Install with the option **"Set JAVA_HOME variable"** checked.
- Open a fresh PowerShell and verify:

  ```powershell
  java -version    # should print 17.x
  echo $env:JAVA_HOME
  ```

## 2. Install Android Studio

Android Studio bundles its own Gradle and the Android SDK manager.

- Download the latest stable **Android Studio**: https://developer.android.com/studio
- Install with default options.
- On first launch, the **Setup Wizard** will install:
  - Android SDK Platform 34 (Android 14) — our `compileSdk` and `targetSdk`
  - Android SDK Build-Tools 34.x
  - Android Emulator + a system image (only needed if you don't plan to use a physical device)

If the wizard skipped any of these, install them later via **Tools → SDK Manager**.

## 3. Open the project

1. Launch Android Studio.
2. **File → Open** → select `D:\Projects\TxTracker` → Trust the project.
3. Studio will detect there's no Gradle Wrapper yet and offer to **Sync Project with Gradle Files** — accept. It downloads the right Gradle distribution and creates `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` for you.
4. Wait for the first sync to finish (downloads dependencies — a few minutes on a fresh machine).

If Studio asks you to upgrade AGP or Kotlin, **decline** for now — the versions in `gradle/libs.versions.toml` are pinned to known-good ones.

## 4. Run on a physical Android device (recommended for this app)

`NotificationListenerService` is awkward to test on the emulator because the emulator doesn't get real GPay/bank notifications. Use your real phone:

1. On your phone: **Settings → About phone → tap "Build number" 7 times** to enable Developer Options.
2. **Settings → System → Developer options → enable "USB debugging"**.
3. Plug into the PC with a USB cable. Allow the debugging prompt on the phone.
4. In Android Studio's device dropdown (top toolbar), pick your phone, then click ▶ Run.
5. After install, the app's onboarding screen will deep-link you to **Settings → Notifications → Notification access** — grant access to **TxTracker**.

## 5. Sideloading to friends (after we have a working build)

```
Studio → Build → Generate Signed Bundle / APK → APK → release
```

Share the resulting `app-release.apk`. They install via **Settings → Apps → Install unknown apps → Files** (granted to whichever file manager they used to receive it).

## Troubleshooting

- **"Failed to find Build Tools revision 34.0.0"** → SDK Manager → install Android SDK Build-Tools 34.x.
- **"Gradle sync failed: JAVA_HOME points to JDK 21"** → Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → set to "Embedded JDK 17".
- **App installs but no transactions appear** → did you grant Notification access? Settings → Notifications → Notification access → TxTracker = ON.
