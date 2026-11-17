# Police Radar

An Android app that listens for the radio signals emergency-service equipment gives off — in-car Wi-Fi routers, body cameras, Tasers — and **pings your phone when police 🚓, fire 🚒 or an ambulance 🚑 gets close, telling you which.** It also spots **Flock Safety license-plate cameras 📷**. Everyday Wi-Fi and Bluetooth devices are ignored.

It only *listens*. It never connects to, transmits to, or interferes with anything.

## What it does

- Scans **Wi-Fi** (every 30 seconds, the fastest Android allows) and **Bluetooth Low Energy** (continuously) in the background.
- Compares every device it hears against **signatures** — patterns that identify emergency-service gear — and ignores everything else.
- When one matches and is within your alert range, you get:
  - a pop-up notification, e.g. **"🚓 Police nearby — Close · Axon body camera / Taser"** or **"🚑 Possible ambulance nearby"**
  - a short siren tone and vibration
  - an optional spoken alert: *"Police nearby, close."*
- Choose which services alert you (police, fire, ambulance, unknown emergency vehicle) in Settings.
- One vehicle carrying a router *and* body cameras pings once, not three times.
- **Learning mode** (Settings) lists every nearby device so you can tap one and teach the app it belongs to police, fire or an ambulance — useful when parked next to one.

### Built-in signatures

| Signature | Service | How it's matched | Confidence |
|---|---|---|---|
| Axon body camera / Taser | 🚓 Police | Hardware address starts with `00:25:DF` | Likely |
| Police vehicle Wi-Fi | 🚓 Police | Wi-Fi name has a word like `police`, `sheriff`, `patrol`, `trooper`, `deputy`, `K9` | Possible |
| Fire apparatus Wi-Fi | 🚒 Fire | Wi-Fi name has a word like `engine`, `ladder`, `battalion`, `VFD`, `fire dept` (ignores Fire TV / Firestick / Kindle Fire) | Possible |
| Ambulance Wi-Fi | 🚑 Ambulance | Wi-Fi name has a word like `medic`, `ambulance`, `EMS`, `paramedic` | Possible |
| Flock camera Wi-Fi | 📷 Flock camera | Wi-Fi name has `Flock` (e.g. `Flock-A1B2C3`), `FS Ext Battery` or `Pigvision` | Likely |
| Flock camera Bluetooth | 📷 Flock camera | Bluetooth name has `Flock`, `FS Ext Battery`, `Pigvision` or `Penguin` | Possible |
| Flock battery pack | 📷 Flock camera | Bluetooth maker ID `0x09C8` | Possible |
| Cradlepoint vehicle router | 🚨 Emergency vehicle | Hardware address starts with `00:30:44` | Possible |
| Cradlepoint router, default name | 🚨 Emergency vehicle | Wi-Fi name contains `IBR900`, `IBR1700`, … | Possible |

Name words must stand on their own: `ENGINE 12` or `ENGINE12` match, `Enginetics` doesn't. Each signature can be switched off, and you can add your own (**☰ What it looks for → +**).

### Honest limitations

- **Matches are educated guesses.** Cradlepoint routers are shared by police, fire and EMS (and some buses and businesses), so they show as "Emergency vehicle". Departments name their Wi-Fi differently, and some gear doesn't broadcast at all. Expect some misses and false alarms — teaching the app your local departments' devices helps a lot.
- **Flock detection is based on community research** (projects like "flock-you"). Flock changes its hardware over time, and newer cameras may not broadcast these names. Hardware-address prefixes are deliberately not used for Flock: the published lists include chip makers found in everyday gadgets and cause constant false alarms.
- **Android only.** iPhones don't let apps scan Wi-Fi or see Bluetooth hardware addresses, so this can't work on iOS.
- **Keep the screen on.** Many phones pause Bluetooth scanning when the screen turns off, so the app keeps the screen awake while scanning. Mount your phone.
- **Range** is roughly 50–150 m for Wi-Fi and 10–50 m for Bluetooth, depending on the gear and obstacles.
- Laws on detection devices vary by place; check yours. Always drive safely and legally, and pull over for emergency vehicles.

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
