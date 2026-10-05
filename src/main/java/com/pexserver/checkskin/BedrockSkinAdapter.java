package com.pexserver.checkskin;

import org.geysermc.geyser.session.auth.BedrockClientData;
import org.geysermc.geyser.api.skin.SkinGeometry;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Geyser has no public API for raw login skins; keep the version-dependent
 * boundary here.
 */
final class BedrockSkinAdapter {
    private static final String[] FIELDS = { "skinId", "skinData", "skinImageWidth", "skinImageHeight",
            "geometryName", "geometryData", "personaSkin", "premiumSkin", "capeId", "capeData",
            "capeImageWidth", "capeImageHeight", "capeOnClassicSkin", "skinAnimationData", "armSize" };
    private final Map<String, Field> fields = new HashMap<>();

    BedrockSkinAdapter() throws ReflectiveOperationException {
        for (String name : FIELDS) {
            Field field = BedrockClientData.class.getDeclaredField(name);
            field.setAccessible(true);
            fields.put(name, field);
        }
    }

    SkinInput read(BedrockClientData client) {
        return new SkinInput(client.getSkinImageWidth(), client.getSkinImageHeight(), client.getSkinData(),
                utf8(client.getGeometryName()), utf8(client.getGeometryData()),
                "slim".equalsIgnoreCase(client.getArmSize()), client.isPersonaSkin(), client.getSkinAnimationData());
    }

    void replace(BedrockClientData client, FallbackSkin fallback) throws IllegalAccessException {
        set(client, "skinId", fallback.id());
        set(client, "skinData", fallback.rgba().clone());
        set(client, "skinImageWidth", 64);
        set(client, "skinImageHeight", 64);
        set(client, "geometryName", SkinGeometry.WIDE.geometryName().getBytes(StandardCharsets.UTF_8));
        set(client, "geometryData", new byte[0]);
        set(client, "personaSkin", false);
        set(client, "premiumSkin", false);
        set(client, "capeId", "");
        set(client, "capeData", new byte[0]);
        set(client, "capeImageWidth", 0);
        set(client, "capeImageHeight", 0);
        set(client, "capeOnClassicSkin", false);
        set(client, "skinAnimationData", "");
        set(client, "armSize", "wide");
        client.setOriginalString(null);
    }

    private void set(BedrockClientData target, String name, Object value) throws IllegalAccessException {
        fields.get(name).set(target, value);
    }

    private static String utf8(byte[] data) {
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }
}
