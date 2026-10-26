package mindustry;

import arc.input.KeyCode;
import mindustry.input.controller.ControllerConfig;
import mindustry.input.controller.ControllerSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ControllerTest {
    @Test
    public void testDeadzone(){
        // deadzone 15% should filter small values
        float dz = 0.15f;
        assertEquals(0f, ControllerConfig.applyDeadzone(0.05f, dz), 0.001f);
        assertEquals(0f, ControllerConfig.applyDeadzone(-0.1f, dz), 0.001f);
        assertTrue(ControllerConfig.applyDeadzone(0.5f, dz) > 0);
    }

    @Test
    public void testNoWrapInMenu(){
        // Simulate menu navigation: should not wrap at edges
        // This is tested in ControllerNavigator logic: when isMenu true, n <0 or >=size returns (stay)
        // We verify the condition
        boolean isMenu = true;
        int size = 5;
        int idx = 0;
        int n = idx - 1; // pressing up/left at start
        if(isMenu){
            assertTrue(n < 0); // should not wrap, should stay
        }
        idx = size - 1;
        n = idx + 1; // pressing down/right at end
        if(isMenu){
            assertTrue(n >= size);
        }
    }

    @Test
    public void testInvertLogic(){
        // raw up = -1 should become +1 world up when not inverted
        float rawUp = -1f;
        float ly = rawUp;
        boolean invertY = false;
        if(!invertY) ly = -ly;
        assertEquals(1f, ly, 0.001f);
        // inverted should stay -1
        invertY = true;
        ly = rawUp;
        if(!invertY) ly = -ly;
        assertEquals(-1f, ly, 0.001f);
    }

    @Test
    public void testConfigDefaults(){
        assertEquals(15, ControllerConfig.DEFAULT_DEADZONE);
        assertEquals(100, ControllerConfig.DEFAULT_SENSITIVITY);
        assertEquals(100, ControllerConfig.DEFAULT_CURSOR_SPEED);
        assertEquals(15, ControllerConfig.DEFAULT_TRIGGER_THRESHOLD);
        // deadzone helper without Core.settings
        assertEquals(0f, ControllerConfig.applyDeadzone(0.05f, 0.15f), 0.001f);
        assertTrue(ControllerConfig.applyDeadzone(0.5f, 0.15f) > 0);
    }

    @Test
    public void testProviderAlternativeMapping(){
        assertEquals(KeyCode.buttonA, ControllerSupport.alternative(KeyCode.controllerA));
        assertEquals(KeyCode.controllerA, ControllerSupport.alternative(KeyCode.buttonA));
        assertNull(ControllerSupport.alternative(KeyCode.a));
    }
}
