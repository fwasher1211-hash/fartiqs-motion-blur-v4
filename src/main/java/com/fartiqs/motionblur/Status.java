package com.fartiqs.motionblur;

/** Live diagnostics shown in the menu, so the mod can never silently claim to work when it doesn't. */
public final class Status {
    public static volatile String blur = "NOT RUN YET";
    public static volatile long blurHookCalls = 0;
    public static volatile long blurFrames = 0;
    public static volatile float lastAlpha = 0f;
    public static volatile int glError = 0;
    public static volatile long aspectHookCalls = 0;

    private Status() {}
}
