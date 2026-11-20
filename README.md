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
| Flock camera hardware address | 📷 Flock camera | Address starts with `B4:1E:52` (Flock Safety's own registered block) | Likely |
| Flock camera Wi-Fi | 📷 Flock camera | Wi-Fi name has `Flock`, `Flock Safety`, `FS Ext Battery` or `Pigvision` | Likely |
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

---

# BLE Radar (second app, `bleradar/`)

A separate app in the same project that hunts for **Bluetooth trackers 🏷️ and hidden cameras 📷**, and helps you **track them down** with beeps and vibration that speed up as you get closer. It installs next to Police Radar as its own app.

## What it does

- Scans **Bluetooth Low Energy** continuously and lists everything nearby on a live radar, most suspicious first.
- **Trackers:** AirTags and other Apple Find My tags (flagged red when away from their owner, which is what Apple's own anti-stalking alerts look for), Tile, Samsung SmartTag, Chipolo and Google Find My Device tags.
- **"Travelling with you" warning:** a tracker that stays in range for 10+ minutes is flagged.
- **Possible hidden cameras:** Bluetooth devices named like cameras (`IPCAM`, `V380`, `LookCam`, `HDWiFiCam`, `spy cam`, …), plus the **Wi-Fi hotspots** cheap spy cameras create while waiting to be set up (`V380-…`, `GW_AP_…`, `HDWiFiCam…`). Other Wi-Fi networks are ignored.
- **Chime and buzz** when a new tracker or possible camera first shows up.
- **Says what each device is**, even when it has no name: e.g. "AirPods Pro", "iPhone, iPad, Mac or Apple Watch", "Keyboard, mouse or remote", "Samsung phone, watch, earbuds or TV", "Car", "Smart light". It works this out from the maker code, Apple's broadcast type (which even gives the AirPods/Beats model), standard service IDs and words in the name.
- **Pop-ups:** each newly found device slides in at the top of the screen with its name, what it is and how close it is. When lots appear at once it shows the most interesting one "and N more". Choose every device, only trackers and cameras, or off in Settings.
- **Tap a dot on the radar** (or a device in the list) to pop up everything known about it. Trackers, cameras and the closest few devices are labelled on the radar.
- In **Fast mode**, the app also asks the connected device for its own name, maker and model, and pops up what it says.
- **Track it down:** tap any device to open a big closeness dial:
  - beeps get **faster and higher-pitched** as you get closer, like a Geiger counter, down to a rapid buzz when you're on top of it
  - **vibration pulses** speed up and get stronger in step with the beeps (works with the sound off too)
  - **▲ Getting closer / ▼ Moving away** indicator, rough distance in metres, and a mark for the strongest reading so far
  - beeps use media volume, so they still work with the ringer on silent
  - shows how many **readings per second** it's getting
  - **⚡ Fast mode** (optional): connects to the device and reads its signal about 6 times a second, instead of waiting for its broadcasts (many trackers only broadcast every 1–2 seconds). Works only with devices that accept connections; others stay on broadcasts. Needs the Nearby devices permission.
  - tracking a **camera Wi-Fi hotspot** asks for a Wi-Fi scan every 5 seconds. Android normally limits apps to 4 scans per 2 minutes; turn off **Developer options → Wi-Fi scan throttling** to get the faster updates.
- **Lens finder 🔦:** many hidden cameras don't use Bluetooth at all. This turns on the flashlight and darkens the camera view so a lens shows up as a sharp sparkle. **Infrared check** uses the front camera, which can usually see a night-vision camera's invisible IR LEDs as a purple glow.

### Honest limitations

- Bluetooth can tell **how close**, not **which direction**. To get a direction, hold the phone against your chest and turn slowly: your body blocks the signal, so it's strongest when you face the device. Where a dot sits around the radar circle means nothing; how far it is from the centre does.
- Signal strength jumps around, so the app smooths it; move slowly and pause between steps. Smoothing adapts to how often the device is heard, so slow broadcasters don't lag behind.
- How often a device broadcasts is set by the device, not the phone. Fast mode gets around that only for devices that accept connections; while connected, some devices stop broadcasting and their owner's app may show them as connected.
- Trackers change their Bluetooth address now and then (AirTags away from their owner about once a day). If one disappears while you're tracking it, go back and pick the newest tracker.
- Many hidden cameras use only Wi-Fi once set up, or record to a memory card with no radio at all. A clean scan doesn't prove a room is clean: also use the Lens finder and check smoke alarms, clocks, chargers and anything pointing at the bed or shower.
- Camera detection by name is a guess and will have false alarms (a dash cam or doorbell counts as a camera).
- It scans while the app is open (the screen is kept on). Android only.

## Put BLE Radar on your phone

Same as Police Radar above, but in Android Studio pick **bleradar** in the run-configuration drop-down (next to the device picker) before pressing **▶ Run**. Allow **Location** and **Nearby devices**; the Lens finder asks for the **Camera** when you first open it.

```
bleradar/src/main/java/com/bleradar/app/
  MainActivity.kt          Permissions, screen switching
  Prefs.kt                 Saved settings (sound, vibration, alerts, show all)
  detect/Classifier.kt     What a device is: tracker, camera or other (pure Kotlin)
  detect/Signal.kt         Smoothing, closer/further trend, distance, beep rate and pitch
  scan/Scanner.kt          Bluetooth + camera-hotspot Wi-Fi scanning
  scan/Feedback.kt         Geiger-style beeps and vibration, new-find chime
  ui/                      Radar + list, tracking dial, lens finder
bleradar/src/test/         Unit tests
```

Run its tests with `./gradlew :bleradar:test`.
