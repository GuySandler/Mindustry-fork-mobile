package mindustry.input.controller;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.Scene;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.graphics.Pal;

import static arc.Core.*;

/**
 * Handles UI navigation with controller for whole app.
 * Provides focus cycling + virtual cursor + visual indicators.
 */
public class ControllerNavigator{
    // All delays are in milliseconds (NOT frames): frame-based delays made repeat speed depend on
    // the device frame rate, which made one d-pad tap move several rows on high-FPS devices and
    // made slider steps apply twice.
    private static final float INITIAL_DELAY = 260f, REPEAT_DELAY = 130f, FAST_REPEAT_DELAY = 60f;
    private static final float ACTION_DELAY = 180f, SLIDER_DELAY = 200f;
    private static float cooldown = 0f;
    private static long lastTickMs = 0;
    private static float repeatDelay = INITIAL_DELAY;
    private static boolean wasDirDown = false;
    private static float holdTime = 0f;
    private static float cursorMoveTime = 0f;
    private static Element lastFocus = null;
    private static final Vec2 tmp = new Vec2();
    // remember last focus per container for back navigation
    private static final arc.struct.ObjectMap<Group, Element> lastFocusMap = new arc.struct.ObjectMap<>();
    private static Group lastContext = null;
    private static arc.scene.ui.Dialog lastTopDialog = null;

    // visual overlay
    private static boolean overlayAdded = false;
    // debug logging
    public static boolean debug = false;

    /**
     * Topmost dialog that is currently on screen, or null.
     * Neither Scene.hasDialog() nor Scene.getDialog() can be used for this: hasDialog() only
     * reports whether the *focus* is inside a dialog, and getDialog() returns a dialog only when
     * the keyboard/scroll focus is the Dialog object itself (it returns null as soon as focus
     * moves to a button inside the dialog). Dialogs are added directly to scene.root, so the
     * last shown Dialog child wins.
     */
    public static arc.scene.ui.Dialog topDialog(){
        if(scene == null || scene.root == null) return null;
        Seq<Element> children = scene.root.getChildren();
        for(int i = children.size - 1; i >= 0; i--){
            Element e = children.get(i);
            if(e instanceof arc.scene.ui.Dialog d && d.isShown() && d.parent != null && d.getScene() != null){
                return d;
            }
        }
        return null;
    }

    /** Content of the topmost dialog, or the scene root when no dialog is open. */
    private static Group dialogContext(){
        arc.scene.ui.Dialog d = topDialog();
        // NOTE: use the dialog itself, not dialog.cont - some dialogs (SettingsMenuDialog)
        // remove their own `cont` from the tree and add custom children instead.
        return d != null ? d : scene.root;
    }

    private static void saveFocus(Group context){
        Element cur = scene.getKeyboardFocus();
        if(cur != null && context != null){
            lastFocusMap.put(context, cur);
            lastContext = context;
        }
    }

    private static void restoreFocus(Group context){
        if(context == null) context = lastContext;
        if(context == null) return;
        Element saved = lastFocusMap.get(context);
        if(saved != null && saved.visible && saved.parent != null){
            scene.setKeyboardFocus(saved);
        }else{
            Seq<Element> all = collectFocusable(context);
            if(all.size > 0) scene.setKeyboardFocus(all.first());
        }
    }

    public static void initOverlay(){
        if(overlayAdded) return;
        try{
            Group root = scene.root;
            if(root == null) return;
            // Add overlay group that draws focus highlight and cursor
            Group overlay = new Group(){
                {
                    setFillParent(true);
                    touchable = Touchable.disabled;
                    update(() -> toFront());
                }
                @Override
                public void draw(){
                    super.draw();
                    drawOverlay();
                }
            };
            overlay.name = "controller-overlay";
            root.addChild(overlay);
            overlayAdded = true;
        }catch(Exception ignored){}
    }

