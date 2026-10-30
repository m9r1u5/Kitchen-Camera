# Kitchen Camera

Ad-free Android viewer for a TP-Link Tapo C200 (and other wired Tapo cameras) on your home Wi-Fi.

- Live view over RTSP (HD / SD toggle, sound on/off)
- Pan and tilt with hold-to-move arrows (ONVIF)
- Snapshots saved to Pictures/Kitchen Camera
- Full-screen landscape mode
- Settings saved on the phone only

## Install
Download **KitchenCamera.apk** from the [latest release](https://github.com/m9r1u5/kitchen-camera/releases/latest) and open it on your phone.

## First run
In the Tapo app: camera → Settings → Advanced Settings → **Camera Account**. Enter that username and password plus the camera's IP address in Kitchen Camera's settings.

Works on the same Wi-Fi as the camera. For viewing away from home, use a VPN such as Tailscale.

Every push to `main` builds a new APK with GitHub Actions and publishes it as a release.
