# Police Radar

An Android app that listens for the radio signals police equipment gives off — in-car Wi-Fi routers, body cameras, Tasers — and **pings your phone when one gets close, telling you what it is.**

It only *listens*. It never connects to, transmits to, or interferes with anything.

## What it does

- Scans **Wi-Fi** (every 30 seconds, the fastest Android allows) and **Bluetooth Low Energy** (continuously) in the background.
- Compares every device it hears against a list of **signatures** — patterns that identify police gear.
- When one matches and is within your alert range, you get:
  - a pop-up notification, e.g. **"🚨 Likely police: Axon equipment — Close · Bluetooth"**
  - a short siren tone and vibration
  - an optional spoken alert: *"Likely police. Axon equipment, close."*
- Shows a live list of police signals in range with signal-strength bars and a rough distance (very close / close / nearby / far).
- **Show all nearby signals** lists everything around you. Tap any device to **mark it as police** — useful when you're parked next to a patrol car and want to teach the app what your local department uses.
- One patrol car carrying a router *and* body cameras pings once, not three times.

### Built-in signatures

| Signature | How it's matched | Confidence |
|---|---|---|
| Axon equipment (body cams, Tasers, Axon Signal) | Hardware address starts with `00:25:DF` | Likely police |
| Cradlepoint vehicle router | Hardware address starts with `00:30:44` | Possible police |
| Cradlepoint router, default Wi-Fi name | Wi-Fi name contains `IBR900`, `IBR1700`, … | Possible police |
| Police-named Wi-Fi | Wi-Fi name contains `police`, `sheriff`, `patrol`, `trooper` | Possible police |

Each can be switched off, and you can add your own (**☰ What it looks for → +**).

### Honest limitations

- **Matches are educated guesses.** Cradlepoint routers are also used by ambulances, fire trucks, buses and shops. Departments differ in what gear they use, and some gear is configured not to broadcast. Expect some false alarms and some misses — adding signatures for your local area improves it a lot.
- **Android only.** iPhones don't let apps scan Wi-Fi or see Bluetooth hardware addresses, so this can't work on iOS.
- **Keep the screen on.** Many phones pause Bluetooth scanning when the screen turns off, so the app keeps the screen awake while scanning. Mount your phone.
- **Range** is roughly 50–150 m for Wi-Fi and 10–50 m for Bluetooth, depending on the gear and obstacles.
- Laws on detection devices vary by place; check yours. Always drive safely and legally.

## Open it in Android Studio

1. Install **Android Studio** from <https://developer.android.com/studio> and open it.
2. On the welcome screen click **Clone Repository** (or **Get from VCS**). If a project is already open: **File → New → Project from Version Control**.
3. Paste `https://github.com/orogers4q-droid/Police-radar-.git` and click **Clone**. Click **Trust Project** if asked.
4. Wait for the bar at the bottom to finish ("Gradle sync"). The first time can take 5–10 minutes while it downloads tools.
5. Check the branch: bottom-right corner shows the branch name. If it says `main` and there's no `app` folder, click it and choose `claude/police-detection-app-oskack` → **Checkout**.

## Put it on your phone

1. On your phone, turn on **Developer options**: Settings → About phone → tap **Build number** 7 times.
2. In Settings → System → Developer options, turn on **USB debugging**.
3. Plug the phone into your computer with a USB cable and tap **Allow** on the phone.
4. In Android Studio, pick your phone in the device drop-down at the top, then press the green **▶ Run** button.
5. In the app, tap **Start scanning** and allow **Location**, **Nearby devices** and **Notifications**. Make sure Location and Bluetooth are switched on.

> Why Location? Android treats nearby Wi-Fi and Bluetooth as location data, so it only shares them with apps that have location permission. The app doesn't record or upload where you are.

## How it's built

```
app/src/main/java/com/policeradar/app/
  MainActivity.kt          Permissions, screen switching
  detect/Signatures.kt     Signature model and the built-in list
  detect/Detector.kt       Matching, distance estimate, alert de-duplication (pure Kotlin)
  scan/ScannerService.kt   Background Wi-Fi + Bluetooth scanning (foreground service)
  scan/AlertNotifier.kt    Notification, siren tone, vibration, text-to-speech
  data/SettingsStore.kt    Saved settings and custom signatures
  data/DetectionRepository.kt  Live list of devices in range
  ui/                      Jetpack Compose screens
app/src/test/              Unit tests for the detection logic
```

Run the unit tests with `./gradlew test`.
