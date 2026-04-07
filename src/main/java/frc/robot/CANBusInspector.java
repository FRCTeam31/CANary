package frc.robot;

import edu.wpi.first.hal.CANData;
import edu.wpi.first.wpilibj.CAN;
import edu.wpi.first.wpilibj.RobotController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CANBusInspector
 *
 * Zero-configuration CAN bus QA tool. No setup required — the electrical team
 * just deploys and reads the output in Driver Station RioLog.
 *
 * HOW IT WORKS:
 *   Every FRC CAN device continuously broadcasts status frames on the bus.
 *   The 29-bit FRC CAN arbitration ID encodes the manufacturer, device type,
 *   and device ID directly in the message.
 *
 *   WPILib's CAN class can open a receiver filtered to a specific
 *   (manufacturer, deviceType, deviceId) tuple. By iterating across all known
 *   FRC manufacturer codes, device types, and IDs 0–62, we passively detect
 *   every device without knowing anything about the robot upfront and without
 *   transmitting anything to the devices.
 *
 *   The manufacturer + deviceType lookup table then gives us a human-readable
 *   device name (e.g. "SPARK MAX / SPARK Flex", "TalonFX", "navX2 IMU").
 *
 * WHAT IT REPORTS:
 *   - Every device on the bus: CAN ID + model name
 *   - Any duplicate CAN IDs (same device type, same ID — always an error)
 *   - Bus health (utilization %, error counters)
 *   - Overall PASS / FAIL
 *
 * HOW TO RUN:
 *   1. Deploy to roboRIO (robot does NOT need to be enabled)
 *   2. Open Driver Station → View → RioLog
 *   3. Report prints ~5 seconds after boot
 */
public class CANBusInspector {

    // -----------------------------------------------------------------------
    // FRC CAN Manufacturer codes (8-bit)
    // Source: https://docs.wpilib.org/en/stable/docs/software/can-devices/can-addressing.html
    // -----------------------------------------------------------------------
    private enum Manufacturer {
        NI              (0x01, "NI"),
        LUMINARY_MICRO  (0x02, "Luminary Micro"),
        DEKA            (0x03, "DEKA"),
        CTRE            (0x04, "CTR Electronics"),
        REV             (0x05, "REV Robotics"),
        GRAPPLE         (0x06, "Grapple"),
        MINDSENSORS     (0x07, "MindSensors"),
        TEAM_USE        (0x08, "Team Use"),
        KAUAI_LABS      (0x09, "Kauai Labs"),
        COPPERFORGE     (0x0A, "Copperforge"),
        PLAYING_WITH_FUSION (0x0B, "Playing With Fusion"),
        STUDICA         (0x0C, "Studica"),
        THE_THRIFTY_BOT (0x0D, "The Thrifty Bot"),
        REDUX           (0x0E, "Redux Robotics"),
        ANDYMARK        (0x0F, "AndyMark"),
        VIVID_HOSTING   (0x10, "Vivid Hosting");

        final int code;
        final String displayName;
        Manufacturer(int code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }
    }

    // -----------------------------------------------------------------------
    // FRC CAN Device Types (5-bit)
    // -----------------------------------------------------------------------
    private enum DeviceType {
        ROBOT_CONTROLLER    (0x01, "Robot Controller"),
        MOTOR_CONTROLLER    (0x02, "Motor Controller"),
        RELAY_CONTROLLER    (0x03, "Relay Controller"),
        GYRO_SENSOR         (0x04, "Gyro/IMU Sensor"),
        ACCELEROMETER       (0x05, "Accelerometer"),
        ULTRASONIC_SENSOR   (0x06, "Ultrasonic Sensor"),
        GEAR_TOOTH_SENSOR   (0x07, "Encoder/Gear Tooth Sensor"),
        POWER_DISTRIBUTION  (0x08, "Power Distribution"),
        PNEUMATICS          (0x09, "Pneumatics Controller"),
        MISC                (0x0A, "Miscellaneous"),
        IO_BREAKOUT         (0x0B, "I/O Breakout");

        final int code;
        final String genericName;
        DeviceType(int code, String genericName) {
            this.code = code;
            this.genericName = genericName;
        }
    }

