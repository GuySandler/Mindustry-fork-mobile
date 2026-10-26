package mindustry.input.controller;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import mindustry.graphics.Drawf;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.Scene;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Slider;
import arc.scene.ui.TextField;
import arc.scene.ui.TextArea;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.gen.Unitc;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.input.MobileInput;
import mindustry.input.PlaceMode;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Controller-capable mobile input. Adds left/right stick movement, aiming, building cursor,
 * and UI navigation on top of standard MobileInput touch handling.
 */
public class ControllerInput extends MobileInput{
    public float ctrlMouseX = -1, ctrlMouseY = -1;
    private float aimX, aimY;
    private boolean wasShooting = false;
    private boolean linePlacing = false;
    private int lineStartX = -1, lineStartY = -1;
    private final Vec2 vec = new Vec2();
    private float uiCooldown = 0f;

    @Override
    public void update(){
        super.update();
        ControllerSupport.update();

        if(ctrlMouseX < 0){
            ctrlMouseX = graphics.getWidth() / 2f;
            ctrlMouseY = graphics.getHeight() / 2f;
        }

        if(!ControllerSupport.isEnabled() || !ControllerSupport.isConnected()){
            return;
        }

        // clamp
        ctrlMouseX = Mathf.clamp(ctrlMouseX, 0, graphics.getWidth());
        ctrlMouseY = Mathf.clamp(ctrlMouseY, 0, graphics.getHeight());

        boolean inGame = state.isGame() && !player.dead() && !state.isPaused();
        boolean locked = locked();

        // UI navigation always
        updateUINavigation();

        if(!inGame || locked) return;

        updateMovement();
        updateAiming();
        updateBuilding();
        updateMisc();
        updateVirtualMouse();
    }

    private void updateMovement(){
        float lx = ControllerSupport.leftX();
        float ly = ControllerSupport.leftY();
        // raw up=-1 -> world up=+1, so invert unless user wants inverted
        if(!ControllerConfig.invertY()) ly = -ly;
        // D-pad: up should be +1 world up (invert similarly)
        float dpadUp = ControllerSupport.isPressed(KeyCode.controllerdPadUp) ? 1f : 0;
        float dpadDown = ControllerSupport.isPressed(KeyCode.controllerdPadDown) ? -1f : 0;
        float dpadLeft = ControllerSupport.isPressed(KeyCode.controllerdPadLeft) ? -1f : 0;
        float dpadRight = ControllerSupport.isPressed(KeyCode.controllerdPadRight) ? 1f : 0;
        if(ControllerConfig.invertY()){ dpadUp = -dpadUp; dpadDown = -dpadDown; }
        ly += dpadUp + dpadDown;
        lx += dpadLeft + dpadRight;

        lx = Mathf.clamp(lx, -1f, 1f);
        ly = Mathf.clamp(ly, -1f, 1f);

        if(Math.abs(lx) > 0.01f || Math.abs(ly) > 0.01f){
            Unit unit = player.unit();
            if(unit == null) return;
            float speed = unit.speed();
            float len = Mathf.sqrt(lx*lx + ly*ly);
            if(len > 1){ lx /= len; ly /= len; len = 1; }
            vec.set(lx, ly).nor().scl(len * speed);

            if(settings.getBool("detach-camera", false)){
                camera.position.add(vec.scl(6f / speed));
            }else{
                unit.movePref(vec);
            }
            spectating = null;
        }
    }

