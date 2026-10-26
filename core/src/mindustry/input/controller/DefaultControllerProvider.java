package mindustry.input.controller;

import arc.Core;
import arc.input.KeyCode;

/**
 * Default provider that uses Arc's Core.input to poll controller axes/buttons.
 * Works on desktop where Arc backend supports controllers. On Android, this will fallback to no controller
 * unless AndroidControllerSupport sets a custom provider.
 */
public class DefaultControllerProvider implements ControllerProvider{

    @Override
    public boolean isConnected(){
        // check if any controller button/axis is available by polling a known controller axis
        // Heuristic: if any controller key is down or axis non-zero, consider connected
        // Better: check Core.input.getAxis for controller axes non-zero
        // For now, rely on Core.input.keyDown detection across known controller buttons
        for(KeyCode key : controllerButtons){
            if(Core.input.keyDown(key)) return true;
        }
        // also check axes
        for(KeyCode axis : controllerAxes){
            float v = Core.input.axis(axis);
            if(Math.abs(v) > 0.2f) return true;
        }
        return false;
    }

    @Override
    public boolean isEnabled(){
        return ControllerConfig.enabled();
    }

    @Override
    public float getAxis(KeyCode axis){
        if(!axis.axis) return 0f;
        try{
            return Core.input.axis(axis);
        }catch(Exception e){
            return 0f;
        }
    }

    @Override
    public boolean isButtonPressed(KeyCode button){
        return Core.input.keyDown(button);
    }

    @Override
    public boolean isButtonTapped(KeyCode button){
        return Core.input.keyTap(button);
    }

    private static final KeyCode[] controllerButtons = {
        KeyCode.controllerA, KeyCode.controllerB, KeyCode.controllerX, KeyCode.controllerY,
        KeyCode.controllerLBumper, KeyCode.controllerRBumper, KeyCode.controllerBack, KeyCode.controllerStart,
        KeyCode.controllerLStick, KeyCode.controllerRStick,
        KeyCode.controllerdPadUp, KeyCode.controllerdPadDown, KeyCode.controllerdPadLeft, KeyCode.controllerdPadRight,
        KeyCode.buttonA, KeyCode.buttonB, KeyCode.buttonX, KeyCode.buttonY,
        KeyCode.buttonL1, KeyCode.buttonR1, KeyCode.buttonL2, KeyCode.buttonR2,
        KeyCode.buttonStart, KeyCode.buttonSelect, KeyCode.buttonMode, KeyCode.buttonThumbL, KeyCode.buttonThumbR
    };

    private static final KeyCode[] controllerAxes = {
        KeyCode.controllerLStickXAxis, KeyCode.controllerLStickYAxis,
        KeyCode.controllerRStickXAxis, KeyCode.controllerRStickYAxis,
        KeyCode.controllerLTrigger, KeyCode.controllerRTrigger
    };
}
