// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot;

import org.littletonrobotics.junction.LoggedRobot;

import edu.wpi.first.wpilibj.Timer;

public class Robot extends LoggedRobot {

  private final CANBusInspector inspector = new CANBusInspector();
    private boolean hasRun = false;
 
    @Override
    public void robotInit() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  CAN BUS QA TOOL - Initializing...");
        System.out.println("=".repeat(60));
        System.out.println("Waiting 5 seconds for all devices to enumerate...");
        System.out.println("Open Driver Station -> Console to see the report.");
    }
 
    @Override
    public void robotPeriodic() {
        // Wait a few seconds on first run to let all devices enumerate
        if (!hasRun && Timer.getFPGATimestamp() > 5.0) {
            hasRun = true;
            inspector.runInspection();
        }
    }
 
    @Override
    public void disabledInit() {}
 
    @Override
    public void disabledPeriodic() {}
}
