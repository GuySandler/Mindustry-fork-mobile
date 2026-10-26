package mindustry.ui.dialogs;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.Time;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.input.controller.ControllerConfig;
import mindustry.input.controller.ControllerSupport;
import mindustry.ui.Styles;

import static mindustry.Vars.*;

import static arc.Core.*;

/**
 * Testing environment for controller: shows live axis values, button states, vibration test, and UI navigation sample.
 */
public class ControllerTestDialog extends BaseDialog{

    public ControllerTestDialog(){
        super("Controller Test");
        addCloseButton();
        shouldPause = true;

        cont.defaults().pad(6f);

        // status
        cont.label(() -> {
            boolean con = ControllerSupport.isConnected();
            String name = con ? ControllerSupport.getProvider().getName() : bundle.get("controller.disconnected", "No Controller");
            int count = ControllerSupport.getProvider().connectedCount();
            return bundle.format("controller.status", con ? "[accent]" + name + " [lightgray](" + count + ")" : "[lightgray]" + name);
        }).row();

        cont.image().color(Color.gray).fillX().height(3).pad(6).colspan(2).row();

        // axis bars
        Table axisTable = new Table();
        axisTable.defaults().pad(2f).left();

        addAxisBar(axisTable, "L Stick X", () -> ControllerSupport.getAxis(KeyCode.controllerLStickXAxis));
        addAxisBar(axisTable, "L Stick Y", () -> ControllerSupport.getAxis(KeyCode.controllerLStickYAxis));
        addAxisBar(axisTable, "R Stick X", () -> ControllerSupport.getAxis(KeyCode.controllerRStickXAxis));
        addAxisBar(axisTable, "R Stick Y", () -> ControllerSupport.getAxis(KeyCode.controllerRStickYAxis));
        addAxisBar(axisTable, "L Trigger", () -> ControllerSupport.getAxis(KeyCode.controllerLTrigger));
        addAxisBar(axisTable, "R Trigger", () -> ControllerSupport.getAxis(KeyCode.controllerRTrigger));

        cont.add(axisTable).colspan(2).fillX().row();

        // button grid
        Table btnTable = new Table();
        btnTable.defaults().pad(4f).size(110f, 45f);

        addButtonIndicator(btnTable, "A", KeyCode.controllerA, KeyCode.buttonA);
        addButtonIndicator(btnTable, "B", KeyCode.controllerB, KeyCode.buttonB);
        addButtonIndicator(btnTable, "X", KeyCode.controllerX, KeyCode.buttonX);
        addButtonIndicator(btnTable, "Y", KeyCode.controllerY, KeyCode.buttonY);
        btnTable.row();
        addButtonIndicator(btnTable, "LB", KeyCode.controllerLBumper, KeyCode.buttonL1);
        addButtonIndicator(btnTable, "RB", KeyCode.controllerRBumper, KeyCode.buttonR1);
        addButtonIndicator(btnTable, "Back", KeyCode.controllerBack, KeyCode.buttonSelect);
        addButtonIndicator(btnTable, "Start", KeyCode.controllerStart, KeyCode.buttonStart);
        btnTable.row();
        addButtonIndicator(btnTable, "L Stick", KeyCode.controllerLStick, KeyCode.buttonThumbL);
        addButtonIndicator(btnTable, "R Stick", KeyCode.controllerRStick, KeyCode.buttonThumbR);
        addButtonIndicator(btnTable, "Guide", KeyCode.controllerGuide, KeyCode.buttonMode);
        btnTable.row();
        addButtonIndicator(btnTable, "D-Up", KeyCode.controllerdPadUp);
        addButtonIndicator(btnTable, "D-Down", KeyCode.controllerdPadDown);
        addButtonIndicator(btnTable, "D-Left", KeyCode.controllerdPadLeft);
        addButtonIndicator(btnTable, "D-Right", KeyCode.controllerdPadRight);

        cont.add(btnTable).colspan(2).row();

        // deadzone / sensitivity live
        cont.table(t -> {
            t.label(() -> "Deadzone: " + (int)(ControllerSupport.getAxis(KeyCode.controllerLStickXAxis)*100) + "%").padRight(10);
            t.label(() -> "Virtual: " + (int)ControllerSupport.virtualX + "," + (int)ControllerSupport.virtualY);
        }).row();

        // vibration test
        cont.button("Vibrate 200ms", () -> ControllerSupport.vibrate(200)).size(160, 50).row();
        cont.button("Vibrate Pattern", () -> {
            try{ Core.input.vibrate(new long[]{0, 50, 50, 100}, -1); }catch(Exception ignored){ ControllerSupport.vibrate(300); }
        }).size(160, 50).row();

        // UI navigation test area
        cont.image().color(Color.gray).fillX().height(3).pad(6).colspan(2).row();
        cont.add("UI Navigation Test (use D-pad / A / B)").colspan(2).row();
        cont.table(sample -> {
            sample.defaults().pad(4f).size(140, 50);
            sample.button("Button 1", () -> Vars.ui.showInfo("Btn1 pressed")).update(b -> b.setChecked(false));
            sample.button("Button 2", () -> Vars.ui.showInfo("Btn2 pressed"));
            sample.row();
            sample.check("Check 1", false, b -> {});
            sample.check("Check 2", true, b -> {});
            sample.row();
            sample.slider(0, 100, 5, 50, v -> {}).width(280).colspan(2);
            sample.row();
            sample.field("TextField", s -> {}).width(280).colspan(2);
        }).row();

        // instructions
        cont.labelWrap("A=Confirm  B=Back  D-pad/Stick=Navigate  LB/RB=Adjust slider  Start+Back=Test").width(400).center().row();
    }

    private void addAxisBar(Table table, String name, Floatp getter){
        table.add(name).left().width(90);
        table.stack(
            new arc.scene.ui.ProgressBar(0, 1, 0.01f, false, Styles.defaultSlider){{
                setValue(0.5f);
                update(() -> {
                    float v = getter.get();
                    // map -1..1 to 0..1
                    float norm = (v + 1f) / 2f;
                    setValue(norm);
                    // color based on magnitude
                    float mag = Math.abs(v);
                    setColor(mag > 0.1f ? Pal.accent : Color.darkGray);
                });
            }},
            new Label(() -> {
                float v = getter.get();
                return String.format("%.2f", v);
            })
        ).width(220).height(18).row();
    }

    private void addButtonIndicator(Table table, String label, KeyCode... codes){
        Label l = new Label(label, Styles.outlineLabel);
        l.update(() -> {
            boolean pressed = false;
            for(KeyCode c : codes) if(ControllerSupport.isPressed(c)) { pressed = true; break; }
            l.setText((pressed ? "[accent]" : "[lightgray]") + label);
            l.setColor(pressed ? Pal.accent : Color.lightGray);
        });
        table.add(l);
    }

    interface Floatp{ float get(); }
}
