package mindustry.android.controller;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import arc.input.KeyCode;
import arc.util.Log;
import mindustry.input.controller.ControllerConfig;
import mindustry.input.controller.ControllerProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Android-specific controller provider that handles MotionEvent axes and KeyEvent button presses
 * from Android gamepads (Xbox, PlayStation, generic Bluetooth controllers).
 *
 * Handles:
 * - Left stick: AXIS_X, AXIS_Y
 * - Right stick: AXIS_Z, AXIS_RZ
 * - Triggers: AXIS_LTRIGGER, AXIS_RTRIGGER (or AXIS_BRAKE/GAS)
 * - Hat (D-pad): AXIS_HAT_X, AXIS_HAT_Y
 * - Buttons: KEYCODE_BUTTON_A/B/X/Y, L1/R1, L2/R2, etc.
 */
public class AndroidControllerProvider implements ControllerProvider{
    // axis values
    private volatile float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, hatX, hatY;
    private volatile float lTriggerAlt, rTriggerAlt; // for older APIs

    // button states
    private final Map<Integer, Boolean> buttonStates = new HashMap<>();
    private final Map<Integer, Boolean> buttonPrevStates = new HashMap<>();

    // last seen device
    private volatile int lastDeviceId = -1;
    private volatile String deviceName = "Controller";
    private volatile boolean connected = false;
    private volatile long lastEventTime = 0;

    // mapping from Android keycodes to our internal button ids (use KeyEvent constants)
    private static final int[] androidButtons = {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_C,
        KeyEvent.KEYCODE_BUTTON_Z
    };

