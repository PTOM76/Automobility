package io.github.foundationgames.automobility.automobile;

public class WheelBase {
    public final WheelPos[] wheels;

    public WheelBase(WheelPos ... wheels) {
        this.wheels = wheels;
    }

    public enum WheelSide {
        LEFT,
        RIGHT
    }

    public enum WheelEnd {
        FRONT,
        BACK
    }

    public static record WheelPos(float forward, float right, float scale, float yaw, WheelEnd end, WheelSide side) {}

    public static WheelBase basic(float separationLong, float separationWide) {
        return new BasicWheelBase(separationLong, separationWide);
    }
}
