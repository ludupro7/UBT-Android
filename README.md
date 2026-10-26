# UBT Android — Android/Android TV preview

This safe Android 9+ (API 28+) preview recreates the original landscape/table workflow for phones and Android TV. It uses a fullscreen screen, the UBT Android name, a circular adaptive launcher icon, and a rectangular Android TV banner. English and Indonesian, system/light/dark appearance, D-pad-friendly controls, runtime USB Host/OTG capability detection, and USB permission handling for VID:PID `1B8E:C003` are included. Any Android 9+ host can be tried if it declares USB Host and has working OTG hardware; devices without host capability show an unsupported message. The Rust core streams the Amlogic header, v0/v1/v2 descriptors, referenced payload ranges, and full-image CRC check. APK ABIs are configured for `armeabi-v7a` and `arm64-v8a`.

## Flashing (added in 0.2.0)

The Start/Stop controls are now active. This build **does** send
ROM/DDR/U-Boot commands, erase flash/bootloader, write eMMC
partitions from an AMLIMG image, and verify each download on the
device (AMLS checksum) plus a version/get_status pass. The UI and
detection are unchanged from the preview: same landscape layout,
language picker, light/dark/system theme, fullscreen, scan loop and
USB permission flow. The flashing engine is new Java code in
`app/src/main/java/com/cue/amlogicburn/` (`UsbSession`,
`AmlTransfer`, `BurnEngine`, `BurnService`, `AmlImage`,
`AmlProtocol`, `SafSource`, `SeekableSource`); the Activity only
gains the wiring that enables Start/Stop and forwards service
progress/log/results into the existing Results panel.

How to use it on-device (Android 9+, USB Host/OTG):

1. Put the target in burn/update mode (USB ID `1B8E:C003`) and
   connect it via OTG, then press Scan and allow the USB permission
   dialog.
2. Import an AMLIMG image. It must pass the Rust structure/CRC
   check; Start stays disabled until a valid image is loaded.
3. Review the Configuration checkboxes (Erase flash + Normal/Force
   erase, Erase bootloader, Reset after success, Overwrite key)
   and press Start. A confirmation dialog repeats the options;
   pressing Start hands the device to the burn foreground service
   (the preview connection closes and the service opens its own —
   permission is already granted for this device).
4. Do not unplug until the Results panel reports success/failure.
   The USB permission dialog may reappear after a mid-burn reset —
   allow it again or the burn stalls at re-enumeration. After a
   burn, press Scan again to reconnect the preview connection.
5. Stop cancels between steps/chunks; the engine then tries
   `burn_complete 0` (power off) on a best-effort basis.

Hardware test order (first run on a non-critical target, in order):
scan → identify (the log shows chip ID + boot stage) → erase a
non-critical partition first → burn → verify. The USB protocol code
has never run against real hardware from this build.

## Important: read before flashing

The Start/Stop controls are no longer disabled, but the cautions
from the preview still apply: this build sends real flash commands.
The only target used in current flash-protocol research and image
validation is HG680P (GXL/P212, S905X family); this does not verify
partition semantics, signature trust, or board compatibility. USB
host/OTG wiring, power, and ROM behavior vary, so discovery may work
on many Android hosts but is not guaranteed on every device. Flash
protocol and target support are not universal.

## Known gaps and corrections (0.2.0, honest list)

- **Rust validator: no offset bug.** The Rust parser reads the
  descriptor offset as a plain little-endian u64 at 0x10
  (`read_u64(descriptor, 16)` — low word at 0x10, high at 0x14),
  which matches a real HG680P v2 image (true offset 0x2440). The
  bug was only in an earlier draft port (and in aml-burn.py lines
  118-119, which carry the swapped convention — the real image
  outranks that reference). The new Java engine parser
  (`AmlImage.java`) uses the correct layout, with regression tests
  for 0x2440 and for a >4 GiB offset that pins the word order.
- **CRC convention follows the Rust validator.** The stored header
  CRC equals standard CRC-32 of bytes [4..EOF] XOR 0xFFFFFFFF
  (`hasher.finalize() ^ 0xffffffff` in lib.rs). The Java engine's
  CRC gate matches it; aml-burn.py's plain comparison disagrees and
  must not be used as the authority here.