    // -----------------------------------------------------------------------
    // Specific device name lookup by (manufacturer, deviceType).
    // Falls back to "Manufacturer + DeviceType" if not present.
    // -----------------------------------------------------------------------
    private static final Map<Integer, String> DEVICE_NAMES = new HashMap<>();

    static {
        add(Manufacturer.CTRE,                DeviceType.MOTOR_CONTROLLER,  "TalonFX (Kraken X60 / Falcon 500)");
        add(Manufacturer.CTRE,                DeviceType.GEAR_TOOTH_SENSOR, "CANcoder");
        add(Manufacturer.CTRE,                DeviceType.GYRO_SENSOR,       "Pigeon 2 IMU");
        add(Manufacturer.CTRE,                DeviceType.POWER_DISTRIBUTION,"CTRE PDH / PDP");
        add(Manufacturer.CTRE,                DeviceType.PNEUMATICS,        "CTRE Pneumatics Control Module");
        add(Manufacturer.CTRE,                DeviceType.MISC,              "CANdle LED Controller");
        add(Manufacturer.REV,                 DeviceType.MOTOR_CONTROLLER,  "SPARK MAX / SPARK Flex");
        add(Manufacturer.REV,                 DeviceType.POWER_DISTRIBUTION,"REV Power Distribution Hub");
        add(Manufacturer.REV,                 DeviceType.PNEUMATICS,        "REV Pneumatic Hub");
        add(Manufacturer.NI,                  DeviceType.ROBOT_CONTROLLER,  "roboRIO");
        add(Manufacturer.KAUAI_LABS,          DeviceType.GYRO_SENSOR,       "navX-MXP / navX2 IMU");
        add(Manufacturer.PLAYING_WITH_FUSION, DeviceType.GEAR_TOOTH_SENSOR, "Playing With Fusion CANandcoder");
        add(Manufacturer.REDUX,               DeviceType.GEAR_TOOTH_SENSOR, "Redux Canandmag Encoder");
        add(Manufacturer.THE_THRIFTY_BOT,     DeviceType.MOTOR_CONTROLLER,  "ThriftyNova Motor Controller");
        add(Manufacturer.STUDICA,             DeviceType.MOTOR_CONTROLLER,  "Studica Motor Controller");
        add(Manufacturer.ANDYMARK,            DeviceType.POWER_DISTRIBUTION,"AndyMark Power Distribution Board");
        add(Manufacturer.LUMINARY_MICRO,      DeviceType.MOTOR_CONTROLLER,  "Jaguar Motor Controller");
        add(Manufacturer.COPPERFORGE,         DeviceType.MISC,              "Copperforge Device");
        add(Manufacturer.GRAPPLE,             DeviceType.MISC,              "Grapple Device");
    }

    private static void add(Manufacturer m, DeviceType t, String name) {
        DEVICE_NAMES.put((m.code << 5) | t.code, name);
    }

    private static String lookupDeviceName(Manufacturer m, DeviceType t) {
        String specific = DEVICE_NAMES.get((m.code << 5) | t.code);
        return specific != null ? specific : (m.displayName + " " + t.genericName);
    }

    // -----------------------------------------------------------------------

    public record DetectedDevice(Manufacturer manufacturer, DeviceType deviceType, int deviceId) {
        public String description() { return lookupDeviceName(manufacturer, deviceType); }
        /** Duplicate key: within a device type, no two devices may share an ID */
        public String duplicateKey() { return deviceType.code + ":" + deviceId; }
    }

    // -----------------------------------------------------------------------

    public void runInspection() {
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  CAN BUS QA TOOL  —  Passive Device Scan");
        System.out.println("=".repeat(62));

        List<DetectedDevice> detected = scanBus();
        Map<String, Long> idCounts = detected.stream()
                .collect(Collectors.groupingBy(DetectedDevice::duplicateKey, Collectors.counting()));

        printBusHealth();
        printDeviceTable(detected, idCounts);
        printSummary(detected, idCounts);

        System.out.println("=".repeat(62));
        System.out.println("  END OF REPORT");
        System.out.println("=".repeat(62) + "\n");
    }

    // -----------------------------------------------------------------------