    private static void drawOverlay(){
        if(!ControllerSupport.isEnabled() || !ControllerSupport.isConnected()) return;
        // ensure overlay exists
        if(!overlayAdded) initOverlay();

        // draw focus highlight
        Element focus = scene.getKeyboardFocus();
        if(focus != null && focus.visible && focus.parent != null){
            // draw pulsing rectangle around focused element
            try{
                Vec2 pos = focus.localToStageCoordinates(new Vec2(0,0));
                float x = pos.x;
                float y = pos.y;
                float w = focus.getWidth();
                float h = focus.getHeight();
                // handle stage coords: scene coordinates are same as stage?
                // Use stageToScreen? For now use element's position in stage
                // Try to use getX/Y in stage: need to compute via parent transforms
                // Simpler: use element's position via localToStage
                Draw.z(9999);
                Lines.stroke(2.5f, Pal.accent);
                // pulsing alpha
                float pulse = 0.9f + Mathf.absin(Time.globalTime * 0.15f, 0.3f);
                Draw.alpha(pulse);
                Lines.rect(x - 3, y - 3, w + 6, h + 6);
                // inner accent
                Lines.stroke(1f, Color.white);
                Draw.alpha(0.4f * pulse);
                Lines.rect(x - 1, y - 1, w + 2, h + 2);
                Draw.reset();
            }catch(Exception ignored){}
        }

        // draw virtual cursor - only in-game (outline is better for menus)
        if(Core.settings.getBool("controller.showCursor", true) && Vars.state != null && Vars.state.isGame()){
            float vx = ControllerSupport.virtualX;
            float vy = ControllerSupport.virtualY;
            if(vx >= 0 && vy >= 0){
                Draw.z(10000);
                float size = 10f;
                Lines.stroke(2f, Pal.accent);
                Lines.circle(vx, vy, size);
                Draw.color(Pal.accent);
                Draw.alpha(0.9f);
                Fill.circle(vx, vy, 3f);
                Lines.stroke(1f, Color.white);
                Draw.alpha(0.6f);
                Lines.line(vx - size, vy, vx + size, vy);
                Lines.line(vx, vy - size, vx, vy + size);
                Draw.reset();
            }
        }
    }