    private void updateAiming(){
        if(player.dead()) return;
        Unit unit = player.unit();
        if(unit == null) return;

        float rx = ControllerSupport.rightX();
        float ry = ControllerSupport.rightY();
        ry = -ry;

        boolean hasAim = Math.abs(rx) > 0.05f || Math.abs(ry) > 0.05f;
        boolean trigger = ControllerSupport.rightTrigger() > 0.2f || ControllerSupport.isPressed(KeyCode.controllerA) || ControllerSupport.isPressed(KeyCode.buttonA);

        if(hasAim){
            float range = unit.range() * 0.9f;
            float tx = player.x + rx * range;
            float ty = player.y + ry * range;
            if(aimX == 0 && aimY == 0){ aimX = tx; aimY = ty; }
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
            // shoot without aim direction: keep last aim
            player.shooting = true;
            unit.aim(player.mouseX, player.mouseY);
            unit.controlWeapons(true, true);
            wasShooting = true;
        }else if(wasShooting){
            player.shooting = false;
            wasShooting = false;
        }

        // X to pickup / control
        if(ControllerSupport.isTapped(KeyCode.controllerX) || ControllerSupport.isTapped(KeyCode.buttonX)){
            Tile cur = tileAt(ctrlMouseX, ctrlMouseY);
            if(cur != null){
                if(player.unit() instanceof mindustry.gen.Payloadc){
                    tryPickupPayload();
                }else{
                    Building b = cur.build;
                    Unit u = Units.closest(player.team(), cur.worldx(), cur.worldy(), 10f, e -> e.isAI() && e.team == player.team() && !e.dead && e.playerControllable());
                    if(u != null && state.rules.possessionAllowed){
                        Call.unitControl(player, u);
                        recentRespawnTimer = 1f;
                        ControllerSupport.vibrate(30);
                    }else if(b != null && b.team == player.team() && state.rules.possessionAllowed){
                        Call.buildingControlSelect(player, b);
                        recentRespawnTimer = 1f;
                    }
                }
            }
        }
        if(ControllerSupport.isTapped(KeyCode.controllerY) || ControllerSupport.isTapped(KeyCode.buttonY)){
            Unit u = player.unit();
            if(u instanceof mindustry.gen.Payloadc p && p.hasPayload()){
                tryDropPayload();
            }else if(player.dead()){
                Call.unitClear(player);
                recentRespawnTimer = 1f;
            }
        }
    }

