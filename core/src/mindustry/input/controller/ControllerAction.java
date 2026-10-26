package mindustry.input.controller;

import arc.input.KeyCode;

/**
 * Enumerates high-level controller actions that can be bound to physical buttons.
 * Each action can map to one or two KeyCodes (primary + alternative).
 */
public enum ControllerAction{
    moveLeft(KeyCode.controllerLStickXAxis, true, false),
    moveRight(KeyCode.controllerLStickXAxis, true, true),
    moveUp(KeyCode.controllerLStickYAxis, false, true),
    moveDown(KeyCode.controllerLStickYAxis, false, false),
    aimX(KeyCode.controllerRStickXAxis),
    aimY(KeyCode.controllerRStickYAxis),
    shoot(KeyCode.controllerRTrigger),
    altShoot(KeyCode.controllerLTrigger),
    boost(KeyCode.controllerLBumper),
    buildSelect(KeyCode.controllerA),
    breakBlock(KeyCode.controllerB),
    pickup(KeyCode.controllerX),
    drop(KeyCode.controllerY),
    shootConfirm(KeyCode.controllerA),
    back(KeyCode.controllerB),
    pause(KeyCode.controllerStart),
    map(KeyCode.controllerBack),
    zoomIn(KeyCode.controllerRBumper),
    zoomOut(KeyCode.controllerLBumper),
    rotate(KeyCode.controllerRStick),
    control(KeyCode.controllerLStick),
    commandMode(KeyCode.controllerY),
    dpadUp(KeyCode.controllerdPadUp),
    dpadDown(KeyCode.controllerdPadDown),
    dpadLeft(KeyCode.controllerdPadLeft),
    dpadRight(KeyCode.controllerdPadRight);

    public final KeyCode defaultKey;
    public final boolean isAxis;
    public final boolean axisPositive;
    private KeyCode boundKey;

    ControllerAction(KeyCode def){
        this(def, false, false);
        boundKey = def;
    }

    ControllerAction(KeyCode def, boolean isAxis, boolean positive){
        this.defaultKey = def;
        this.isAxis = isAxis;
        this.axisPositive = positive;
        this.boundKey = def;
    }

    public KeyCode getBound(){
        return boundKey;
    }

    public void setBound(KeyCode key){
        boundKey = key;
    }

    public static ControllerAction fromKey(KeyCode code){
        for(ControllerAction a : values()){
            if(a.defaultKey == code) return a;
        }
        return null;
    }
}