    public static void update(){
        if(!ControllerSupport.isEnabled()) return;
        if(!ControllerSupport.isConnected()){
            // clear focus if disconnected?
            return;
        }
        Scene scene = Core.scene;
        if(scene == null) return;

        // ensure overlay
        if(!overlayAdded) initOverlay();

        // keep keyboard focus inside the topmost dialog. Without this, opening a dialog leaves
        // focus on the button that opened it (behind the dialog), so A re-fires that button and
        // the direction keys start from an element outside the dialog.
        arc.scene.ui.Dialog top = topDialog();
        if(top != null){
            Element cur = scene.getKeyboardFocus();
            if(cur == null || cur == top || !isInside(cur, top)){
                Seq<Element> first = collectFocusable(top);
                if(!first.isEmpty()) scene.setKeyboardFocus(first.first());
            }
        }else if(lastTopDialog != null){
            restoreFocus(scene.root);
        }
        lastTopDialog = top;

        long nowMs = Time.millis();
        if(lastTickMs == 0) lastTickMs = nowMs;
        cooldown -= Math.min(nowMs - lastTickMs, 250f); // clamp so a stalled frame can't skip the delay
        lastTickMs = nowMs;

        // update virtual cursor move tracking
        float prevX = ControllerSupport.virtualX;
        float prevY = ControllerSupport.virtualY;
        // ControllerSupport already updates virtual cursor based on sticks
        // Detect movement
        if(Math.abs(ControllerSupport.getAxis(KeyCode.controllerLStickXAxis)) > 0.2f ||
           Math.abs(ControllerSupport.getAxis(KeyCode.controllerLStickYAxis)) > 0.2f ||
           Math.abs(ControllerSupport.getAxis(KeyCode.controllerRStickXAxis)) > 0.2f ||
           Math.abs(ControllerSupport.getAxis(KeyCode.controllerRStickYAxis)) > 0.2f ||
           ControllerSupport.isPressed(KeyCode.controllerdPadUp) || ControllerSupport.isPressed(KeyCode.controllerdPadDown) ||
           ControllerSupport.isPressed(KeyCode.controllerdPadLeft) || ControllerSupport.isPressed(KeyCode.controllerdPadRight)){
            cursorMoveTime = Time.time;
        }

        // --- Confirm (A) - prioritize focus in menu, cursor in game ---
        // NOTE: the cooldown gate is essential - isTapped() stays true for every frame between
        // the key-down and key-up events, so without it one press fires several clicks.
        if(cooldown <= 0 && (ControllerSupport.isTapped(KeyCode.controllerA) || ControllerSupport.isTapped(KeyCode.buttonA) ||
           ControllerSupport.isTapped(KeyCode.controllerRTrigger) || ControllerSupport.isTapped(KeyCode.enter))){
            // In menu, always use focused element (more reliable than cursor)
            Element focus = scene.getKeyboardFocus();
            if(focus != null && focus.visible && focus.touchable == Touchable.enabled && isClickable(focus)){
                if(debug) arc.util.Log.info("[CtrlNav] A: click @ at y=@", focus.getClass().getSimpleName(), focus.y);
                rememberCurrentFocus();
                focus.fireClick();
                ControllerSupport.vibrate(15);
                cooldown = ACTION_DELAY;
                return;
            }
            // If no focus, try cursor hit (for free-form UI)
            if(ControllerSupport.virtualX >= 0 && Vars.state != null && Vars.state.isGame()){
                // virtual coords are stage bottom-origin; hit expects same
                Element hit = scene.hit(ControllerSupport.virtualX, ControllerSupport.virtualY, true);
                if(hit != null){
                    Element clickable = findClickable(hit);
                    if(clickable != null){
                        clickable.fireClick();
                        scene.setKeyboardFocus(clickable);
                        ControllerSupport.vibrate(15);
                        cooldown = ACTION_DELAY;
                        return;
                    }
                }
            }
        }

        // --- Back (B) - only one level ---
        if((ControllerSupport.isTapped(KeyCode.controllerB) || ControllerSupport.isTapped(KeyCode.buttonB) ||
           ControllerSupport.isTapped(KeyCode.controllerBack) || ControllerSupport.isTapped(KeyCode.buttonSelect) ||
           ControllerSupport.isTapped(KeyCode.back) || ControllerSupport.isTapped(KeyCode.escape)) && cooldown <= 0){
            // Always operate on the TOP dialog only. Dialogs like "Game Data" or "Rebind Keys" are
            // separate windows opened on top of the settings dialog, so checking ui.settings first
            // (like the old code did) hid the dialog *behind* the visible one and left it orphaned.
            if(top != null){
                // A dialog that owns a "back"-styled button (settings submenu, close buttons) is
                // taken back one level by pressing it; everything else is simply closed.
                Element backButton = findBackButton(top);
                if(debug) arc.util.Log.info("[CtrlNav] B: top=@ backButton=@", top.getClass().getSimpleName(), backButton != null);
                saveFocus(top.cont);
                if(backButton != null){
                    backButton.fireClick();
                }else{
                    top.hide();
                }
                ControllerSupport.vibrate(12);
                cooldown = ACTION_DELAY;
                return;
            }
        }

        // --- Directional navigation --- (raw Y: up=-1, down=+1)
        boolean up = ControllerSupport.isPressed(KeyCode.controllerdPadUp) || ControllerSupport.getAxis(KeyCode.controllerLStickYAxis) < -0.5f;
        boolean down = ControllerSupport.isPressed(KeyCode.controllerdPadDown) || ControllerSupport.getAxis(KeyCode.controllerLStickYAxis) > 0.5f;
        boolean left = ControllerSupport.isPressed(KeyCode.controllerdPadLeft) || ControllerSupport.getAxis(KeyCode.controllerLStickXAxis) < -0.5f;
        boolean right = ControllerSupport.isPressed(KeyCode.controllerdPadRight) || ControllerSupport.getAxis(KeyCode.controllerLStickXAxis) > 0.5f;

        // also allow right stick for navigation in menus (invert Y for stick)
        if(Vars.state != null && Vars.state.isMenu()){
            if(ControllerSupport.getAxis(KeyCode.controllerRStickYAxis) < -0.5f) up = true;
            if(ControllerSupport.getAxis(KeyCode.controllerRStickYAxis) > 0.5f) down = true;
            if(ControllerSupport.getAxis(KeyCode.controllerRStickXAxis) < -0.5f) left = true;
            if(ControllerSupport.getAxis(KeyCode.controllerRStickXAxis) > 0.5f) right = true;
        }

        boolean anyDir = up || down || left || right;
        // Hysteresis: every new d-pad/stick press starts with the long initial delay, holding it
        // then accelerates. (The old code reset the delay in the "not held" branch, so every press
        // effectively used the short repeat delay and one tap could move two rows.)
        if(anyDir && !wasDirDown){
            repeatDelay = INITIAL_DELAY;
            holdTime = 0;
        }
        wasDirDown = anyDir;

        if(anyDir && cooldown <= 0){
            if(debug) arc.util.Log.info("[CtrlNav] dir u=@ d=@ l=@ r=@ cooldown=@", up, down, left, right, cooldown);
            // Determine direction vector for spatial navigation
            float dx = 0, dy = 0;
            if(up) dy += 1;
            if(down) dy -= 1;
            if(left) dx -= 1;
            if(right) dx += 1;

            Element cur = scene.getKeyboardFocus();
            // Handle slider adjustment with left/right when focused on Slider
            if((left || right) && cur instanceof Slider slider){
                float step = slider.getStepSize();
                if(step == 0) step = (slider.getMaxValue() - slider.getMinValue()) / 20f;
                float delta = (right ? step : -step) * (ControllerSupport.isPressed(KeyCode.controllerRBumper) ? 5 : 1);
                slider.setValue(Mathf.clamp(slider.getValue() + delta, slider.getMinValue(), slider.getMaxValue()));
                // Slider change will be picked up via listener automatically
                cooldown = SLIDER_DELAY;
                ControllerSupport.vibrate(4);
                return;
            }
            // CheckBox toggle with left/right or A
            if((left || right) && cur instanceof CheckBox box){
                // allow toggle with left/right
                if(cooldown <= 0){
                    box.setChecked(!box.isChecked());
                    cooldown = ACTION_DELAY;
                    ControllerSupport.vibrate(8);
                }
                return;
            }

            // If no focus, try to set to first element in current context (dialog or root)
            Group context = dialogContext();
            Seq<Element> all = collectFocusable(context);
            if(all.isEmpty()){
                // try root
                all = collectFocusable(scene.root);
                context = scene.root;
            }
            // Detect context change and restore last focus for that context
            if(lastContext != context && context != null){
                Element saved = lastFocusMap.get(context);
                if(saved != null && all.contains(saved)){
                    scene.setKeyboardFocus(saved);
                    lastFocus = saved;
                    lastContext = context;
                    cooldown = repeatDelay;
                    return;
                }
            }
            lastContext = context;
            if(cur == null || !all.contains(cur)){
                Element toFocus = lastFocusMap.get(context);
                if(toFocus != null && all.contains(toFocus)){
                    scene.setKeyboardFocus(toFocus);
                    lastFocus = toFocus;
                }else if(all.size > 0){
                    scene.setKeyboardFocus(all.first());
                    lastFocus = all.first();
                }
                cooldown = repeatDelay;
                ControllerSupport.vibrate(8);
                return;
            }

            if(debug) arc.util.Log.info("[CtrlNav] cur=@ (x=@ y=@ w=@ h=@) all=@ top=@ ctx=@ parent=@", cur.getClass().getSimpleName(), cur.x, cur.y, cur.getWidth(), cur.getHeight(), all.size, top == null ? "null" : top.getClass().getSimpleName(), context.getClass().getSimpleName(), cur.parent == null ? "noparent" : cur.parent.getClass().getSimpleName());
            Element next = null;
            // Try spatial navigation first (find closest in direction)
            if(dx != 0 || dy != 0){
                next = findSpatialNext(cur, all, dx, dy);
            }
            // No linear fallback: without a row/column match, focus simply stays put (edge clamp).
            // The old index-based fallback walked the raw focus list, which also contains the
            // overlay buttons at the screen edges, so pressing down on the bottom row or left on
            // the left column moved focus out of the menu and got it stuck there.

            if(debug) arc.util.Log.info("[CtrlNav] next=@ (x=@ y=@ w=@ h=@) same=@", next == null ? "null" : next.getClass().getSimpleName(), next == null ? 0 : next.x, next == null ? 0 : next.y, next == null ? 0 : next.getWidth(), next == null ? 0 : next.getHeight(), next == cur);
            if(next != null && next != cur){
                // save current focus for this context before changing
                lastFocusMap.put(context, cur);
                scene.setKeyboardFocus(next);
                lastFocus = next;
                // scroll into view
                scrollToVisible(next);
                cooldown = repeatDelay;
                holdTime += Math.max(1f, Math.min(nowMs - lastTickMs, 250f));
                if(holdTime > 700f){
                    repeatDelay = REPEAT_DELAY;
                }else if(holdTime > 1600f){
                    repeatDelay = FAST_REPEAT_DELAY;
                }
                ControllerSupport.vibrate(6);
            }
        }else if(!anyDir){
            holdTime = 0;
            if(cooldown < 0) cooldown = 0;
        }

        // Right stick scrolling
        float scrollX = ControllerSupport.getAxis(KeyCode.controllerRStickXAxis);
        float scrollY = ControllerSupport.getAxis(KeyCode.controllerRStickYAxis);
        // On Android, right stick is also used for cursor, but allow scrolling when not in game
        if(Vars.state != null && !Vars.state.isGame()){
            if(Math.abs(scrollX) > 0.25f || Math.abs(scrollY) > 0.25f){
                Group context = dialogContext();
                ScrollPane pane = findScrollablePane(context);
                if(pane != null){
                    pane.setScrollX(pane.getScrollX() + scrollX * 14f);
                    pane.setScrollY(pane.getScrollY() - scrollY * 14f);
                    pane.updateVisualScroll();
                }
            }
        }
    }