    private void updateBuilding(){
        if(player.dead()) return;

        // move virtual cursor with right stick (bottom-origin: up=+Y)
        float rx = ControllerSupport.rightX();
        float ry = ControllerSupport.rightY();
        if(!ControllerConfig.invertY()) ry = -ry;
        if(Math.abs(rx) > 0.02f || Math.abs(ry) > 0.02f){
            float speed = 7f * ControllerConfig.cursorSpeed() * (camera.width / graphics.getWidth());
            ctrlMouseX += rx * speed * Time.delta * 60f;
            ctrlMouseY += ry * speed * Time.delta * 60f;
            ctrlMouseX = Mathf.clamp(ctrlMouseX, 0, graphics.getWidth());
            ctrlMouseY = Mathf.clamp(ctrlMouseY, 0, graphics.getHeight());
        }
        // D-pad nudge (respect invertY)
        float nudge = 2f * Time.delta * 60f;
        float inv = ControllerConfig.invertY() ? -1 : 1;
        if(ControllerSupport.isPressed(KeyCode.controllerdPadUp)) ctrlMouseY += nudge * inv;
        if(ControllerSupport.isPressed(KeyCode.controllerdPadDown)) ctrlMouseY -= nudge * inv;
        if(ControllerSupport.isPressed(KeyCode.controllerdPadLeft)) ctrlMouseX -= nudge;
        if(ControllerSupport.isPressed(KeyCode.controllerdPadRight)) ctrlMouseX += nudge;

        // zoom with bumpers
        if(ControllerSupport.isTapped(KeyCode.controllerRBumper) || ControllerSupport.isTapped(KeyCode.buttonR1)){
            renderer.scaleCamera(-0.07f);
        }
        if(ControllerSupport.isTapped(KeyCode.controllerLBumper) || ControllerSupport.isTapped(KeyCode.buttonL1)){
            renderer.scaleCamera(0.07f);
        }
        // rotate with R3
        if(ControllerSupport.isTapped(KeyCode.controllerRStick) || ControllerSupport.isTapped(KeyCode.buttonThumbR)){
            if(block != null && block.rotate){
                rotation = Mathf.mod(rotation + 1, 4);
                ControllerSupport.vibrate(12);
            }
        }

        // place with A / RT
        boolean placeTap = ControllerSupport.isTapped(KeyCode.controllerA) || ControllerSupport.isTapped(KeyCode.buttonA) || (ControllerSupport.rightTrigger() > 0.5f && !wasShooting);
        boolean breakTap = ControllerSupport.isTapped(KeyCode.controllerB) || ControllerSupport.isTapped(KeyCode.buttonB) || ControllerSupport.leftTrigger() > 0.5f;

        if(placeTap){
            int tx = tileX(ctrlMouseX);
            int ty = tileY(ctrlMouseY);
            Tile tile = world.tile(tx, ty);
            if(tile != null){
                Tile linked = tile.build == null ? tile : tile.build.tile;
                // if has plan, remove it
                boolean has = false;
                for(var p : selectPlans){
                    if(p.x == linked.x && p.y == linked.y){ has = true; break; }
                }
                if(has){
                    // remove matching plan
                    selectPlans.remove(p -> p.x == linked.x && p.y == linked.y);
                    ControllerSupport.vibrate(15);
                }else if(block != null){
                    // try place
                    if(validPlace(tx, ty, block, rotation)){
                        selectPlans.add(new BuildPlan(tx, ty, rotation, block, block.nextConfig()));
                        // auto flush for controller: immediately add to player queue
                        flushSinglePlan(tx, ty);
                        ControllerSupport.vibrate(10);
                    }
                }else{
                    // tap building - use public method via input handler
                    // building tap fallback: try to interact manually
                    if(linked.build != null && linked.build.interactable(player.team())){
                        linked.build.tapped();
                    }
                }
                // track line start
                if(lineStartX == -1){
                    lineStartX = tx; lineStartY = ty;
                }
            }
        }

        if(breakTap){
            int tx = tileX(ctrlMouseX);
            int ty = tileY(ctrlMouseY);
            Tile tile = world.tile(tx, ty);
            if(tile != null){
                Tile linked = tile.build == null ? tile : tile.build.tile;
                if(validBreak(linked.x, linked.y)){
                    selectPlans.add(new BuildPlan(linked.x, linked.y));
                    flushSinglePlan(linked.x, linked.y);
                    ControllerSupport.vibrate(15);
                }
            }
        }

        // confirm queue with Y
        if(ControllerSupport.isTapped(KeyCode.controllerY) || ControllerSupport.isTapped(KeyCode.buttonY)){
            if(!selectPlans.isEmpty()){
                for(var plan : selectPlans.copy()){
                    Tile t = world.tile(plan.x, plan.y);
                    if(t != null){
                        if(!plan.breaking){
                            if(validPlace(plan.x, plan.y, plan.block, plan.rotation)){
                                player.unit().addBuild(plan.copy());
                            }
                        }else{
                            tryBreakBlock(t.x, t.y);
                        }
                    }
                }
                selectPlans.clear();
                ControllerSupport.vibrate(20);
            }
        }

        // cancel with Back
        if(ControllerSupport.isTapped(KeyCode.controllerBack) || ControllerSupport.isTapped(KeyCode.buttonSelect)){
            if(!selectPlans.isEmpty()){
                selectPlans.clear();
                block = null;
            }else if(!player.dead()){
                player.unit().clearBuilding();
            }
        }
    }

    private void flushSinglePlan(int x, int y){
        // find plan just added and add to player
        for(int i = selectPlans.size - 1; i >= 0; i--){
            var p = selectPlans.get(i);
            if(p.x == x && p.y == y){
                if(!p.breaking){
                    player.unit().addBuild(p);
                }else{
                    tryBreakBlock(p.x, p.y);
                }
                selectPlans.remove(i);
                break;
            }
        }
    }

    private void updateMisc(){
        if(ControllerSupport.isTapped(KeyCode.controllerStart) || ControllerSupport.isTapped(KeyCode.buttonStart)){
            if(state.isGame()){
                if(!ui.paused.isShown() && !scene.hasDialog()){
                    ui.paused.show();
                    if(!net.active() && !state.rules.pauseDisabled) state.set(GameState.State.paused);
                }else if(ui.paused.isShown()){
                    ui.paused.hide();
                    if(state.is(GameState.State.paused)) state.set(GameState.State.playing);
                }
                ControllerSupport.vibrate(15);
            }
        }
        if(ControllerSupport.isTapped(KeyCode.controllerBack) && state.isGame() && !scene.hasDialog()){
            // short press handled in building cancel, long? use tap again for minimap
            // we need to differentiate: if selectPlans empty, treat as minimap toggle
            if(selectPlans.isEmpty()){
                // delay to avoid immediate after cancel?
            }
        }
        // map with back + start combo?
    }