    // Android InputDevice sources
    public boolean onGenericMotionEvent(MotionEvent event){
        if((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
           (event.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD){
            return false;
        }

        InputDevice device = event.getDevice();
        if(device != null){
            lastDeviceId = device.getId();
            deviceName = device.getName();
            connected = true;
            lastEventTime = System.currentTimeMillis();
        }

        // Check if this is from a joystick/gamepad
        // Left stick
        leftX = getCenteredAxis(event, MotionEvent.AXIS_X);
        leftY = getCenteredAxis(event, MotionEvent.AXIS_Y);

        // Right stick (different devices use different axes)
        rightX = getCenteredAxis(event, MotionEvent.AXIS_Z);
        if(Math.abs(rightX) < 0.01f) rightX = getCenteredAxis(event, MotionEvent.AXIS_RX);
        rightY = getCenteredAxis(event, MotionEvent.AXIS_RZ);
        if(Math.abs(rightY) < 0.01f) rightY = getCenteredAxis(event, MotionEvent.AXIS_RY);

        // Triggers (Android 5.0+ uses AXIS_LTRIGGER/RTRIGGER, older may use BRAKE/GAS)
        leftTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        rightTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        if(leftTrigger == 0) leftTrigger = event.getAxisValue(MotionEvent.AXIS_BRAKE);
        if(rightTrigger == 0) rightTrigger = event.getAxisValue(MotionEvent.AXIS_GAS);

        // D-pad hat
        hatX = getCenteredAxis(event, MotionEvent.AXIS_HAT_X);
        hatY = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y);

        // Some controllers send D-pad as hat, others as buttons - we handle both
        // If hat is non-zero, consider it D-pad press as well
        // Also ensure deadzone for hat
        if(Math.abs(hatX) < 0.3f) hatX = 0;
        if(Math.abs(hatY) < 0.3f) hatY = 0;

        // Log occasionally for debugging
        // Log.info("Controller axes: L( @, @ ) R( @, @ ) LT:@ RT:@ Hat:@,@", leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, hatX, hatY);

        return true;
    }

    public boolean onKeyEvent(KeyEvent event){
        int keyCode = event.getKeyCode();
        boolean isGamepadButton = isGamepadKey(keyCode) ||
            (event.getDevice() != null && (event.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
            (event.getDevice() != null && (event.getDevice().getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);

        if(!isGamepadButton) return false;

        InputDevice device = event.getDevice();
        if(device != null){
            lastDeviceId = device.getId();
            deviceName = device.getName();
            connected = true;
            lastEventTime = System.currentTimeMillis();
        }

        int action = event.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;

        synchronized(buttonStates){
            buttonPrevStates.put(keyCode, buttonStates.getOrDefault(keyCode, false));
            buttonStates.put(keyCode, down);
        }

        // Log.info("Controller button @ @", keyCode, down ? "down" : "up");

        // return true to indicate we handled it (prevent other handling)
        return true;
    }

    private boolean isGamepadKey(int keyCode){
        for(int b : androidButtons) if(b == keyCode) return true;
        // also check D-pad and generic gamepad ranges
        return (keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE) ||
               (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    private float getCenteredAxis(MotionEvent event, int axis){
        InputDevice device = event.getDevice();
        if(device != null){
            InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
            if(range != null){
                float flat = range.getFlat();
                float value = event.getAxisValue(axis);
                if(Math.abs(value) > flat){
                    return value;
                }
            }
        }
        return event.getAxisValue(axis);
    }

    // timeout to detect disconnection (no events for 5 seconds)
    private boolean checkTimeout(){
        if(lastEventTime == 0) return false;
        if(System.currentTimeMillis() - lastEventTime > 5000){
            // check if device still exists
            if(lastDeviceId != -1){
                InputDevice dev = InputDevice.getDevice(lastDeviceId);
                if(dev == null){
                    connected = false;
                    return false;
                }
            }
        }
        return connected;
    }

    @Override
    public boolean isConnected(){
        if(!connected) return false;
        // check for any connected gamepad device
        int[] ids = InputDevice.getDeviceIds();
        for(int id : ids){
            InputDevice dev = InputDevice.getDevice(id);
            if(dev != null){
                int sources = dev.getSources();
                if(((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                   ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)){
                    return true;
                }
            }
        }
        // fallback to timeout logic
        return checkTimeout();
    }

    @Override
    public boolean isEnabled(){
        return ControllerConfig.enabled();
    }

    @Override
    public float getAxis(KeyCode axis){
        // map Arc KeyCodes to Android axes
        switch(axis){
            case controllerLStickXAxis: return leftX;
            case controllerLStickYAxis: return leftY;
            case controllerRStickXAxis: return rightX;
            case controllerRStickYAxis: return rightY;
            case controllerLTrigger: return leftTrigger;
            case controllerRTrigger: return rightTrigger;
            case scroll: return 0f; // not used
            default: return 0f;
        }
    }

    @Override
    public boolean isButtonPressed(KeyCode button){
        // check both axes hat as buttons and actual buttons
        // Handle D-pad via hat or buttons
        switch(button){
            case controllerdPadUp: return hatY < -0.5f || isAndroidButtonPressed(KeyEvent.KEYCODE_DPAD_UP);
            case controllerdPadDown: return hatY > 0.5f || isAndroidButtonPressed(KeyEvent.KEYCODE_DPAD_DOWN);
            case controllerdPadLeft: return hatX < -0.5f || isAndroidButtonPressed(KeyEvent.KEYCODE_DPAD_LEFT);
            case controllerdPadRight: return hatX > 0.5f || isAndroidButtonPressed(KeyEvent.KEYCODE_DPAD_RIGHT);
            case controllerA: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_A);
            case controllerB: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_B);
            case controllerX: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_X);
            case controllerY: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_Y);
            case controllerLBumper: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_L1);
            case controllerRBumper: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_R1);
            case controllerLTrigger: return leftTrigger > 0.5f || isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_L2);
            case controllerRTrigger: return rightTrigger > 0.5f || isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_R2);
            case controllerBack: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_SELECT);
            case controllerStart: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_START);
            case controllerGuide: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_MODE);
            case controllerLStick: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_THUMBL);
            case controllerRStick: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_THUMBR);
            // also support button.* mappings (Android maps gamepad to those too)
            case buttonA: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_A);
            case buttonB: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_B);
            case buttonX: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_X);
            case buttonY: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_Y);
            case buttonL1: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_L1);
            case buttonR1: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_R1);
            case buttonL2: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_L2);
            case buttonR2: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_R2);
            case buttonThumbL: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_THUMBL);
            case buttonThumbR: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_THUMBR);
            case buttonStart: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_START);
            case buttonSelect: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_SELECT);
            case buttonMode: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_MODE);
            case back: return isAndroidButtonPressed(KeyEvent.KEYCODE_BACK) || isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_B);
            case escape: return isAndroidButtonPressed(KeyEvent.KEYCODE_BUTTON_START) || isAndroidButtonPressed(KeyEvent.KEYCODE_BACK);
            default: return false;
        }
    }

    private boolean isAndroidButtonPressed(int androidKey){
        synchronized(buttonStates){
            return buttonStates.getOrDefault(androidKey, false);
        }
    }

    @Override
    public boolean isButtonTapped(KeyCode button){
        // Rising edge, consumed once per press. Without consuming, this returned true on every
        // frame between the key-down and key-up events, so a single (or held) press fired the
        // action many times - e.g. opening a dialog and then immediately "clicking" whatever the
        // new dialog put under the focus/cursor.
        int androidKey = toAndroidKey(button);
        if(androidKey == -1) return false;
        synchronized(buttonStates){
            boolean curr = buttonStates.getOrDefault(androidKey, false);
            boolean prev = buttonPrevStates.getOrDefault(androidKey, false);
            if(curr && !prev){
                buttonPrevStates.put(androidKey, true); // consume this edge
                return true;
            }
            return false;
        }
    }

    private int toAndroidKey(KeyCode code){
        if(code == KeyCode.controllerA || code == KeyCode.buttonA) return KeyEvent.KEYCODE_BUTTON_A;
        if(code == KeyCode.controllerB || code == KeyCode.buttonB) return KeyEvent.KEYCODE_BUTTON_B;
        if(code == KeyCode.controllerX || code == KeyCode.buttonX) return KeyEvent.KEYCODE_BUTTON_X;
        if(code == KeyCode.controllerY || code == KeyCode.buttonY) return KeyEvent.KEYCODE_BUTTON_Y;
        if(code == KeyCode.controllerLBumper || code == KeyCode.buttonL1) return KeyEvent.KEYCODE_BUTTON_L1;
        if(code == KeyCode.controllerRBumper || code == KeyCode.buttonR1) return KeyEvent.KEYCODE_BUTTON_R1;
        if(code == KeyCode.controllerBack || code == KeyCode.buttonSelect) return KeyEvent.KEYCODE_BUTTON_SELECT;
        if(code == KeyCode.controllerStart || code == KeyCode.buttonStart) return KeyEvent.KEYCODE_BUTTON_START;
        if(code == KeyCode.controllerLStick || code == KeyCode.buttonThumbL) return KeyEvent.KEYCODE_BUTTON_THUMBL;
        if(code == KeyCode.controllerRStick || code == KeyCode.buttonThumbR) return KeyEvent.KEYCODE_BUTTON_THUMBR;
        if(code == KeyCode.controllerdPadUp) return KeyEvent.KEYCODE_DPAD_UP;
        if(code == KeyCode.controllerdPadDown) return KeyEvent.KEYCODE_DPAD_DOWN;
        if(code == KeyCode.controllerdPadLeft) return KeyEvent.KEYCODE_DPAD_LEFT;
        if(code == KeyCode.controllerdPadRight) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if(code == KeyCode.controllerLTrigger || code == KeyCode.buttonL2) return KeyEvent.KEYCODE_BUTTON_L2;
        if(code == KeyCode.controllerRTrigger || code == KeyCode.buttonR2) return KeyEvent.KEYCODE_BUTTON_R2;
        return -1;
    }

    @Override
    public String getName(){
        return deviceName != null ? deviceName : "Android Controller";
    }

    @Override
    public int connectedCount(){
        int count = 0;
        int[] ids = InputDevice.getDeviceIds();
        for(int id : ids){
            InputDevice dev = InputDevice.getDevice(id);
            if(dev != null){
                int sources = dev.getSources();
                if(((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                   ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)){
                    count++;
                }
            }
        }
        return count;
    }

    /** Called to update button prev states each frame */
    public void update(){
        synchronized(buttonStates){
            buttonPrevStates.clear();
            buttonPrevStates.putAll(buttonStates);
        }
        // also update connection based on device list
        if(!isConnected() && connected){
            // maybe disconnected
        }
    }

    public void cancelAll(){
        synchronized(buttonStates){
            buttonStates.clear();
            buttonPrevStates.clear();
        }
        leftX = leftY = rightX = rightY = leftTrigger = rightTrigger = hatX = hatY = 0;
    }
}