- **Original tool reference.** The v2.2.0 installer sent for this
  work is an Inno Setup 5.5 package whose real tool binary reports
  version 2.1.9.0 (company Amlogic, Inc.). Its command vocabulary
  was confirmed from its strings and a Ghidra pass: `download`,
  `download get_status`, `download key `, `disk_initial`,
  `burn_complete`, `save_setting`, `key init`, `key can_write `,
  `key get_id `, `key is_burned `, `key uninit`, `get keyid`,
  `erase_bootloader`, `store erase partition data`, plus the
  `BurnCfgUI.ini` option set (EraseFlash, EraseType, EraseBootLoader,
  Reset, OverWriteKey) that the config panel already mirrors.
- **`download key` argument format: narrowed, not closed.** The
  decompile shows `download key ` built with a string field plus
  further string/int arguments (consistent with a
  `download key <name> <size>`-style construction), but the field
  semantics are not proven. The engine therefore sends the bare
  command plus the packed key blob, and the key phase stays
  fail-closed: with no key-package picker in the UI and no
  user-supplied packed key blob, checking Overwrite key fails with
  a clear message instead of guessing. Closing this needs a USB
  capture/log of a real Windows key burn, or the KeysProvider
  packing format (per-key files: MAC, USID, HDCP, PlayReady,
  secure-boot keys, ...). Key material is never fabricated.
- **`store erase partition data`: observed, unplaced.** The string
  exists in the real tool, but its exact trigger condition in the
  burn state machine was not pinned down, so it is deliberately NOT
  wired into the burn sequence (wiring an unplaced command could
  break burns). The erase flow uses `disk_initial` as documented.
- **Force erase level is an inference.** Normal erase maps to
  `disk_initial 1` (documented); Force erase all maps to
  `disk_initial 2` (force except key, per the documented level
  table). The force mapping still needs hardware confirmation.
- **Cold-ROM needs user-supplied load addresses.** The engine
  refuses cold-ROM flashing without SPL/DDR and U-Boot load
  addresses (they are device/image-specific and must come from the
  official tool's log, e.g. `Download DDR.USB buffer at 0x...`).
  The B860 package's `usb_spl.bin` ships none, so it cannot be used
  for cold-ROM boot here. The cold-ROM execution handoff itself has
  no explicit command in the references (device-side, via the
  boot-info handshake); the engine polls for the bulk channel and
  falls back to re-enumeration.
- **Password-locked burn mode: not implemented.** The real tool has
  a password path (`_Aml_Libusb_Password`, `PASSWORD.USB` file
  reference, `NeedPassword` flag), but its exact control-transfer
  format was not established from the installer payload, so no
  password flow is wired. A locked device will fail at the mode
  handshake with a readable error.
- **Verification is per-download checksums + version/get_status.**
  No block-level flash read-back compare command was found in the
  v2.x references, so none is claimed.
- **No Android bus-reset API.** Mid-sequence reset is implemented
  as close + re-enumeration + reopen + poll, which is the only
  available mechanism on Android.
- **Loose-file (B860-style) flashing: deferred.** It would need a
  folder picker the prototype UI does not have, so it was left out
  rather than redesigning any UI. The main flow is AMLIMG, like the
  original tool.
- **Self-update: deliberately not ported.** The Windows tool
  updates over plaintext FTP (`ftp-china.amlogic.com`) with no
  signature; that mechanism must not be copied to Android.
- **Not hardware-tested.** Nothing in this build has run against a
  real device. The full Gradle build was also not run in the
  sandbox where this was prepared (JVM-TCP block); build with the
  project's own setup, which already builds the preview
  (`./gradlew assembleDebug` after the Rust build step above).

The only target used in current flash-protocol research and image validation is HG680P (GXL/P212, S905X family); this does not mean the APK can flash it. The supplied image passed the version-2 header, 16-descriptor table, payload-range, and full-image CRC checks, and the parser lists five partition entries. This does not verify partition semantics, signature trust, or board compatibility. USB host/OTG wiring, power, and ROM behavior vary, so discovery may work on many Android hosts but is not guaranteed on every device. Flash protocol and target support are not universal.

## Build prerequisites

- Android SDK Platform 35 and Android Build Tools 35.0.0
- Android NDK (set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`)
- JDK 17 or newer, Gradle 8.9 or newer, Rust, `cargo-ndk`, and Rust targets `armv7-linux-androideabi` and `aarch64-linux-android`

Build Rust libraries and then the APK:

```sh
cd /workspace/amlogic-usb-burn-android
cargo ndk -t armeabi-v7a -t arm64-v8a -o app/src/main/jniLibs build --release
./gradlew assembleDebug
```

No USB device is flashed by building or running the preview.
