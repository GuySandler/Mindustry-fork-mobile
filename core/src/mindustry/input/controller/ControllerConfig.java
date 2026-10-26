package mindustry.input.controller;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;

/**
 * Holds controller configuration and provides helpers for deadzone and sensitivity.
 * Uses Core.settings for persistence.
 */
public class ControllerConfig{
    // setting keys
    public static final String ENABLED = "controller.enabled";
    public static final String DEADZONE = "controller.deadzone";
    public static final String SENSITIVITY = "controller.sensitivity";
    public static final String INVERT_Y = "controller.invertY";
    public static final String VIBRATION = "controller.vibration";
    public static final String RIGHT_STICK_SENSITIVITY = "controller.rStickSensitivity";
    public static final String LEFT_STICK_SENSITIVITY = "controller.lStickSensitivity";
    public static final String TRIGGER_THRESHOLD = "controller.triggerThreshold";
    public static final String CURSOR_SPEED = "controller.cursorSpeed";
    public static final String PRESET = "controller.preset";

    // defaults
    public static final int DEFAULT_DEADZONE = 15; // percent 0-50
    public static final int DEFAULT_SENSITIVITY = 100; // percent 50-200
    public static final int DEFAULT_CURSOR_SPEED = 100; // percent
    public static final int DEFAULT_TRIGGER_THRESHOLD = 15;

    public static void loadDefaults(){
        Core.settings.defaults(
            ENABLED, true,
            DEADZONE, DEFAULT_DEADZONE,
            SENSITIVITY, DEFAULT_SENSITIVITY,
            RIGHT_STICK_SENSITIVITY, 100,
            LEFT_STICK_SENSITIVITY, 100,
            CURSOR_SPEED, 100,
            TRIGGER_THRESHOLD, DEFAULT_TRIGGER_THRESHOLD,
            INVERT_Y, false,
            VIBRATION, true,
            PRESET, 0,
            // additional toggles
            "controller.showCursor", true,
            "controller.cursorScale", 100,
            "controller.cameraStick", false,
            "controller.aimAssist", false
        );
    }

    public static boolean enabled(){
        return Core.settings.getBool(ENABLED, true);
    }

    public static float deadzone(){
        return Core.settings.getInt(DEADZONE, DEFAULT_DEADZONE) / 100f;
    }

    public static float sensitivity(){
        return Core.settings.getInt(SENSITIVITY, DEFAULT_SENSITIVITY) / 100f;
    }

    public static float rightStickSensitivity(){
        return Core.settings.getInt(RIGHT_STICK_SENSITIVITY, 100) / 100f * sensitivity();
    }

    public static float leftStickSensitivity(){
        return Core.settings.getInt(LEFT_STICK_SENSITIVITY, 100) / 100f * sensitivity();
    }

    public static float cursorSpeed(){
        return Core.settings.getInt(CURSOR_SPEED, 100) / 100f;
    }

    public static float triggerThreshold(){
        return Core.settings.getInt(TRIGGER_THRESHOLD, DEFAULT_TRIGGER_THRESHOLD) / 100f;
    }

    public static boolean invertY(){
        return Core.settings.getBool(INVERT_Y, false);
    }

    public static boolean vibration(){
        return Core.settings.getBool(VIBRATION, true);
    }

    /** Applies radial deadzone and returns adjusted vector length, preserving direction */
    public static float applyDeadzone(float x, float y, float deadzone){
        float mag = Mathf.sqrt(x*x + y*y);
        if(mag < deadzone) return 0f;
        // scale to 0-1 after deadzone
        return (mag - deadzone) / (1f - deadzone);
    }

    public static float applyDeadzone(float value, float deadzone){
        if(Math.abs(value) < deadzone) return 0f;
        float sign = Math.signum(value);
        return sign * (Math.abs(value) - deadzone) / (1f - deadzone);
    }

    public static float processAxis(float value){
        return applyDeadzone(value, deadzone()) * sensitivity();
    }

    public static float processAxis(float x, float y, boolean isX){
        float dz = deadzone();
        float mag = Mathf.sqrt(x*x + y*y);
        if(mag < dz) return 0f;
        float scaled = (mag - dz) / (1f - dz);
        float angle = Mathf.atan2(y, x);
        float outX = Mathf.cos(angle) * scaled;
        float outY = Mathf.sin(angle) * scaled;
        return (isX ? outX : outY) * sensitivity();
    }
}
