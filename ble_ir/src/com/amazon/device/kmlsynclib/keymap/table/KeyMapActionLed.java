package com.amazon.device.kmlsynclib.keymap.table;

import com.amazon.device.kmlsynclib.keymap.sync.BleConfig;
import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
final class KeyMapActionLed extends KeyMapAction {
    private static final int LED_ANIMATION_TYPE_BLINK0 = 3;
    private static final int LED_ANIMATION_TYPE_BLINK1 = 4;
    private static final int LED_ANIMATION_TYPE_OFF = 0;
    private static final int LED_ANIMATION_TYPE_ON = 1;
    private static final int LED_ANIMATION_TYPE_PULSE = 2;
    private int mAnimationType;
    private int mB;
    private int mG;
    private int mR;

    private short clamp(int input) {
        if (input < 0) {
            return (short) 0;
        }
        if (input <= 255) {
            return (short) input;
        }
        return (short) 255;
    }

    KeyMapActionLed(JSONObject json) throws JSONException {
        this.mAnimationType = transcodeAnimation(json.optString("Animation", ""));
        this.mR = clamp(json.optInt("R", 0));
        this.mG = clamp(json.optInt("G", 0));
        this.mB = clamp(json.optInt("B", 0));
    }

    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue */
    private int transcodeAnimation(String animation) {
        char c = 65535;
        switch (animation.hashCode()) {
            case -1719284134:
                if (animation.equals("BlinkType0")) {
                    c = LED_ANIMATION_TYPE_BLINK1;
                    break;
                }
                break;
            case -1719284133:
                if (animation.equals("BlinkType1")) {
                    c = 5;
                    break;
                }
                break;
            case 2559:
                if (animation.equals("On")) {
                    c = 2;
                    break;
                }
                break;
            case 79183:
                if (animation.equals("Off")) {
                    c = 1;
                    break;
                }
                break;
            case 77474681:
                if (animation.equals("Pulse")) {
                    c = LED_ANIMATION_TYPE_BLINK0;
                    break;
                }
                break;
        }
        switch (c) {
            case 2:
                return 1;
            case LED_ANIMATION_TYPE_BLINK0 /* 3 */:
                return 2;
            case LED_ANIMATION_TYPE_BLINK1 /* 4 */:
                return LED_ANIMATION_TYPE_BLINK0;
            case BleConfig.KEYMAP_CONTROL_COMMIT_BLAST_TABLE /* 5 */:
                return LED_ANIMATION_TYPE_BLINK1;
            default:
                return 0;
        }
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public byte[] compile() throws IOException {
        byte[] payload = ByteTool.concatBytes(ByteTool.i8Tob(this.mAnimationType), ByteTool.i8Tob(this.mR), ByteTool.i8Tob(this.mG), ByteTool.i8Tob(this.mB));
        return compileAction(KeyMapActionType.LED, 0, payload);
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public String describe() {
        return String.format("LED[%x](r:%d g:%d b:%d)", Integer.valueOf(this.mAnimationType), Integer.valueOf(this.mR), Integer.valueOf(this.mG), Integer.valueOf(this.mB));
    }
}
