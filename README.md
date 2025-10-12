# 🔧 auto-adb

**Automatically enable wireless ADB debugging and switch to port 5555 on every boot - completely autonomous, no root required!**

Perfect for Chromecast with Google TV, Android TV boxes, and any Android device where you want persistent wireless ADB access without manual intervention.

## ✨ Features

- 🚀 **Fully Autonomous**: Automatically enables wireless debugging and switches to port 5555 on every boot
- 📱 **No Root Required**: Works on non-rooted devices with a one-time ADB permission grant
- 🌐 **Web UI**: Clean web interface at `http://device-ip:8080` for status and manual control
- 🔄 **Smart Retry**: Implements retry logic with WiFi connection monitoring for reliability

## 🎬 How It Works

1. **One-Time Pairing**: App pairs with the local ADB daemon, storing authentication keys
2. **Boot Detection**: On device boot, service starts automatically (with WiFi monitoring and 60s stabilization delay)
3. **Enable Wireless Debugging**: Enables wireless debugging via `Settings.Global`
4. **Port Discovery**: Discovers the random ADB port using mDNS or port scanning
5. **Self-Connection**: Connects to local ADB daemon using stored authentication keys
6. **Port Switch**: Sends `tcpip:5555` command to ADB daemon to switch to fixed port
7. **Done!**: ADB is now available on port 5555 for external connections

## 📋 Requirements

- Android 13+ (tested on Chromecast with Google TV)
- ADB access for one-time setup
- WiFi connection

## 🚀 Quick Start

### 1. Installation

Download and install the APK from [apk](./apk), or build from source:

```bash
git clone https://github.com/yourusername/wireless-adb-switch.git
cd wireless-adb-switch
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Grant Permission

Connect your device via USB and grant the required permission:

```bash
adb shell pm grant com.example.adbautoenable android.permission.WRITE_SECURE_SETTINGS
```

### 3. Initial Pairing

1. Open the app or navigate to `http://your-device-ip:8080` in a browser
2. On your Android device:
   - Go to **Settings → Developer Options → Wireless Debugging**
   - Tap **"Pair device with pairing code"**
3. Enter the **pairing code** and **pairing port** into the web interface
4. Click **"Pair Device"**
5. Click **"Switch to Port 5555 Now"** to verify it works

### 4. Test Auto-Boot

Reboot your device:

```bash
adb reboot
```

Wait about 90 seconds (60s boot delay + 30s for configuration), then connect:

```bash
adb connect your-device-ip:5555
```

## 🌐 Web Interface

Access the web interface at `http://device-ip:8080` to:

- ✅ View system status (permissions, pairing, port availability)
- 🔗 Perform one-time pairing
- 🔄 Manually trigger port switch
- 🧪 Test the boot configuration sequence
- 📊 Monitor last boot status and discovered ports

## 🛠️ Configuration

### Adjust Boot Delay

Edit `AdbConfigService.java`:

```java
private static final int INITIAL_BOOT_DELAY_SECONDS = 60; // Adjust as needed
```

### Adjust Retry Settings

```java
private static final int MAX_RETRY_ATTEMPTS = 3;
private static final int RETRY_DELAY_SECONDS = 15;
```

### Change Web Server Port

Edit `MainActivity.java`:

```java
private static final int WEB_SERVER_PORT = 8080; // Change port
```

## 🐛 Troubleshooting

### Boot Configuration Not Running

Check logs:
```bash
adb logcat -s ADBAutoEnable
```

Look for:
- `"Boot event detected, starting ADB configuration service..."`
- `"Waiting for WiFi connection..."`
- `"Successfully configured ADB on port 5555!"`

### Port Switch Fails

1. Verify permission is granted:
   ```bash
   adb shell pm grant com.example.adbautoenable android.permission.WRITE_SECURE_SETTINGS
   ```

2. Check pairing status in web UI - must show "✓ Paired"

3. Try manual trigger via web UI button

### WiFi Takes Too Long to Connect

Increase the WiFi wait timeout in `AdbConfigService.java`:

```java
int maxWaitSeconds = 120; // Increase to 180 or more
```

### Service Crashes on Boot

Check for foreground service permission errors:
```bash
adb logcat | grep -i "foreground"
```

Ensure `AndroidManifest.xml` has:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

## 📁 Project Structure

```
wireless-adb-switch/
├── app/src/main/java/com/example/adbautoenable/
│   ├── MainActivity.java          # Main activity with web server
│   ├── AdbConfigService.java      # Foreground service for boot config
│   ├── BootReceiver.java          # Boot broadcast receiver
│   ├── AdbHelper.java             # ADB protocol implementation
│   └── WebServer.java             # NanoHTTPD web interface
├── app/src/main/AndroidManifest.xml
└── README.md
```

## 🔧 Technical Details

### ADB Protocol Implementation

The app implements the ADB wire protocol:
- **CONNECT** message with `host::features` service
- **AUTH** signature/token exchange for authentication
- **OPEN** service channel to ADB daemon
- **WRITE** commands like `tcpip:5555`

Authentication keys are stored in `/data/data/com.example.adbautoenable/files/adb_keys/`.

### Boot Process Flow

```
LOCKED_BOOT_COMPLETED → BootReceiver → AdbConfigService (Foreground)
  ↓
Wait for WiFi (up to 2 min)
  ↓
Wait for system stabilization (60s)
  ↓
Enable wireless debugging
  ↓
Wait 10s for ADB daemon
  ↓
Discover port (mDNS → fallback to scan)
  ↓
Connect to local ADB
  ↓
Send tcpip:5555 command
  ↓
Success! (with 3 retry attempts if needed)
```

### Why This Approach?

**Alternatives:**
- ❌ **Magisk modules**: Require root
- ❌ **Tasker + AutoInput**: Require UI automation, fragile
- ❌ **Shell scripts**: Require root or ADB always enabled
- ✅ **This app**: Direct ADB protocol, no root, fully autonomous

## 🤝 Contributing

Contributions welcome! Areas for improvement:

- [ ] Add support for custom port (not just 5555)
- [ ] Implement persistent notification with quick actions
- [ ] Add option to disable auto-boot behavior
- [ ] Support for multiple saved device pairings
- [ ] Improve mDNS discovery reliability
- [ ] Add widget for quick status check

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Built for Chromecast with Google TV users who need persistent ADB access
- Inspired by various Tasker projects and Magisk modules
- Uses [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) for embedded web server

## ⚠️ Disclaimer

This app modifies system settings and enables wireless ADB debugging. Only use on devices you own and control. Enabling wireless ADB debugging can pose security risks if not properly secured on your network.

