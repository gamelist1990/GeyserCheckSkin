package com.pexserver.checkskin;

import com.google.gson.Gson;
import org.geysermc.geyser.session.auth.BedrockClientData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class FallbackAndAdapterTest {
    @TempDir
    Path folder;

    @Test
    void defaultsInstalledAndAdapterReplacesAllAppearanceFields() throws Exception {
        var config = CheckConfig.load(folder);
        var fallbacks = FallbackSkin.loadAll(folder, config);
        assertEquals(3, fallbacks.size());
        assertEquals(3, fallbacks.stream().map(FallbackSkin::id).distinct().count());
        for (int i = 0; i < config.fallbackFiles.size(); i++) {
            String file = config.fallbackFiles.get(i);
            assertTrue(Files.exists(folder.resolve(file)));
            try (var resource = getClass().getResourceAsStream("/" + file)) {
                assertArrayEquals(resource.readAllBytes(), Files.readAllBytes(folder.resolve(file)));
            }
            assertEquals(fallbacks.get(i).id(), FallbackSkin.load(folder, file).id());
        }
        var fallback = fallbacks.get(0);
        BedrockClientData client = new Gson().fromJson(
                "{\"SkinImageWidth\":1,\"SkinImageHeight\":1,\"SkinId\":\"evil\",\"PersonaSkin\":true,\"CapeId\":\"evil-cape\"}",
                BedrockClientData.class);
        client.setOriginalString("signed-original-jwt");
        BedrockSkinAdapter adapter = new BedrockSkinAdapter();
        adapter.replace(client, fallback);
        assertTrue(new SkinInspector(config).inspect(adapter.read(client)).allowed());
        assertNull(client.getOriginalString());
        assertFalse(client.isPersonaSkin());
        assertEquals("", client.getCapeId());
        assertEquals(0, client.getCapeData().length);
        assertEquals(64, client.getSkinImageWidth());
        assertArrayEquals(fallback.rgba(), client.getSkinData());
        assertNotSame(fallback.rgba(), client.getSkinData());
    }

    @Test
    void transparentFallbackRejected() throws Exception {
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png",
                folder.resolve("fallback.png").toFile());
        assertThrows(java.io.IOException.class, () -> FallbackSkin.load(folder, "fallback.png"));
    }

    @Test
    void invalidConfigRejectedAndCustomFallbackNeverOverwritten() throws Exception {
        Files.writeString(folder.resolve("config.json"), "{\"geometryMode\":\"TYPO\"}");
        assertThrows(java.io.IOException.class, () -> CheckConfig.load(folder));
        var config = new CheckConfig();
        config.fallbackFiles = java.util.List.of("custom.png");
        assertThrows(java.io.IOException.class, () -> FallbackSkin.loadAll(folder, config));
        config.fallbackFiles = java.util.List.of("../outside.png");
        assertThrows(java.io.IOException.class, () -> FallbackSkin.loadAll(folder, config));
        config.minimumBodyOpacity = Double.NaN;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void emptyNullAndBlankCandidateArraysRejected() throws Exception {
        for (String json : java.util.List.of("{\"fallbackFiles\":[]}", "{\"fallbackFiles\":null}",
                "{\"fallbackFiles\":[null]}", "{\"fallbackFiles\":[\" \"]}")) {
            Files.writeString(folder.resolve("config.json"), json);
            assertThrows(java.io.IOException.class, () -> CheckConfig.load(folder), json);
        }
    }

    @Test
    void legacySingleFileAndArrayPrecedence() throws Exception {
        Files.writeString(folder.resolve("config.json"), "{\"fallbackFile\":\"old.png\"}");
        assertEquals(java.util.List.of("old.png"), CheckConfig.load(folder).fallbackFiles);
        Files.writeString(folder.resolve("config.json"),
                "{\"fallbackFile\":\"old.png\",\"fallbackFiles\":[\"new.png\"]}");
        assertEquals(java.util.List.of("new.png"), CheckConfig.load(folder).fallbackFiles);
    }

    @Test
    void existingBundledFileIsPreservedAndAllCandidatesValidated() throws Exception {
        var config = CheckConfig.load(folder);
        FallbackSkin.loadAll(folder, config);
        Path first = folder.resolve(config.fallbackFiles.get(0));
        byte[] before = Files.readAllBytes(first);
        FallbackSkin.loadAll(folder, config);
        assertArrayEquals(before, Files.readAllBytes(first));
        Path last = folder.resolve(config.fallbackFiles.get(2));
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", last.toFile());
        byte[] invalid = Files.readAllBytes(last);
        assertThrows(java.io.IOException.class, () -> FallbackSkin.loadAll(folder, config));
        assertArrayEquals(invalid, Files.readAllBytes(last));
    }
}
