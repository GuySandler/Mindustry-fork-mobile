package mindustry.input.controller;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;

/**
 * Global controller state manager. Handles polling, deadzone, and high-level queries.
 * Platform code (Android) should set a custom provider via setProvider().
 */
public class ControllerSupport{
    private static ControllerProvider provider = new DefaultControllerProvider();
    private static ControllerProvider defaultProvider = new DefaultControllerProvider();

    // cached axis states for press detection
    private static boolean[] buttonPrev = new boolean[KeyCode.all.length];
    private static boolean[] buttonCurr = new boolean[KeyCode.all.length];

    // virtual cursor for controller UI navigation
    public static float virtualX = -1, virtualY = -1;
    public static boolean virtualCursorEnabled = true;
    private static float cursorX, cursorY;

    // connection tracking
    private static boolean wasConnected = false;
    private static long lastConnectLog = 0;

    public static void setProvider(ControllerProvider p){
        if(p != null) provider = p;
    }

    public static ControllerProvider getProvider(){
        return provider;
    }

    public static void resetProvider(){
        provider = defaultProvider;
    }

    public static boolean isEnabled(){
        return ControllerConfig.enabled() && provider.isEnabled();
    }

    public static boolean isConnected(){
        try{
            return provider.isConnected();
        }catch(Exception e){
            return false;
        }
    }

    /** Call each frame to update internal button states */
    public static void update(){
        if(!isEnabled()){
            wasConnected = false;
            return;
        }

        boolean connected = isConnected();
        if(connected != wasConnected){
            wasConnected = connected;
            if(connected){
                Log.info("[Controller] Connected: @", provider.getName());
                // init virtual cursor to center
                if(virtualX < 0){
                    virtualX = Core.graphics.getWidth() / 2f;
                    virtualY = Core.graphics.getHeight() / 2f;
                }
                cursorX = virtualX;
                cursorY = virtualY;
            }else{
                Log.info("[Controller] Disconnected");
            }
        }

        if(!connected) return;

        // update button states for tap detection fallback
        for(KeyCode code : allControllerKeys){
            boolean down = isPressed(code);
            int idx = code.ordinal();
            buttonPrev[idx] = buttonCurr[idx];
            buttonCurr[idx] = down;
        }

        // update virtual cursor position using right stick + D-pad
        updateVirtualCursor();

        // optional: vibration feedback handled elsewhere
    }

    private static void updateVirtualCursor(){
        if(!virtualCursorEnabled) return;
        float ax = getAxis(KeyCode.controllerRStickXAxis);
        float ay = getAxis(KeyCode.controllerRStickYAxis);
        // also support D-pad for navigation
        float dpadX = 0, dpadY = 0;
        if(isPressed(KeyCode.controllerdPadLeft) || isPressed(KeyCode.buttonL1)) dpadX -= 1;
        if(isPressed(KeyCode.controllerdPadRight) || isPressed(KeyCode.buttonR1)) dpadX += 1;
        // stick raw: up=-1, down=+1, so D-pad should match raw for consistency (up=-1)
        if(isPressed(KeyCode.controllerdPadDown)) dpadY += 1;
        if(isPressed(KeyCode.controllerdPadUp)) dpadY -= 1;

        // also left stick can move cursor when in menu
        float lX = getAxis(KeyCode.controllerLStickXAxis);
        float lY = getAxis(KeyCode.controllerLStickYAxis);

        // blend inputs: right stick primary, D-pad secondary
        float moveX = ax * 1f + dpadX * 0.6f;
        float moveY = ay * 1f + dpadY * 0.6f;

        // when in menu, allow left stick to also move
        if(Vars.state != null && Vars.state.isMenu()){
            moveX += lX * 0.8f;
            moveY += lY * 0.8f;
        }

        float speed = 12f * ControllerConfig.cursorSpeed() * (Core.graphics.getWidth() / 800f);
        speed *= Core.graphics.getDeltaTime() * 60f;

        cursorX += moveX * speed;
        cursorY -= moveY * speed; // invert Y for screen

        cursorX = Mathf.clamp(cursorX, 0, Core.graphics.getWidth());
        cursorY = Mathf.clamp(cursorY, 0, Core.graphics.getHeight());

        virtualX = cursorX;
        virtualY = cursorY;
    }

