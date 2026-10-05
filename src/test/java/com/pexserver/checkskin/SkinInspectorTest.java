package com.pexserver.checkskin;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SkinInspectorTest {
    private final CheckConfig config = new CheckConfig();

    static byte[] opaque(int w, int h) {
        byte[] data = new byte[w * h * 4];
        Arrays.fill(data, (byte) 255);
        return data;
    }

    static SkinInput input(JsonObject model, byte[] data, int w, int h, boolean slim) {
        String id = model == null ? "geometry.humanoid.custom" + (slim ? "Slim" : "")
                : model.getAsJsonObject("description").get("identifier").getAsString();
        String patch = "{\"geometry\":{\"default\":\"" + id + "\"}}";
        JsonObject root = new JsonObject();
        JsonArray models = new JsonArray();
        if (model != null)
            models.add(model);
        root.add("minecraft:geometry", models);
        return new SkinInput(w, h, data, patch, model == null ? "" : root.toString(), slim, false, "");
    }

    static JsonObject bone(JsonObject m, String name) {
        for (var b : m.getAsJsonArray("bones"))
            if (b.getAsJsonObject().get("name").getAsString().equals(name))
                return b.getAsJsonObject();
        throw new IllegalArgumentException(name);
    }

    static JsonObject cube(JsonObject m, String name) {
        return bone(m, name).getAsJsonArray("cubes").get(0).getAsJsonObject();
    }

    private SkinInspector.Result check(JsonObject model) {
        return new SkinInspector(config).inspect(input(model, opaque(64, 64), 64, 64, false));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void normalWideAndSlim(boolean slim) {
        var inspector = new SkinInspector(config);
        assertTrue(inspector.inspect(input(null, opaque(64, 64), 64, 64, slim)).allowed());
        assertTrue(inspector.inspect(input(SkinInspector.vanilla(slim), opaque(64, 64), 64, 64, slim)).allowed());
    }

    @Test
    void legacyAndHD() {
        var inspector = new SkinInspector(config);
        assertTrue(inspector.inspect(input(null, opaque(64, 32), 64, 32, false)).allowed());
        assertTrue(inspector.inspect(input(null, opaque(128, 128), 128, 128, false)).allowed());
        assertTrue(inspector.inspect(input(null, opaque(128, 64), 128, 64, false)).allowed());
    }

    @Test
    void invisibleAndPartialSkin() {
        assertFalse(new SkinInspector(config).inspect(input(null, new byte[64 * 64 * 4], 64, 64, false)).allowed());
        byte[] data = opaque(64, 64);
        for (int y = 8; y < 16; y++)
            for (int x = 8; x < 16; x++)
                data[(y * 64 + x) * 4 + 3] = 0;
        assertEquals("transparent-face:head",
                new SkinInspector(config).inspect(input(null, data, 64, 64, false)).reason());
    }

    @Test
    void transparentUnusedAndOverlayPixelsAreAllowed() {
        byte[] data = new byte[64 * 64 * 4];
        for (String name : java.util.List.of("head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg")) {
            JsonObject c = cube(SkinInspector.vanilla(false), name);
            JsonArray s = c.getAsJsonArray("size"), uv = c.getAsJsonArray("uv");
            int x = s.get(0).getAsInt(), y = s.get(1).getAsInt(), z = s.get(2).getAsInt(), u = uv.get(0).getAsInt(),
                    v = uv.get(1).getAsInt();
            for (int[] r : new int[][] { { u + z, v, 2 * x, z }, { u, v + z, 2 * x + 2 * z, y } }) {
                for (int py = r[1]; py < r[1] + r[3]; py++)
                    for (int px = r[0]; px < r[0] + r[2]; px++)
                        data[(py * 64 + px) * 4 + 3] = (byte) 255;
            }
        }
        assertTrue(new SkinInspector(config).inspect(input(null, data, 64, 64, false)).allowed());
        config.geometryMode = CheckConfig.GeometryMode.ALLOW;
        assertTrue(
                new SkinInspector(config).inspect(input(SkinInspector.vanilla(false), data, 64, 64, false)).allowed());
    }

    @Test
    void smallDecorationAllowedButDenyRejects() {
        JsonObject m = SkinInspector.vanilla(false), extra = new JsonObject();
        extra.addProperty("name", "smallEars");
        extra.addProperty("parent", "head");
        extra.add("pivot", JsonParser.parseString("[0,24,0]"));
        extra.add("cubes", JsonParser.parseString("[{\"origin\":[-4,32,-1],\"size\":[2,2,2],\"uv\":[0,0]}]"));
        m.getAsJsonArray("bones").add(extra);
        assertTrue(check(m).allowed(), check(m).reason());
        config.geometryMode = CheckConfig.GeometryMode.DENY;
        assertFalse(check(m).allowed());
    }

    @Test
    void denyAcceptsVanilla() {
        config.geometryMode = CheckConfig.GeometryMode.DENY;
        assertTrue(check(SkinInspector.vanilla(false)).allowed());
    }

    @Test
    void changedUVCannotHideBody() {
        JsonObject m = SkinInspector.vanilla(false);
        cube(m, "body").add("uv", JsonParser.parseString("[0,0]"));
        assertEquals("changed-body-cube:body", check(m).reason());
    }

    @Test
    void tinyHeadAndExtremeCoordinatesRejected() {
        JsonObject m = SkinInspector.vanilla(false);
        cube(m, "head").add("size", JsonParser.parseString("[1,1,1]"));
        assertFalse(check(m).allowed());
        m = SkinInspector.vanilla(false);
        cube(m, "body").add("origin", JsonParser.parseString("[-4,-999999,-2]"));
        assertFalse(check(m).allowed());
    }

    @Test
    void ancestorTransformsAndRenderFlagsRejected() {
        for (String property : java.util.List.of("scale", "rotation", "neverRender", "reset")) {
            JsonObject m = SkinInspector.vanilla(false);
            bone(m, "root").add(property, JsonParser
                    .parseString(property.equals("rotation") || property.equals("scale") ? "[1,1,1]" : "true"));
            assertFalse(check(m).allowed(), property);
        }
    }

    @Test
    void hugeDecorationRejected() {
        JsonObject m = SkinInspector.vanilla(false);
        bone(m, "head").getAsJsonArray("cubes")
                .add(JsonParser.parseString("{\"origin\":[-50,24,-4],\"size\":[100,8,8],\"uv\":[0,0]}"));
        assertEquals("decoration-out-of-bounds", check(m).reason());
    }

    @Test
    void excessiveDecorationAreaAndCubeLimitsRejected() {
        JsonObject m = SkinInspector.vanilla(false);
        JsonElement extra = JsonParser.parseString("{\"origin\":[-4,32,-1],\"size\":[2,2,2],\"uv\":[0,0]}");
        for (int i = 0; i < 22; i++)
            bone(m, "head").getAsJsonArray("cubes").add(extra.deepCopy());
        assertEquals("too-much-decoration", check(m).reason());
        config.maxCubes = 6;
        assertEquals("too-many-cubes", check(SkinInspector.vanilla(false)).reason());
    }

    @Test
    void nonFiniteGeometryAndOutsideUVRejected() {
        JsonObject m = SkinInspector.vanilla(false);
        cube(m, "head").add("origin", JsonParser.parseString("[\"NaN\",24,-4]"));
        assertEquals("invalid-number", check(m).reason());
        config.geometryMode = CheckConfig.GeometryMode.ALLOW;
        m = SkinInspector.vanilla(false);
        cube(m, "head").add("uv", JsonParser.parseString("[100,100]"));
        assertEquals("uv-outside-texture", check(m).reason());
    }

    @Test
    void semiTransparentAndBodyAggregateThresholds() {
        byte[] data = opaque(64, 64);
        for (int i = 3; i < data.length; i += 4)
            data[i] = (byte) 199;
        assertFalse(new SkinInspector(config).inspect(input(null, data, 64, 64, false)).allowed());
        config.alphaThreshold = 199;
        assertTrue(new SkinInspector(config).inspect(input(null, data, 64, 64, false)).allowed());
        data = opaque(64, 64);
        for (int y = 8; y < 16; y++)
            data[(y * 64 + 8) * 4 + 3] = 0;
        config.minimumBodyOpacity = 1;
        assertEquals("transparent-body-part:head",
                new SkinInspector(config).inspect(input(null, data, 64, 64, false)).reason());
    }

    @Test
    void disabledConfigurationSkipsInspection() {
        config.enabled = false;
        assertTrue(new SkinInspector(config).inspect(null).allowed());
    }

    @Test
    void duplicateAndCycleRejected() {
        JsonObject m = SkinInspector.vanilla(false);
        m.getAsJsonArray("bones").add(bone(m, "head").deepCopy());
        assertEquals("duplicate-bone", check(m).reason());
        m = SkinInspector.vanilla(false);
        bone(m, "root").addProperty("parent", "head");
        assertEquals("cyclic-bone-parent", check(m).reason());
    }

    @Test
    void missingHeadRejected() {
        JsonObject m = SkinInspector.vanilla(false);
        bone(m, "head").remove("cubes");
        assertEquals("changed-body-cube:head", check(m).reason());
    }

    @Test
    void limitsAndMalformedInputRejected() {
        var inspector = new SkinInspector(config);
        assertFalse(inspector.inspect(input(null, new byte[0], 64, 64, false)).allowed());
        assertFalse(inspector.inspect(new SkinInput(64, 64, opaque(64, 64), "{}", "", false, false, "")).allowed());
        assertFalse(inspector.inspect(new SkinInput(64, 64, opaque(64, 64), "[".repeat(40), "", false, false, ""))
                .allowed());
        assertFalse(inspector.inspect(
                new SkinInput(64, 64, opaque(64, 64), "x".repeat(config.maxGeometryBytes + 1), "", false, false, ""))
                .allowed());
        JsonObject m = SkinInspector.vanilla(false);
        config.maxBones = 6;
        assertFalse(check(m).allowed());
    }

    @Test
    void allowCustomStillChecksAlphaUsingActualUV() {
        config.geometryMode = CheckConfig.GeometryMode.ALLOW;
        JsonObject m = SkinInspector.vanilla(false);
        cube(m, "head").add("size", JsonParser.parseString("[1,1,1]"));
        assertTrue(check(m).allowed());
        assertFalse(new SkinInspector(config).inspect(input(m, new byte[64 * 64 * 4], 64, 64, false)).allowed());
    }

    @Test
    void animatedSkinsRejectedAndPersonaExplicit() {
        var inspector = new SkinInspector(config);
        assertFalse(inspector.inspect(new SkinInput(64, 64, opaque(64, 64), "", "", false, true, "")).allowed());
        config.allowPersona = true;
        config.geometryMode = CheckConfig.GeometryMode.ALLOW;
        config.transparencyCheck = false;
        assertTrue(inspector.inspect(new SkinInput(64, 64, opaque(64, 64), "", "", false, true, "")).allowed());
        assertFalse(inspector.inspect(new SkinInput(64, 64, opaque(64, 64), "", "", false, false, "{} ")).allowed());
    }

    @Test
    void legacyGeometrySelectionSupported() {
        JsonObject modern = SkinInspector.vanilla(false), old = new JsonObject();
        old.addProperty("texturewidth", 64);
        old.addProperty("textureheight", 64);
        old.add("bones", modern.get("bones"));
        JsonObject root = new JsonObject();
        root.add("geometry.test", old);
        var result = new SkinInspector(config).inspect(new SkinInput(64, 64, opaque(64, 64),
                "{\"geometry\":{\"default\":\"geometry.test\"}}", root.toString(), false, false, ""));
        assertTrue(result.allowed(), result.reason());
    }

    @Test
    void unselectedGeometryDoesNotAffectSelectedModel() {
        var skin = input(SkinInspector.vanilla(false), opaque(64, 64), 64, 64, false);
        JsonObject root = JsonParser.parseString(skin.geometry()).getAsJsonObject();
        JsonObject evil = SkinInspector.vanilla(false);
        evil.getAsJsonObject("description").addProperty("identifier", "geometry.unselected");
        cube(evil, "body").add("origin", JsonParser.parseString("[0,-999999,0]"));
        root.getAsJsonArray("minecraft:geometry").add(evil);
        assertTrue(new SkinInspector(config)
                .inspect(new SkinInput(64, 64, skin.rgba(), skin.resourcePatch(), root.toString(), false, false, ""))
                .allowed());
    }
}
