/*----------------------------------------------------------------------------*/
/* Copyright (c) 2020 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

package edu.wpi.first.wpilibj.simulation;

import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.system.LinearSystem;
import edu.wpi.first.wpilibj.system.RungeKutta;
import edu.wpi.first.wpilibj.system.plant.DCMotor;
import edu.wpi.first.wpilibj.system.plant.LinearSystemId;
import edu.wpi.first.wpilibj.util.Units;
import edu.wpi.first.wpiutil.math.Matrix;
import edu.wpi.first.wpiutil.math.Nat;
import edu.wpi.first.wpiutil.math.VecBuilder;
import edu.wpi.first.wpiutil.math.numbers.N1;
import edu.wpi.first.wpiutil.math.numbers.N2;
import edu.wpi.first.wpiutil.math.numbers.N7;

/**
 * This class simulates the state of the drivetrain. In simulationPeriodic, users should first set inputs from motors with
 * {@link #setInputs(double, double)}, call {@link #update(double)} to update the simulation,
 * and set estimated encoder and gyro positions, as well as estimated odometry pose. Teams can use {@link edu.wpi.first.wpilibj.simulation.Field2d} to
 * visualize their robot on the Sim GUI's field.
 *
 *  <p>Our state-space system is:
 *
 *  <p>x = [[x, y, theta, vel_l, vel_r, dist_l, dist_r, voltError_l, voltError_r, headingError]]^T
 *  in the field coordinate system (dist_* are wheel distances.)
 *
 *  <p>u = [[voltage_l, voltage_r]]^T This is typically the control input of the last timestep
 *  from a LTVDiffDriveController.
 *
 *  <p>y = [[x, y, theta]]^T from a global measurement source(ex. vision),
 *  or y = [[dist_l, dist_r, theta]] from encoders and gyro.
 *
 */
public class DifferentialDrivetrainSim {
  private final DCMotor m_motor;
  private final double m_originalGearing;
  private double m_currentGearing;
  private final double m_wheelRadiusMeters;
  @SuppressWarnings("MemberName")
  private Matrix<N2, N1> m_u;
  @SuppressWarnings("MemberName")
  private Matrix<N7, N1> m_x;

  private final double m_rb;
  private final LinearSystem<N2, N2, N2> m_plant;

  /**
   * Create a SimDrivetrain.
   *
   * @param driveMotor        A {@link DCMotor} representing the left side of the drivetrain.
   * @param gearing           The gearing on the drive between motor and wheel, as output over input.
   *                          This must be the same ratio as the ratio used to identify or
   *                          create the drivetrainPlant.
   * @param jKgMetersSquared  The moment of inertia of the drivetrain about its center.
   * @param massKg            The mass of the drivebase.
   * @param wheelRadiusMeters The radius of the wheels on the drivetrain.
   * @param trackWidthMeters  The robot's track width, or distance between left and right wheels.
   */
  public DifferentialDrivetrainSim(DCMotor driveMotor, double gearing,
                                   double jKgMetersSquared, double massKg,
                                   double wheelRadiusMeters, double trackWidthMeters) {
    this(LinearSystemId.createDrivetrainVelocitySystem(driveMotor, massKg, wheelRadiusMeters,
        trackWidthMeters / 2.0, jKgMetersSquared, gearing),
        driveMotor, gearing, trackWidthMeters, wheelRadiusMeters);
  }

  /**
   * Create a SimDrivetrain
   * .
   * @param drivetrainPlant   The {@link LinearSystem} representing the robot's drivetrain. This
   *                          system can be created with {@link edu.wpi.first.wpilibj.system.plant.LinearSystemId#createDrivetrainVelocitySystem(DCMotor, double, double, double, double, double)}
   *                          or {@link edu.wpi.first.wpilibj.system.plant.LinearSystemId#identifyDrivetrainSystem(double, double, double, double)}.
   * @param driveMotor        A {@link DCMotor} representing the drivetrain.
   * @param gearing           The gearingRatio ratio of the robot, as output over input.
   *                          This must be the same ratio as the ratio used to identify or
   *                          create the drivetrainPlant.
   * @param trackWidthMeters  The distance between the two sides of the drivetrian. Can be
   *                          found with frc-characterization.
   * @param wheelRadiusMeters The radius of the wheels on the drivetrain, in meters.
   */
  public DifferentialDrivetrainSim(LinearSystem<N2, N2, N2> drivetrainPlant,
                                   DCMotor driveMotor, double gearing,
                                   double trackWidthMeters,
                                   double wheelRadiusMeters) {
    this.m_plant = drivetrainPlant;
    this.m_rb = trackWidthMeters / 2.0;
    this.m_motor = driveMotor;
    this.m_originalGearing = gearing;
    m_wheelRadiusMeters = wheelRadiusMeters;
    m_currentGearing = m_originalGearing;

    m_x = new Matrix<>(Nat.N7(), Nat.N1());
  }