    public static float getAxis(KeyCode code){
        if(!isEnabled()) return 0f;
        float raw = 0f;
        try{
            raw = provider.getAxis(code);
        }catch(Exception e){
            // fallback to Core.input
            try{ raw = Core.input.axis(code); }catch(Exception ignored){}
        }
        if(Float.isNaN(raw) || Float.isInfinite(raw)) return 0f;

        // handle trigger axes which are 0..1 not -1..1
        boolean isTrigger = code == KeyCode.controllerLTrigger || code == KeyCode.controllerRTrigger;
        if(isTrigger){
            // trigger already 0-1, apply threshold
            float thr = ControllerConfig.triggerThreshold();
            if(raw < thr) return 0f;
            return (raw - thr) / (1f - thr);
        }

        // apply deadzone for sticks
        float dz = ControllerConfig.deadzone();
        if(Math.abs(raw) < dz) return 0f;
        // scale after deadzone
        return Mathf.sign(raw) * (Math.abs(raw) - dz) / (1f - dz) * ControllerConfig.sensitivity();
    }

    /** Gets stick vector with radial deadzone applied, returns true if non-zero */
    public static boolean getStick(KeyCode xAxis, KeyCode yAxis, arc.math.geom.Vec2 out){
        float x = getAxis(xAxis);
        float y = getAxis(yAxis);
        // raw values already deadzoned per-axis, but do radial for better feel
        float dz = ControllerConfig.deadzone();
        float mag = Mathf.sqrt(x*x + y*y);
        // re-apply radial deadzone on combined vector that already had per-axis deadzone
        // Use raw provider values for radial calc to be accurate
        float rawX = provider.getAxis(xAxis);
        float rawY = provider.getAxis(yAxis);
        float rawMag = Mathf.sqrt(rawX*rawX + rawY*rawY);
        if(rawMag < dz){
            out.set(0,0);
            return false;
        }
        float scaled = (rawMag - dz)/(1f - dz) * ControllerConfig.sensitivity();
        float ang = Mathf.atan2(rawY, rawX);
        out.set(Mathf.cos(ang) * scaled, Mathf.sin(ang) * scaled);
        // invert Y if needed (many controllers Y is inverted)
        if(ControllerConfig.invertY()){
            out.y = -out.y;
        }
        return true;
    }

    public static boolean isPressed(KeyCode code){
        if(!isEnabled()) return false;
        try{
            if(provider.isButtonPressed(code)) return true;
        }catch(Exception ignored){}
        // fallback to generic check for both controller and button mappings
        try{
            if(Core.input.keyDown(code)) return true;
        }catch(Exception ignored){}
        // also check alternative mapping (buttonA <-> controllerA, etc)
        KeyCode alt = alternative(code);
        if(alt != null){
            try{
                if(provider.isButtonPressed(alt)) return true;
                if(Core.input.keyDown(alt)) return true;
            }catch(Exception ignored){}
        }
        return false;
    }

    public static boolean isTapped(KeyCode code){
        if(!isEnabled()) return false;
        try{
            if(provider.isButtonTapped(code)) return true;
        }catch(Exception ignored){}
        try{
            if(Core.input.keyTap(code)) return true;
        }catch(Exception ignored){}
        KeyCode alt = alternative(code);
        if(alt != null){
            try{
                if(provider.isButtonTapped(alt)) return true;
                if(Core.input.keyTap(alt)) return true;
            }catch(Exception ignored){}
        }
        // fallback: detect rising edge via our cached states
        int idx = code.ordinal();
        if(idx < buttonCurr.length){
            return buttonCurr[idx] && !buttonPrev[idx];
        }
        return false;
    }

    public static boolean isPressedAny(KeyCode... codes){
        for(KeyCode c : codes) if(isPressed(c)) return true;
        return false;
    }

