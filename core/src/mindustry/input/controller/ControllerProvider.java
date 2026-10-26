package mindustry.input.controller;

import arc.input.KeyCode;

/**
 * Abstraction for platform-specific controller input.
 * Core polls this provider each frame. Android implementation feeds MotionEvent data here.
 */
public interface ControllerProvider{
    /** Whether any controller is currently connected */
    boolean isConnected();

    /** Whether this provider is available/enabled */
    boolean isEnabled();

    /** Raw axis value -1..1 for given controller axis KeyCode, or 0 if not applicable */
    float getAxis(KeyCode axis);

    /** Whether a controller button is currently down */
    boolean isButtonPressed(KeyCode button);

    /** Whether button was just pressed this frame (optional, may return false) */
    default boolean isButtonTapped(KeyCode button){ return false; }

    /** Vibrate controller if supported */
    default void vibrate(int durationMs){}

    /** Vibrate with pattern */
    default void vibrate(long[] pattern, int repeat){}

    default void cancelVibrate(){}

    /** Human-readable controller name for UI */
    default String getName(){ return "Controller"; }

    /** Number of connected controllers */
    default int connectedCount(){ return isConnected() ? 1 : 0; }
}
