# CANary

<picture>
  <img alt="canary picture" src="canary.png" width="200">
</picture>

A zero-configuration FRC robot program for electrical team QA. Deploy it, open RioLog, and read the report — or drop the single `CANBusInspector.java` file into your own robot project and call it whenever you need.

---

## What it does

Passively listens to all CAN bus traffic and reports every device it sees:

| Check | How |
|---|---|
| **Which devices are present** | Listens for status frames from each known manufacturer/type/ID combination |
| **What each device is** | Decodes manufacturer + device type from the CAN arbitration ID → human-readable name |
| **Duplicate IDs** | Flags any case where two devices of the same type share a CAN ID |
| **Bus health** | Reports utilization %, RX/TX error counts |
| **NetworkTables output** | Optionally publishes all results under the `CANary` table for dashboard use |

---

## How to run (standalone)

1. Deploy to the roboRIO via VS Code (`Ctrl+Shift+P → WPILib: Deploy Robot Code`)
2. Open **Driver Station → View → RioLog**
3. The report prints automatically every 10 seconds
4. **The robot does not need to be enabled**

---

## Using in your own robot project

`CANBusInspector.java` is fully self-contained — it depends only on standard WPILib classes. You can copy the single file into any FRC Java project and call it directly.

### Quick start

1. Copy `src/main/java/frc/robot/CANBusInspector.java` into your project's `frc/robot/` package (or update the `package` declaration to match your own package).
2. Create an instance and call `runInspection()`:

```java
// In your Robot class or a command
CANBusInspector inspector = new CANBusInspector();

// Print report to RioLog only
inspector.runInspection();

// Print report AND push results to NetworkTables (under "CANary" table)
inspector.runInspection(true);
```

### Periodic inspection example

Run the inspection on a timer (e.g. every 10 seconds) without blocking your main loop more than once per interval:

```java
public class Robot extends TimedRobot {
    private final CANBusInspector inspector = new CANBusInspector();
    private static final double INSPECTION_INTERVAL_SECONDS = 10.0;
    private double lastInspectionTimestamp = 0.0;

    @Override
    public void robotPeriodic() {
        double now = Timer.getFPGATimestamp();
        if (now - lastInspectionTimestamp >= INSPECTION_INTERVAL_SECONDS) {
            lastInspectionTimestamp = now;

            // Print to RioLog only
            inspector.runInspection();

            // To also push results to NetworkTables, use the overload instead:
            // inspector.runInspection(true);
        }
    }
}
```

### One-shot inspection example

Run the inspection once after a short boot delay to let all devices enumerate:

```java
public class Robot extends TimedRobot {
    private final CANBusInspector inspector = new CANBusInspector();
    private boolean hasRun = false;

    @Override
    public void robotPeriodic() {
        if (!hasRun && Timer.getFPGATimestamp() > 5.0) {
            hasRun = true;
            inspector.runInspection();
        }
    }
}
```

### Using `scanBus()` directly

If you need the raw device list for your own logic (e.g. pre-flight checks, dashboard widgets):

```java
CANBusInspector inspector = new CANBusInspector();
List<CANBusInspector.DetectedDevice> devices = inspector.scanBus();

for (var device : devices) {
    System.out.println("Found: " + device.description() + " at ID " + device.deviceId());
}
```

---

## NetworkTables output

When you call `inspector.runInspection(true)`, results are published under the `CANary` NetworkTables table. You can view them in Shuffleboard, Glass, AdvantageScope, or any NetworkTables client.

| Key | Type | Description |
|---|---|---|
| `CANary/BusHealth/Utilization` | double | Bus utilization (0–100%) |
| `CANary/BusHealth/ReceiveErrors` | int | Receive error count |
| `CANary/BusHealth/TransmitErrors` | int | Transmit error count |
| `CANary/BusHealth/TxFullCount` | int | TX-full count |
| `CANary/BusHealth/Healthy` | boolean | `true` when both error counters are zero |
| `CANary/Devices` | String[] | One entry per device: `"ID \| Name \| Status"` |
| `CANary/Summary/DeviceCount` | int | Total devices detected |
| `CANary/Summary/DuplicateCount` | int | Number of duplicate-ID groups |
| `CANary/Summary/Result` | String | `"PASS"`, `"FAIL"`, or `"NO DEVICES"` |

> **Custom table path:** Use the constructor overload `new CANBusInspector(networkTable)` to publish under a different NetworkTables path.

---

## Example console output

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

## API reference

| Method | Description |
|---|---|
| `CANBusInspector()` | Creates an inspector with results published to the default `CANary` NT table |
| `CANBusInspector(NetworkTable)` | Creates an inspector that publishes to a custom NetworkTables path |
| `runInspection()` | Scans the bus and prints a report to RioLog |
| `runInspection(true)` | Scans the bus, prints a report, **and** pushes results to NetworkTables |
| `scanBus()` | Returns a `List<DetectedDevice>` without printing anything — use for custom logic |
| `DetectedDevice.description()` | Human-readable device name (e.g. `"TalonFX (Kraken X60 / Falcon 500)"`) |
| `DetectedDevice.deviceId()` | The 6-bit CAN ID (0–62) |
| `DetectedDevice.duplicateKey()` | Key for duplicate detection (`"deviceType:id"`) |

---

## Dependencies

**None beyond WPILib.** This tool uses only `edu.wpi.first.wpilibj.CAN`, `RobotController`, and `edu.wpi.first.networktables` — all part of the standard WPILib installation. No vendor libraries required.