  /**
   * Sets the applied voltage to the drivetrain. Note that positive voltage must make that
   * side of the drivetrain travel forward (+X).
   *
   * @param leftVoltageVolts  The left voltage.
   * @param rightVoltageVolts The right voltage.
   */
  public void setInputs(double leftVoltageVolts, double rightVoltageVolts) {
    m_u = VecBuilder.fill(leftVoltageVolts, rightVoltageVolts);
  }

  @SuppressWarnings("LocalVariableName")
  public void update(double dtSeconds) {

    // Update state estimate with RK4
    m_x = RungeKutta.rungeKutta(this::getDynamics, m_x, m_u, dtSeconds);
  }

  public double getState(State state) {
    return m_x.get(state.value, 0);
  }

  /**
   * Returns the full simulated state of the drivetrain.
   */
  public Matrix<N7, N1> getState() {
    return m_x;
  }

  /**
   * Returns the direction the robot is pointing.
   *
   * <p>Note that this angle is counterclockwise-positive, while most gyros are
   * clockwise positive.
   */
  public Rotation2d getHeading() {
    return new Rotation2d(getState(State.kHeading));
  }

  /**
   * Returns the current pose.
   */
  public Pose2d getPose() {
    return new Pose2d(m_x.get(0, 0),
      m_x.get(1, 0),
      new Rotation2d(m_x.get(2, 0)));
  }

  public double getCurrentDrawAmps() {
    var loadIleft = m_motor.getCurrent(
        getState(State.kLeftVelocity) * m_currentGearing / m_wheelRadiusMeters,
        m_u.get(0, 0)) * Math.signum(m_u.get(0, 0));

    var loadIright = m_motor.getCurrent(
        getState(State.kRightVelocity) * m_currentGearing / m_wheelRadiusMeters,
        m_u.get(1, 0)) * Math.signum(m_u.get(1, 0));

    return loadIleft + loadIright;
  }

  public double getCurrentGearing() {
    return m_currentGearing;
  }

  /**
   * Sets the gearing reduction on the drivetrain. This is commonly used for
   * shifting drivetrains.
   *
   * @param newGearRatio The new gear ratio, as output over input.
   */
  public void setCurrentGearing(double newGearRatio) {
    this.m_currentGearing = newGearRatio;
  }

  /**
   * Sets the system state.
   *
   * @param state The state.
   */
  public void setState(Matrix<N7, N1> state) {
    m_x = state;
  }

  /**
   * Sets the system pose.
   *
   * @param pose The pose.
   */
  public void setPose(Pose2d pose) {
    m_x.set(State.kX.value, 0, pose.getX());
    m_x.set(State.kY.value, 0, pose.getY());
    m_x.set(State.kHeading.value, 0, pose.getRotation().getRadians());
  }

