package frc.robot;

import edu.wpi.first.hal.CANData;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
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

    /** The default NetworkTables key under which all CANary results are published. */
    public static final String NT_TABLE_KEY = "CANary";

    private final NetworkTable table;

    /**
     * Creates a new inspector that publishes results to the default {@code "CANary"}
     * NetworkTables table.
     */
    public CANBusInspector() {
        this(NetworkTableInstance.getDefault().getTable(NT_TABLE_KEY));
    }

    /**
     * Creates a new inspector that publishes results to the supplied
     * {@link NetworkTable}. Use this constructor when you want results under a
     * custom table path.
     *
     * @param networkTable the NetworkTable to publish results to
     */
    public CANBusInspector(NetworkTable networkTable) {
        this.table = networkTable;
    }

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

    /**
     * Represents a single CAN device detected on the bus.
     *
     * @param manufacturer the manufacturer that produced this device
     * @param deviceType   the FRC CAN device-type category (motor controller, gyro, etc.)
     * @param deviceId     the 6-bit CAN device ID (0–62) assigned to this device
     */
    public record DetectedDevice(Manufacturer manufacturer, DeviceType deviceType, int deviceId) {
        /**
         * Returns a human-readable name for this device (e.g. "TalonFX (Kraken X60 / Falcon 500)").
         * Uses the {@code DEVICE_NAMES} lookup table and falls back to
         * "{@code Manufacturer DeviceType}" if no specific name is registered.
         *
         * @return the display name for this device
         */
        public String description() { return lookupDeviceName(manufacturer, deviceType); }

        /**
         * Returns a key used to detect duplicate CAN IDs within the same device type.
         * Two devices are considered duplicates if they share the same device-type code
         * <em>and</em> the same device ID.
         *
         * @return a string of the form {@code "deviceTypeCode:deviceId"}
         */
        public String duplicateKey() { return deviceType.code + ":" + deviceId; }
    }

    // -----------------------------------------------------------------------

    /**
     * Runs the full CAN bus inspection and prints a formatted report to standard output
     * (visible in Driver Station RioLog).
     *
     * <p>The report includes:
     * <ul>
     *   <li>Bus health (utilization %, RX/TX error counts)</li>
     *   <li>A table of every detected device with its CAN ID and model name</li>
     *   <li>Duplicate-ID warnings</li>
     *   <li>An overall PASS / FAIL result</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This method is blocking and iterates over all possible
     * manufacturer × device-type × ID combinations. Call it once (e.g. in
     * {@code robotInit} with a delay, or from a triggered command) rather than in a
     * periodic loop.
     */
    public void runInspection() {
        runInspection(false);
    }

    /**
     * Runs the full CAN bus inspection and prints a formatted report to standard output
     * (visible in Driver Station RioLog). Optionally publishes the same results to NetworkTables.
     *
     * <p>The report includes:
     * <ul>
     *   <li>Bus health (utilization %, RX/TX error counts)</li>
     *   <li>A table of every detected device with its CAN ID and model name</li>
     *   <li>Duplicate-ID warnings</li>
     *   <li>An overall PASS / FAIL result</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This method is blocking and iterates over all possible
     * manufacturer × device-type × ID combinations. Call it once (e.g. in
     * {@code robotInit} with a delay, or from a triggered command) rather than in a
     * periodic loop.
     */
    public void runInspection(boolean publishToNetworkTables) {
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  CAN BUS QA TOOL  —  Passive Device Scan");
        System.out.println("=".repeat(62));

        List<DetectedDevice> detected = scanBus();
        Map<String, Long> idCounts = detected.stream()
                .collect(Collectors.groupingBy(DetectedDevice::duplicateKey, Collectors.counting()));

        printBusHealth();
        printDeviceTable(detected, idCounts);
        printSummary(detected, idCounts);

        if (publishToNetworkTables) {
            publishToNetworkTables(detected, idCounts);
        }

        System.out.println("=".repeat(62));
        System.out.println("  END OF REPORT");
        System.out.println("=".repeat(62) + "\n");
    }

    /**
     * Scans the CAN bus for all connected FRC devices by probing every known
     * manufacturer × device-type × ID (0–62) combination.
     *
     * <p>For each combination, a temporary {@link edu.wpi.first.wpilibj.CAN CAN} receiver
     * is opened and {@code readPacketLatest} is called on API ID {@code 0x00} (the
     * lowest-numbered periodic status frame). If a frame has been received for that
     * tuple, the device is recorded.
     *
     * <p>The roboRIO itself is intentionally skipped (NI / Robot Controller) because
     * it is always present and is not wired by the electrical team.
     *
     * <p><strong>Performance:</strong> This method opens and closes a CAN receiver for
     * every combination (~11,000), so it is not instantaneous. Avoid calling it in a
     * periodic loop.
     *
     * @return a list of {@link DetectedDevice} entries, one per device found on the bus
     */
    public List<DetectedDevice> scanBus() {
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

    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------

    /**
     * Publishes the full inspection report to NetworkTables under the
     * {@link #NT_TABLE_KEY} table (default {@code "CANary"}).
     *
     * <p>Published entries:
     * <table>
     *   <caption>NetworkTables layout</caption>
     *   <tr><th>Key</th><th>Type</th><th>Description</th></tr>
     *   <tr><td>{@code BusHealth/Utilization}</td><td>double</td><td>Bus utilization (0–100%)</td></tr>
     *   <tr><td>{@code BusHealth/ReceiveErrors}</td><td>int</td><td>Receive error count</td></tr>
     *   <tr><td>{@code BusHealth/TransmitErrors}</td><td>int</td><td>Transmit error count</td></tr>
     *   <tr><td>{@code BusHealth/TxFullCount}</td><td>int</td><td>TX-full count</td></tr>
     *   <tr><td>{@code BusHealth/Healthy}</td><td>boolean</td><td>True when both error counters are zero</td></tr>
     *   <tr><td>{@code Devices}</td><td>String[]</td><td>One entry per device: {@code "ID | Name | Status"}</td></tr>
     *   <tr><td>{@code Summary/DeviceCount}</td><td>int</td><td>Total devices detected</td></tr>
     *   <tr><td>{@code Summary/DuplicateCount}</td><td>int</td><td>Number of duplicate-ID groups</td></tr>
     *   <tr><td>{@code Summary/Result}</td><td>String</td><td>{@code "PASS"}, {@code "FAIL"}, or {@code "NO DEVICES"}</td></tr>
     * </table>
     */
    private void publishToNetworkTables(List<DetectedDevice> detected, Map<String, Long> idCounts) {
        // --- Bus Health ---
        var status = RobotController.getCANStatus();
        NetworkTable healthTable = table.getSubTable("BusHealth");
        healthTable.getEntry("Utilization").setDouble(status.percentBusUtilization * 100.0);
        healthTable.getEntry("ReceiveErrors").setInteger(status.receiveErrorCount);
        healthTable.getEntry("TransmitErrors").setInteger(status.transmitErrorCount);
        healthTable.getEntry("TxFullCount").setInteger(status.txFullCount);
        healthTable.getEntry("Healthy").setBoolean(
                status.receiveErrorCount == 0 && status.transmitErrorCount == 0);

        // --- Devices ---
        // Each entry formatted as "ID | DeviceName | Status" for easy dashboard consumption
        String[] deviceEntries = detected.stream()
                .sorted(Comparator.comparingInt(DetectedDevice::deviceId)
                        .thenComparing(DetectedDevice::description))
                .map(d -> {
                    long count = idCounts.getOrDefault(d.duplicateKey(), 1L);
                    String deviceStatus = count > 1 ? "DUPLICATE" : "OK";
                    return d.deviceId() + " | " + d.description() + " | " + deviceStatus;
                })
                .toArray(String[]::new);
        table.getEntry("Devices").setStringArray(deviceEntries);

        // --- Summary ---
        long duplicateGroups = idCounts.values().stream().filter(c -> c > 1).count();
        NetworkTable summaryTable = table.getSubTable("Summary");
        summaryTable.getEntry("DeviceCount").setInteger(detected.size());
        summaryTable.getEntry("DuplicateCount").setInteger(duplicateGroups);

        String result;
        if (detected.isEmpty()) {
            result = "NO DEVICES";
        } else if (duplicateGroups > 0) {
            result = "FAIL";
        } else {
            result = "PASS";
        }
        summaryTable.getEntry("Result").setString(result);
    }
}