    /**
     * Spatial navigation: prefers candidates that share a row (for left/right) or a column
     * (for up/down) with the focused element, i.e. proper "rectangular" navigation like every
     * console UI. Without the overlap requirement the search happily walks out of the menu grid
     * into unrelated overlay buttons (the "i"/Discord buttons at the screen edges), where the
     * user then gets stuck.
     */
    private static Element findSpatialNext(Element cur, Seq<Element> all, float dx, float dy){
        if(cur == null) return null;
        Vec2 curPos = cur.localToStageCoordinates(new Vec2(cur.getWidth()/2f, cur.getHeight()/2f));
        float curHW = cur.getWidth()/2f, curHH = cur.getHeight()/2f;

        Element best = null;
        float bestScore = Float.MAX_VALUE;
        for(Element e : all){
            if(e == cur) continue;
            Vec2 pos = e.localToStageCoordinates(new Vec2(e.getWidth()/2f, e.getHeight()/2f));
            float ddx = pos.x - curPos.x, ddy = pos.y - curPos.y;
            boolean overlapsX = Math.abs(ddx) < curHW + e.getWidth()/2f;
            boolean overlapsY = Math.abs(ddy) < curHH + e.getHeight()/2f;

            float primary, secondary;
            if(dx != 0){
                if(!overlapsY || ddx * dx <= 1f) continue;
                primary = Math.abs(ddx);
                secondary = Math.abs(ddy);
            }else{
                if(!overlapsX || ddy * dy <= 1f) continue;
                primary = Math.abs(ddy);
                secondary = Math.abs(ddx);
            }
            // prefer the closest element in the movement direction, then the best aligned one
            float score = primary * 3f + secondary;
            if(score < bestScore){
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static void scrollToVisible(Element e){
        Element p = e.parent;
        while(p != null){
            if(p instanceof ScrollPane pane){
                // approximate centering
                Vec2 pos = e.localToStageCoordinates(new Vec2(0,0));
                // Use pane's scroll to bring into view
                float targetY = -pos.y + Core.graphics.getHeight()/2f;
                pane.setScrollY(Mathf.clamp(targetY, 0, pane.getMaxY()));
                pane.updateVisualScroll();
                break;
            }
            p = p.parent;
        }
    }

    private static ScrollPane findScrollablePane(Group root){
        Seq<Element> panes = new Seq<>();
        collectPanes(root, panes);
        for(Element e : panes){
            if(e.visible && e.getWidth() > 0) return (ScrollPane)e;
        }
        return null;
    }

    private static boolean isClickable(Element e){
        if(e instanceof Button) return true;
        if(e instanceof TextField) return true;
        if(e instanceof Slider) return true;
        if(e instanceof CheckBox) return true;
        // check for ClickListener
        for(var l : e.getListeners()) if(l.getClass().getSimpleName().contains("ClickListener")) return true;
        return false;
    }

    private static Element findClickable(Element e){
        Element cur = e;
        while(cur != null){
            if(isClickable(cur)) return cur;
            cur = cur.parent;
        }
        return null;
    }

    /** Stores the current focus under the current context so it can be restored later. */
    private static void rememberCurrentFocus(){
        Element cur = scene.getKeyboardFocus();
        if(cur != null) lastFocusMap.put(dialogContext(), cur);
    }

    /** Whether <b>e</b> is the given dialog or a descendant of it. */
    private static boolean isInside(Element e, Element container){
        Element cur = e;
        while(cur != null){
            if(cur == container) return true;
            cur = cur.parent;
        }
        return false;
    }

    /** Finds a button inside <b>dialog</b> whose label looks like a back/close button, or null. */
    private static Element findBackButton(Group dialog){
        Seq<Element> found = new Seq<>();
        collectRecursive(dialog, found);
        for(Element e : found){
            if(!(e instanceof Button) || !e.visible || e.touchable != Touchable.enabled) continue;
            String text = elementText(e).toLowerCase();
            if(text.contains("back") || text.contains("return")) return e;
        }
        return null;
    }

    private static String elementText(Element e){
        if(e instanceof Label l) return l.getText().toString();
        if(e instanceof TextField t) return "";
        if(e instanceof Button && e instanceof Group g){
            StringBuilder sb = new StringBuilder();
            for(Element child : g.getChildren()){
                if(child instanceof Label l) sb.append(l.getText()).append(' ');
                if(child instanceof Group cg) sb.append(elementText(cg));
            }
            return sb.toString();
        }
        return "";
    }

    private static Seq<Element> collectByText(Group root, String text){
        Seq<Element> out = new Seq<>();
        collectByTextRecursive(root, text.toLowerCase(), out);
        return out;
    }

    private static void collectByTextRecursive(Group group, String text, Seq<Element> out){
        for(Element e : group.getChildren()){
            if(!e.visible) continue;
            String name = e.name != null ? e.name.toLowerCase() : "";
            if(e instanceof Button b){
                String btnText = b.toString().toLowerCase();
                if(btnText.contains(text) || name.contains(text)) out.add(e);
                // also check label text
                try{
                    for(Element child : ((Group)e).getChildren()){
                        if(child instanceof Label l && l.getText().toString().toLowerCase().contains(text)){
                            out.add(e);
                            break;
                        }
                    }
                }catch(Exception ignored){}
            }
            if(e instanceof Group g) collectByTextRecursive(g, text, out);
        }
    }

    private static Seq<Element> collectFocusable(Group root){
        Seq<Element> raw = new Seq<>();
        collectRecursive(root == null ? scene.root : root, raw);
        Seq<Element> filtered = new Seq<>();
        for(Element e : raw){
            if(e.visible && e.touchable == Touchable.enabled && e.getWidth() > 0 && e.getHeight() > 0){
                filtered.add(e);
            }
        }
        return filtered;
    }

    private static void collectRecursive(Group group, Seq<Element> out){
        for(Element e : group.getChildren()){
            if(!e.visible) continue;
            boolean focusable = (e instanceof Button) || (e instanceof TextField) || (e instanceof Slider) || (e instanceof CheckBox) || (e instanceof TextArea);
            // also consider any element with ClickListener as focusable for navigation
            if(!focusable){
                for(var l : e.getListeners()){
                    if(l.getClass().getSimpleName().contains("ClickListener")){
                        focusable = true;
                        break;
                    }
                }
            }
            if(focusable && e.touchable == Touchable.enabled){
                out.add(e);
            }
            if(e instanceof Group g) collectRecursive(g, out);
        }
    }

    private static void collectPanes(Group group, Seq<Element> out){
        for(Element e : group.getChildren()){
            if(e instanceof ScrollPane) out.add(e);
            if(e instanceof Group g) collectPanes(g, out);
        }
    }
}
