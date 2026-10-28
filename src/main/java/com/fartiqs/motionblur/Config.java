package com.fartiqs.motionblur;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("fartiqsmotionblur.json");

    public static final String[] ASPECT_NAMES = {"4:3", "5:4", "16:9", "16:10", "21:9", "32:9", "1:1", "Custom"};
    public static final float[] ASPECT_VALUES = {4f / 3f, 5f / 4f, 16f / 9f, 16f / 10f, 21f / 9f, 32f / 9f, 1f, -1f};
    public static final int CUSTOM = ASPECT_NAMES.length - 1;
    public static final float MIN_ASPECT = 0.75f;
    public static final float MAX_ASPECT = 3.6f;

    public boolean enabled = true;
    public float strength = 60f;            // 0..100
    public boolean adaptive = false;
    public float adaptiveSensitivity = 50f; // 0..100
    public boolean disableInMenus = true;
    public boolean aspectEnabled = false;
    public float aspect = 16f / 9f;
    public int aspectPreset = 2;

    private static Config instance = new Config();

    public static Config get() {
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Config c = GSON.fromJson(Files.readString(FILE), Config.class);
                if (c != null) instance = c;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        instance.sanitize();
        save();
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void reset() {
        instance = new Config();
        save();
    }

    private void sanitize() {
        strength = Math.max(0f, Math.min(100f, strength));
        adaptiveSensitivity = Math.max(0f, Math.min(100f, adaptiveSensitivity));
        aspect = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, aspect));
        if (aspectPreset < 0 || aspectPreset > CUSTOM) aspectPreset = CUSTOM;
    }
}