    public static boolean isTappedAny(KeyCode... codes){
        for(KeyCode c : codes) if(isTapped(c)) return true;
        return false;
    }

    /** Returns alternative mapping for controller buttons (e.g., buttonA <-> controllerA) */
    public static KeyCode alternative(KeyCode code){
        if(code == KeyCode.controllerA) return KeyCode.buttonA;
        if(code == KeyCode.controllerB) return KeyCode.buttonB;
        if(code == KeyCode.controllerX) return KeyCode.buttonX;
        if(code == KeyCode.controllerY) return KeyCode.buttonY;
        if(code == KeyCode.controllerLBumper) return KeyCode.buttonL1;
        if(code == KeyCode.controllerRBumper) return KeyCode.buttonR1;
        if(code == KeyCode.controllerBack) return KeyCode.buttonSelect;
        if(code == KeyCode.controllerStart) return KeyCode.buttonStart;
        if(code == KeyCode.controllerLStick) return KeyCode.buttonThumbL;
        if(code == KeyCode.controllerRStick) return KeyCode.buttonThumbR;
        if(code == KeyCode.buttonA) return KeyCode.controllerA;
        if(code == KeyCode.buttonB) return KeyCode.controllerB;
        if(code == KeyCode.buttonX) return KeyCode.controllerX;
        if(code == KeyCode.buttonY) return KeyCode.controllerY;
        if(code == KeyCode.buttonL1) return KeyCode.controllerLBumper;
        if(code == KeyCode.buttonR1) return KeyCode.controllerRBumper;
        if(code == KeyCode.buttonSelect) return KeyCode.controllerBack;
        if(code == KeyCode.buttonStart) return KeyCode.controllerStart;
        if(code == KeyCode.buttonThumbL) return KeyCode.controllerLStick;
        if(code == KeyCode.buttonThumbR) return KeyCode.controllerRStick;
        return null;
    }

    public static float leftX(){ return getAxis(KeyCode.controllerLStickXAxis); }
    public static float leftY(){ return getAxis(KeyCode.controllerLStickYAxis); }
    public static float rightX(){ return getAxis(KeyCode.controllerRStickXAxis); }
    public static float rightY(){ return getAxis(KeyCode.controllerRStickYAxis); }
    public static float leftTrigger(){ return getAxis(KeyCode.controllerLTrigger); }
    public static float rightTrigger(){ return getAxis(KeyCode.controllerRTrigger); }

    public static boolean leftStickMoving(){
        float x = leftX(), y = leftY();
        return x*x + y*y > 0.01f;
    }

    public static boolean rightStickMoving(){
        float x = rightX(), y = rightY();
        return x*x + y*y > 0.01f;
    }

    /** Vibration helper */
    public static void vibrate(int ms){
        if(!ControllerConfig.vibration()) return;
        try{ provider.vibrate(ms);}catch(Exception ignored){}
        // also try generic vibrate
        try{ Core.input.vibrate(ms);}catch(Exception ignored){}
    }

    private static final KeyCode[] allControllerKeys = {
        KeyCode.controllerA, KeyCode.controllerB, KeyCode.controllerX, KeyCode.controllerY,
        KeyCode.controllerLBumper, KeyCode.controllerRBumper, KeyCode.controllerBack, KeyCode.controllerStart,
        KeyCode.controllerLStick, KeyCode.controllerRStick,
        KeyCode.controllerdPadUp, KeyCode.controllerdPadDown, KeyCode.controllerdPadLeft, KeyCode.controllerdPadRight,
        KeyCode.controllerLTrigger, KeyCode.controllerRTrigger,
        KeyCode.buttonA, KeyCode.buttonB, KeyCode.buttonX, KeyCode.buttonY,
        KeyCode.buttonL1, KeyCode.buttonR1, KeyCode.buttonL2, KeyCode.buttonR2,
        KeyCode.buttonStart, KeyCode.buttonSelect, KeyCode.buttonMode, KeyCode.buttonThumbL, KeyCode.buttonThumbR,
        KeyCode.back, KeyCode.escape, KeyCode.enter, KeyCode.space
    };
}
