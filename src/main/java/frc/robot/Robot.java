// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot;

import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj.Timer;

public class Robot extends TimedRobot {

    private final CANBusInspector inspector = new CANBusInspector();

    /** Interval in seconds between each automatic inspection run. */
    private static final double INSPECTION_INTERVAL_SECONDS = 10.0;

    /** Timestamp of the last completed inspection (FPGA time). */
    private double lastInspectionTimestamp = 0.0;

    @Override
    public void robotInit() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  CAN BUS QA TOOL - Initializing...");
        System.out.println("=".repeat(60));
        System.out.println("Inspections will run every " + (int) INSPECTION_INTERVAL_SECONDS + " seconds.");
        System.out.println("Open Driver Station -> Console to see the report.");
    }

    @Override
    public void robotPeriodic() {
        double now = Timer.getFPGATimestamp();

        if (now - lastInspectionTimestamp >= INSPECTION_INTERVAL_SECONDS) {
            lastInspectionTimestamp = now;

            // Run inspection — prints to RioLog only (no NetworkTables push)
            inspector.runInspection();

            // To also push results to NetworkTables, use the overload instead:
            // inspector.runInspection(true);
        }
    }

    @Override
    public void disabledInit() {}

    @Override
    public void disabledPeriodic() {}
}
