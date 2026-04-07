# CAN Bus QA Tool

A zero-configuration FRC robot program for electrical team QA. Deploy it, open RioLog, and read the report. **No code changes needed, ever.**

---

## What it does

Passively listens to all CAN bus traffic and reports every device it sees:

| Check | How |
|---|---|
| **Which devices are present** | Listens for status frames from each known manufacturer/type/ID combination |
| **What each device is** | Decodes manufacturer + device type from the CAN arbitration ID → human-readable name |
| **Duplicate IDs** | Flags any case where two devices of the same type share a CAN ID |
| **Bus health** | Reports utilization %, RX/TX error counts |

---

## How to run

1. Deploy to the roboRIO via VS Code (`Ctrl+Shift+P → WPILib: Deploy Robot Code`)
2. Open **Driver Station → View → RioLog**
3. The report prints automatically ~5 seconds after boot
4. **The robot does not need to be enabled**

---

## Example output

```
==============================================================
  CAN BUS QA TOOL  —  Passive Device Scan
==============================================================
  Probing 1188 manufacturer/type/ID combinations...

--- BUS HEALTH ---
  Utilization:      9.2%
  Receive Errors:   0
  Transmit Errors:  0
  TX Full Count:    0
  Status:           ✓ HEALTHY

--- DETECTED DEVICES ---
  ID     Device                                    Status
  ------------------------------------------------------------
  0      Pigeon 2 IMU                              ✓ OK
  1      TalonFX (Kraken X60 / Falcon 500)         ✓ OK
  2      TalonFX (Kraken X60 / Falcon 500)         ✓ OK
  3      TalonFX (Kraken X60 / Falcon 500)         ✓ OK
  3      SPARK MAX / SPARK Flex                     ✗ DUPLICATE ID
  4      TalonFX (Kraken X60 / Falcon 500)         ✓ OK
  21     CANcoder                                   ✓ OK
  22     CANcoder                                   ✓ OK
  23     CANcoder                                   ✓ OK
  24     CANcoder                                   ✓ OK

--- SUMMARY ---
  Devices found:   10
  Duplicate IDs:   1

  *** RESULT: FAIL — Duplicate IDs must be resolved ***
    → Use Phoenix Tuner X or REV Hardware Client to
      reassign conflicting device IDs before handoff.

==============================================================
```

---

## Fixing issues

| Problem | Fix |
|---|---|
| **No devices detected** | Check CAN wiring (H/L reversed?), power to devices, termination resistors |
| **Duplicate ID** | Use Phoenix Tuner X (CTRE) or REV Hardware Client (REV) to reassign the ID |
| **Expected device missing** | Check that device's CAN connectors, power, and its own status LEDs |
| **Unknown device shown** | Add it to the `DEVICE_NAMES` lookup table in `CANBusInspector.java` |
| **Bus errors at idle** | Physical fault — check for shorts, missing termination, or a broken shield |

---

## How it works (for the curious)

Every FRC CAN device transmits periodic status frames. The 29-bit FRC CAN arbitration ID encodes:

```
[DeviceType: 5 bits][Manufacturer: 8 bits][APIClass: 6 bits][APIIndex: 4 bits][DeviceID: 6 bits]
```

WPILib's `CAN` class can open a receive filter for any `(manufacturer, deviceType, deviceId)` combination. This tool iterates over all known FRC manufacturer codes × device types × IDs 0–62, calls `readPacketLatest()` on each combination, and records any that have received a frame. The manufacturer + device type are then decoded into a human-readable name using a lookup table.

This is exactly the same principle used by Phoenix Tuner X and REV Hardware Client — just implemented as a deployable robot program that runs without a laptop connection.

---

## Supported devices (out of the box)

- TalonFX (Kraken X60, Falcon 500) — CTRE
- CANcoder — CTRE
- Pigeon 2 IMU — CTRE
- CANdle LED Controller — CTRE
- CTRE PDH / PCM — CTRE
- SPARK MAX / SPARK Flex — REV
- REV Power Distribution Hub / Pneumatic Hub — REV
- navX-MXP / navX2 — Kauai Labs
- Redux Canandmag Encoder — Redux Robotics
- Playing With Fusion CANandcoder — PWF
- ThriftyNova Motor Controller — The Thrifty Bot
- Jaguar Motor Controller — Luminary Micro
- AndyMark PDB — AndyMark

Any unrecognized device will still show up as `"[Manufacturer] [DeviceType]"` — you can add a specific name to the `DEVICE_NAMES` map in `CANBusInspector.java`.

---

## Dependencies

**None beyond WPILib.** This tool uses only `edu.wpi.first.wpilibj.CAN` and `RobotController`, which are part of the standard WPILib installation. No vendor libraries required.