  @SuppressWarnings({"DuplicatedCode", "LocalVariableName"})
  protected Matrix<N7, N1> getDynamics(Matrix<N7, N1> x, Matrix<N2, N1> u) {

    // Because G can be factored out of B, we can divide by the old ratio and multiply
    // by the new ratio to get a new drivetrain model.
    var B = new Matrix<>(Nat.N4(), Nat.N2());
    B.assignBlock(0, 0, m_plant.getB().times(this.m_currentGearing / this.m_originalGearing));

    // Because G^2 can be factored out of A, we can divide by the old ratio squared and multiply
    // by the new ratio squared to get a new drivetrain model.
    var A = new Matrix<>(Nat.N4(), Nat.N4());
    A.assignBlock(0, 0, m_plant.getA().times((this.m_currentGearing * this.m_currentGearing)
        / (this.m_originalGearing * this.m_originalGearing)));

    A.assignBlock(2, 0, Matrix.eye(Nat.N2()));

    var v = (x.get(State.kLeftVelocity.value, 0) + x.get(State.kRightVelocity.value, 0)) / 2.0;

    var xdot = new Matrix<>(Nat.N7(), Nat.N1());
    xdot.set(0, 0, v * Math.cos(x.get(State.kHeading.value, 0)));
    xdot.set(1, 0, v * Math.sin(x.get(State.kHeading.value, 0)));
    xdot.set(2, 0, (x.get(State.kRightVelocity.value, 0)
        - x.get(State.kLeftVelocity.value, 0)) / (2.0 * m_rb));
    xdot.assignBlock(3, 0,
        A.times(x.block(Nat.N4(), Nat.N1(), 3, 0))
        .plus(B.times(u)));

    return xdot;
  }

  public enum State {
    kX(0),
    kY(1),
    kHeading(2),
    kLeftVelocity(3),
    kRightVelocity(4),
    kLeftPosition(5),
    kRightPosition(6);

    @SuppressWarnings("MemberName")
    public final int value;

    State(int i) {
      this.value = i;
    }
  }

  /**
   * Represents a gearing option of the Toughbox mini.
   * 12.75:1 -- 14:50 and 14:50
   * 10.71:1 -- 14:50 and 16:48
   * 8.45:1 -- 14:50 and 19:45
   * 7.31:1 -- 14:50 and 21:43
   * 5.95:1 -- 14:50 and 24:40
   */
  public enum KitbotGearing {
    k12p75(12.75),
    k10p71(10.71),
    k8p45(8.45),
    k7p31(7.31),
    k5p95(5.95);

    @SuppressWarnings("MemberName")
    public final double value;

    KitbotGearing(double i) {
      this.value = i;
    }
  }

  public enum KitbotMotor {
    kSingleCIMPerSide(DCMotor.getCIM(1)),
    kDualCIMPerSide(DCMotor.getCIM(2)),
    kSingleMiniCIMPerSide(DCMotor.getMiniCIM(1)),
    kDualMiniCIMPerSide(DCMotor.getMiniCIM(2));

    @SuppressWarnings("MemberName")
    public final DCMotor value;

    KitbotMotor(DCMotor i) {
      this.value = i;
    }
  }

  public enum KitbotWheelSize {
    SixInch(Units.inchesToMeters(6)),
    EightInch(Units.inchesToMeters(8)),
    TenInch(Units.inchesToMeters(10));

    @SuppressWarnings("MemberName")
    public final double value;

    KitbotWheelSize(double i) {
      this.value = i;
    }
  }

  /**
   * Create a sim for the standard FRC kitbot.
   *
   * @param motor     The motors installed in the bot.
   * @param gearing   The gearing reduction used.
   * @param wheelSize The wheel size.
   */
  public static DifferentialDrivetrainSim createKitbotSim(KitbotMotor motor, KitbotGearing gearing,
                                                          KitbotWheelSize wheelSize) {
    // MOI estimation -- note that I = m r^2 for point masses
    var batteryMoi = 12.5 / 2.2 * Math.pow(Units.inchesToMeters(10), 2);
    var gearboxMoi = (2.8 /* CIM motor */ * 2 / 2.2 + 2.0 /* Toughbox Mini- ish */)
        * Math.pow(Units.inchesToMeters(26.0 / 2.0), 2);

    return createKitbotSim(motor, gearing, wheelSize, batteryMoi + gearboxMoi);
  }

  /**
   * Create a sim for the standard FRC kitbot.
   *
   * @param motor            The motors installed in the bot.
   * @param gearing          The gearing reduction used.
   * @param wheelSize        The wheel size.
   * @param jKgMetersSquared The moment of inertia of the drivebase. This can be calculated using
   *                         frc-characterization.
   */
  public static DifferentialDrivetrainSim createKitbotSim(KitbotMotor motor, KitbotGearing gearing,
                                                          KitbotWheelSize wheelSize, double jKgMetersSquared) {
    return new DifferentialDrivetrainSim(motor.value, gearing.value, jKgMetersSquared, 25 / 2.2,
        wheelSize.value / 2.0, Units.inchesToMeters(26));
  }
}