    private List<DetectedDevice> scanBus() {
        List<DetectedDevice> found = new ArrayList<>();
        CANData data = new CANData();

        // API ID 0x00 is the lowest-numbered periodic status frame that almost
        // every FRC device broadcasts continuously. We use readPacketLatest so
        // we get the most recently cached frame instantly — no transmission needed.
        final int STATUS_API_ID = 0x00;

        int totalCombinations = Manufacturer.values().length
                              * DeviceType.values().length
                              * (MAX_DEVICE_ID + 1);
        System.out.printf("  Probing %d manufacturer/type/ID combinations...%n%n",
                totalCombinations);

        for (Manufacturer mfr : Manufacturer.values()) {
            for (DeviceType type : DeviceType.values()) {
                // Skip roboRIO itself — always present, not wired by electrical team
                if (mfr == Manufacturer.NI && type == DeviceType.ROBOT_CONTROLLER) continue;

                for (int id = 0; id <= MAX_DEVICE_ID; id++) {
                    try (CAN listener = new CAN(id, mfr.code, type.code)) {
                        // readPacketLatest returns true if any frame has ever been
                        // received for this (mfr, type, id, apiId) combination.
                        if (listener.readPacketLatest(STATUS_API_ID, data)) {
                            found.add(new DetectedDevice(mfr, type, id));
                        }
                    } catch (Exception ignored) {
                        // Invalid mfr/type combos may not be constructible — skip silently
                    }
                }
            }
        }

        return found;
    }

    private static final int MAX_DEVICE_ID = 62;

    // -----------------------------------------------------------------------

    private void printBusHealth() {
        var status = RobotController.getCANStatus();
        System.out.println("--- BUS HEALTH ---");
        System.out.printf("  Utilization:      %.1f%%%n", status.percentBusUtilization * 100.0);
        System.out.printf("  Receive Errors:   %d%n", status.receiveErrorCount);
        System.out.printf("  Transmit Errors:  %d%n", status.transmitErrorCount);
        System.out.printf("  TX Full Count:    %d%n", status.txFullCount);
        boolean healthy = status.receiveErrorCount == 0 && status.transmitErrorCount == 0;
        System.out.printf("  Status:           %s%n%n", healthy ? "✓ HEALTHY" : "⚠ ERRORS — check wiring");
    }

    // -----------------------------------------------------------------------

    private void printDeviceTable(List<DetectedDevice> detected, Map<String, Long> idCounts) {
        System.out.println("--- DETECTED DEVICES ---");

        if (detected.isEmpty()) {
            System.out.println("  (none)\n");
            return;
        }

        // Sort by device ID ascending for easy reading
        detected.sort(Comparator.comparingInt(DetectedDevice::deviceId)
                .thenComparing(d -> d.description()));

        System.out.printf("  %-5s  %-40s  %s%n", "ID", "Device", "Status");
        System.out.println("  " + "-".repeat(60));

        for (DetectedDevice d : detected) {
            long count = idCounts.getOrDefault(d.duplicateKey(), 1L);
            String status = count > 1 ? "✗ DUPLICATE ID" : "✓ OK";
            System.out.printf("  %-5d  %-40s  %s%n", d.deviceId(), d.description(), status);
        }
        System.out.println();
    }

    // -----------------------------------------------------------------------

    private void printSummary(List<DetectedDevice> detected, Map<String, Long> idCounts) {
        long duplicateGroups = idCounts.values().stream().filter(c -> c > 1).count();

        System.out.println("--- SUMMARY ---");
        System.out.printf("  Devices found:   %d%n", detected.size());
        System.out.printf("  Duplicate IDs:   %d%n", duplicateGroups);
        System.out.println();

        if (detected.isEmpty()) {
            System.out.println("  *** RESULT: NO DEVICES — check power and CAN wiring ***");
        } else if (duplicateGroups > 0) {
            System.out.println("  *** RESULT: FAIL — Duplicate IDs must be resolved ***");
            System.out.println("    → Use Phoenix Tuner X or REV Hardware Client to");
            System.out.println("      reassign conflicting device IDs before handoff.");
        } else {
            System.out.println("  *** RESULT: PASS ***");
            System.out.println("    → Verify device list above matches your wiring plan.");
        }
        System.out.println();
    }
}