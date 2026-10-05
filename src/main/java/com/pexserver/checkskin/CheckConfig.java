package com.pexserver.checkskin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CheckConfig {
    public enum GeometryMode {
        DENY, MINOR, ALLOW
    }

    public enum Action {
        FALLBACK, KICK
    }

    public boolean enabled = true;
    public GeometryMode geometryMode = GeometryMode.MINOR;
    public Action violationAction = Action.FALLBACK;
    public boolean allowPersona = false;
    public boolean transparencyCheck = true;
    public int alphaThreshold = 200;
    public double minimumBodyOpacity = .95;
    public double minimumFaceOpacity = .80;
    public int maxGeometryBytes = 1_048_576;
    public int maxBones = 64;
    public int maxCubes = 128;
    public double decorationMargin = 2;
    public double maxDecorationSurfaceArea = 512;
    public List<String> fallbackFiles = List.of(
            "fallbacks/skin1.png",
            "fallbacks/skin2.png",
            "fallbacks/skin3.png");
    public String kickMessage = "このスキンは使用できません。通常のスキンに変更してください。";
    public boolean logViolations = true;

    public static CheckConfig load(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("config.json");
        if (!Files.exists(file)) {
            try (var in = CheckConfig.class.getResourceAsStream("/config.json")) {
                if (in == null)
                    throw new IOException("Missing bundled config.json");
                Files.copy(in, file);
            }
        }
        try {
            var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("fallbackFiles") && json.has("fallbackFile")) {
                JsonArray paths = new JsonArray();
                paths.add(json.get("fallbackFile"));
                json.add("fallbackFiles", paths);
            }
            CheckConfig config = new Gson().fromJson(json, CheckConfig.class);
            if (config == null)
                throw new IllegalArgumentException("Empty configuration");
            config.validate();
            return config;
        } catch (RuntimeException e) {
            throw new IOException("Invalid config.json: " + e.getMessage(), e);
        }
    }

    public void validate() {
        if (geometryMode == null || violationAction == null)
            throw new IllegalArgumentException("Invalid mode/action");
        if (alphaThreshold < 1 || alphaThreshold > 255)
            throw new IllegalArgumentException("alphaThreshold: 1..255");
        unit(minimumBodyOpacity);
        unit(minimumFaceOpacity);
        if (maxGeometryBytes < 1024 || maxGeometryBytes > 4_194_304 || maxBones < 6 || maxBones > 256
                || maxCubes < 6 || maxCubes > 1024)
            throw new IllegalArgumentException("Invalid geometry limits");
        if (!Double.isFinite(decorationMargin) || decorationMargin < 0 || decorationMargin > 16
                || !Double.isFinite(maxDecorationSurfaceArea) || maxDecorationSurfaceArea < 0)
            throw new IllegalArgumentException("Invalid decoration limits");
        if (fallbackFiles == null || fallbackFiles.isEmpty() || fallbackFiles.size() > 128
                || fallbackFiles.stream().anyMatch(path -> path == null || path.isBlank()))
            throw new IllegalArgumentException("fallbackFiles must contain 1..128 nonblank paths");
        fallbackFiles = List.copyOf(fallbackFiles);
        if (kickMessage == null || kickMessage.isBlank())
            throw new IllegalArgumentException("Missing kickMessage");
    }

    private static void unit(double n) {
        if (!Double.isFinite(n) || n < 0 || n > 1)
            throw new IllegalArgumentException("Opacity must be 0..1");
    }
}
