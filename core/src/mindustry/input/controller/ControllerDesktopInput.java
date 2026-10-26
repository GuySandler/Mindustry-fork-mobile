package mindustry.input.controller;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.entities.units.BuildPlan;
import mindustry.world.Tile;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Desktop controller input (keyboard mode + controller).
 */
public class ControllerDesktopInput extends DesktopInput{
    public float ctrlMouseX = -1, ctrlMouseY = -1;
    private float aimX, aimY;
    private boolean wasShooting = false;
    private final Vec2 tmp = new Vec2();

    @Override
    public void update(){
        super.update();
        ControllerSupport.update();

        if(ctrlMouseX < 0){
            ctrlMouseX = graphics.getWidth() / 2f;
            ctrlMouseY = graphics.getHeight() / 2f;
        }

        if(!ControllerSupport.isEnabled() || !ControllerSupport.isConnected()) return;
        if(state.isMenu() || player.dead() || locked()) return;

        // left stick movement (also handled by super, but we add override) - respect invertY
        float lx = ControllerSupport.leftX();
        float ly = ControllerSupport.leftY();
        if(!ControllerConfig.invertY()) ly = -ly;
        float dpadUp = ControllerSupport.isPressed(KeyCode.controllerdPadUp) ? 1f : 0;
        float dpadDown = ControllerSupport.isPressed(KeyCode.controllerdPadDown) ? -1f : 0;
        float dpadLeft = ControllerSupport.isPressed(KeyCode.controllerdPadLeft) ? -1f : 0;
        float dpadRight = ControllerSupport.isPressed(KeyCode.controllerdPadRight) ? 1f : 0;
        if(ControllerConfig.invertY()){ dpadUp = -dpadUp; dpadDown = -dpadDown; }
        ly += dpadUp + dpadDown;
        lx += dpadLeft + dpadRight;
        lx = Mathf.clamp(lx, -1, 1);
        ly = Mathf.clamp(ly, -1, 1);
        if(Math.abs(lx) > 0.01f || Math.abs(ly) > 0.01f){
            Unit unit = player.unit();
            if(unit != null){
                float speed = unit.speed();
                tmp.set(lx, ly).nor().scl(Mathf.sqrt(lx*lx+ly*ly) * speed);
                unit.movePref(tmp);
                panning = false;
                spectating = null;
            }
        }

        // right stick aim / cursor - respect invertY
        float rx = ControllerSupport.rightX();
        float ry = ControllerSupport.rightY();
        if(!ControllerConfig.invertY()) ry = -ry;
        boolean hasAim = Math.abs(rx) > 0.05f || Math.abs(ry) > 0.05f;
        boolean trigger = ControllerSupport.rightTrigger() > 0.2f || ControllerSupport.isPressed(KeyCode.controllerA);
        Unit unit = player.unit();
        if(unit != null){
            if(hasAim){
                float range = unit.range() * 0.9f;
                float tx = player.x + rx * range;
                float ty = player.y + ry * range;
                if(aimX == 0) { aimX = tx; aimY = ty; }
                aimX = Mathf.lerpDelta(aimX, tx, 0.2f);
                aimY = Mathf.lerpDelta(aimY, ty, 0.2f);
                player.mouseX = aimX;
                player.mouseY = aimY;
                unit.aim(player.mouseX, player.mouseY);
                if(trigger || Mathf.len(rx, ry) > 0.7f){
                    player.shooting = true;
                    unit.controlWeapons(true, true);
                    wasShooting = true;
                }
            }else if(trigger){
                player.shooting = true;
                unit.controlWeapons(true, true);
                wasShooting = true;
            }else if(wasShooting){
                player.shooting = false;
                wasShooting = false;
            }
            // move virtual cursor with right stick when not aiming? share same stick, so cursor moves a bit
            if(Math.abs(rx) > 0.02f || Math.abs(ry) > 0.02f){
                float speed = 7f * ControllerConfig.cursorSpeed() * (camera.width / graphics.getWidth());
                ctrlMouseX += rx * speed * Time.delta * 60f;
                ctrlMouseY += ry * speed * Time.delta * 60f;
                ctrlMouseX = Mathf.clamp(ctrlMouseX, 0, graphics.getWidth());
                ctrlMouseY = Mathf.clamp(ctrlMouseY, 0, graphics.getHeight());
            }
            // A to place
            if(ControllerSupport.isTapped(KeyCode.controllerA) || ControllerSupport.isTapped(KeyCode.buttonA)){
                int tx = tileX(ctrlMouseX);
                int ty = tileY(ctrlMouseY);
                Tile tile = world.tile(tx, ty);
                if(tile != null && block != null && validPlace(tx, ty, block, rotation)){
                    BuildPlan plan = new BuildPlan(tx, ty, rotation, block);
                    player.unit().addBuild(plan);
                }else if(tile != null){
                    Tile linked = tile.build == null ? tile : tile.build.tile;
                    if(linked.build != null && linked.build.interactable(player.team())) linked.build.tapped();
                }
            }
            if(ControllerSupport.isTapped(KeyCode.controllerB) || ControllerSupport.isTapped(KeyCode.buttonB)){
                int tx = tileX(ctrlMouseX);
                int ty = tileY(ctrlMouseY);
                Tile tile = world.tile(tx, ty);
                if(tile != null){
                    Tile linked = tile.build == null ? tile : tile.build.tile;
                    if(validBreak(linked.x, linked.y)){
                        player.unit().addBuild(new BuildPlan(linked.x, linked.y));
                    }
                }
            }
            if(ControllerSupport.isTapped(KeyCode.controllerRBumper) || ControllerSupport.isTapped(KeyCode.buttonR1)){
                renderer.scaleCamera(-0.07f);
            }
            if(ControllerSupport.isTapped(KeyCode.controllerLBumper) || ControllerSupport.isTapped(KeyCode.buttonL1)){
                renderer.scaleCamera(0.07f);
            }
            if(ControllerSupport.isTapped(KeyCode.controllerStart) || ControllerSupport.isTapped(KeyCode.buttonStart)){
                if(!ui.paused.isShown() && !scene.hasDialog()){
                    ui.paused.show();
                    if(!net.active() && !state.rules.pauseDisabled) state.set(GameState.State.paused);
                }else if(ui.paused.isShown()){
                    ui.paused.hide();
                    if(state.is(GameState.State.paused)) state.set(GameState.State.playing);
                }
            }
        }
    }
}