    private void updateVirtualMouse(){
        // optional: synthesize mouse moved for hover effects
        // Not needed for now
    }

    private void updateUINavigation(){
        uiCooldown -= Time.delta;
        if(uiCooldown > 0) return;

        Scene scene = Core.scene;
        boolean up = ControllerSupport.isPressed(KeyCode.controllerdPadUp) || ControllerSupport.getAxis(KeyCode.controllerLStickYAxis) > 0.6f;
        boolean down = ControllerSupport.isPressed(KeyCode.controllerdPadDown) || ControllerSupport.getAxis(KeyCode.controllerLStickYAxis) < -0.6f;
        boolean left = ControllerSupport.isPressed(KeyCode.controllerdPadLeft) || ControllerSupport.getAxis(KeyCode.controllerLStickXAxis) < -0.6f;
        boolean right = ControllerSupport.isPressed(KeyCode.controllerdPadRight) || ControllerSupport.getAxis(KeyCode.controllerLStickXAxis) > 0.6f;

        if(up || down || left || right){
            Element cur = scene.getKeyboardFocus();
            Element next = findNext(cur, down || right);
            if(next != null){
                scene.setKeyboardFocus(next);
                uiCooldown = 11f;
                ControllerSupport.vibrate(8);
            }else if(cur == null){
                Seq<Element> all = collectFocusable(scene.root);
                if(all.size > 0){
                    scene.setKeyboardFocus(all.first());
                    uiCooldown = 11f;
                }
            }
        }

        if(ControllerSupport.isTapped(KeyCode.controllerA) || ControllerSupport.isTapped(KeyCode.buttonA)){
            Element focus = scene.getKeyboardFocus();
            if(focus != null){
                focus.fireClick();
                uiCooldown = 8f;
                ControllerSupport.vibrate(12);
            }
        }
        if(ControllerSupport.isTapped(KeyCode.controllerB) || ControllerSupport.isTapped(KeyCode.buttonB)){
            if(ControllerNavigator.topDialog() != null){
                ControllerNavigator.topDialog().hide();
                uiCooldown = 10f;
                ControllerSupport.vibrate(10);
            }
        }
    }

    private Element findNext(Element cur, boolean forward){
        Seq<Element> all = collectFocusable(scene.root);
        if(all.isEmpty()) return null;
        if(cur == null) return all.first();
        int idx = all.indexOf(cur);
        if(idx == -1) return all.first();
        int next = forward ? (idx + 1) % all.size : (idx - 1 + all.size) % all.size;
        return all.get(next);
    }

    private Seq<Element> collectFocusable(Group root){
        Seq<Element> out = new Seq<>();
        collect(root, out);
        return out;
    }

    private void collect(Group group, Seq<Element> out){
        for(Element e : group.getChildren()){
            if(!e.visible) continue;
            boolean focusable = (e instanceof Button) || (e instanceof TextField) || (e instanceof Slider) || (e instanceof CheckBox) || (e instanceof TextArea);
            if(focusable && e.touchable == Touchable.enabled){
                out.add(e);
            }
            if(e instanceof Group g) collect(g, out);
        }
    }

    @Override
    public void drawTop(){
        super.drawTop();
        if(!ControllerSupport.isEnabled() || !ControllerSupport.isConnected()) return;
        if(!Core.settings.getBool("controller.showCursor", true)) return;
        if(!state.isGame()) return;

        float worldX = Core.input.mouseWorld(ctrlMouseX, ctrlMouseY).x;
        float worldY = Core.input.mouseWorld(ctrlMouseX, ctrlMouseY).y;
        Draw.z(Layer.overlayUI);
        Lines.stroke(1.5f);
        Draw.color(Pal.accent, 0.9f);
        Lines.circle(worldX, worldY, 6f);
        Draw.color(Pal.accent, 0.35f);
        Lines.circle(worldX, worldY, 3f);
        Draw.reset();
        if(player.shooting){
            Drawf.target(player.mouseX, player.mouseY, 5f, Pal.remove);
        }
    }
}
