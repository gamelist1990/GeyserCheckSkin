package com.pexserver.checkskin;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Conservative structural policy: unsupported render features cannot establish
 * a safe humanoid.
 */
public final class SkinInspector {
    private static final Set<String> BODY = Set.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static final Map<Boolean, JsonObject> VANILLA = loadVanilla();
    private final CheckConfig config;

    public SkinInspector(CheckConfig config) {
        this.config = config;
    }

    public record Result(boolean allowed, String reason) {
        static Result pass() {
            return new Result(true, "OK");
        }

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    public Result inspect(SkinInput skin) {
        if (!config.enabled)
            return Result.pass();
        try {
            require(skin != null && skin.rgba() != null, "missing-skin");
            require((skin.width() == 64 || skin.width() == 128)
                    && (skin.height() == skin.width() || skin.height() * 2 == skin.width()),
                    "invalid-texture-dimensions");
            require(skin.rgba().length == skin.width() * skin.height() * 4, "invalid-pixel-length");
            require(!skin.persona() || config.allowPersona, "persona-disabled");
            require(!skin.persona() || (config.geometryMode == CheckConfig.GeometryMode.ALLOW
                    && !config.transparencyCheck), "persona-requires-ALLOW-and-transparency-disabled");
            if (skin.persona())
                return Result.pass();
            require(blank(skin.animationData()) || skin.animationData().equals("[]"), "unsupported-skin-animation");
            JsonObject patch = parse(skin.resourcePatch());
            require(patch.keySet().equals(Set.of("geometry")), "unsupported-resource-patch");
            JsonObject geometryPatch = patch.getAsJsonObject("geometry");
            require(geometryPatch.keySet().equals(Set.of("default")), "unsupported-geometry-patch");
            String id = geometryPatch.get("default").getAsString();
            JsonObject model;
            boolean builtin = blank(skin.geometry());
            if (builtin) {
                require(id.equals("geometry.humanoid.custom") || id.equals("geometry.humanoid.customSlim"),
                        "unknown-builtin-geometry");
                model = vanilla(id.endsWith("Slim"));
            } else {
                model = select(parse(skin.geometry()), id);
            }
            JsonObject description = model.getAsJsonObject("description");
            int tw = description.get("texture_width").getAsInt();
            int th = description.get("texture_height").getAsInt();
            require(tw > 0 && th > 0 && tw <= 1024 && th <= 1024, "invalid-geometry-texture-size");
            Map<String, JsonObject> bones = bones(model);
            int cubes = 0;
            for (JsonObject bone : bones.values()) {
                if (bone.has("cubes"))
                    cubes += bone.getAsJsonArray("cubes").size();
            }
            require(cubes <= config.maxCubes, "too-many-cubes");
            if (config.geometryMode != CheckConfig.GeometryMode.ALLOW) {
                validateHumanoid(model, bones, skin.slim());
                require(tw == 64 && (th == 64 || th == 32), "nonstandard-uv-layout");
            }
            if (config.transparencyCheck) {
                checkOpacity(skin, bones, tw, th, builtin);
            }
            return Result.pass();
        } catch (Rejected e) {
            return Result.fail(e.getMessage());
        } catch (RuntimeException e) {
            return Result.fail("malformed-skin-data");
        }
    }

    public static JsonObject vanilla(boolean slim) {
        return VANILLA.get(slim).deepCopy();
    }

    private JsonObject parse(String text) {
        require(text != null && text.getBytes(StandardCharsets.UTF_8).length <= config.maxGeometryBytes,
                "geometry-too-large-or-missing");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (char c : text.toCharArray()) {
            if (quoted) {
                if (escaped)
                    escaped = false;
                else if (c == '\\')
                    escaped = true;
                else if (c == '"')
                    quoted = false;
            } else if (c == '"')
                quoted = true;
            else if (c == '{' || c == '[')
                require(++depth <= 32, "geometry-too-deep");
            else if (c == '}' || c == ']')
                depth--;
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private JsonObject select(JsonObject document, String id) {
        JsonObject selected = null;
        if (document.has("minecraft:geometry")) {
            for (JsonElement entry : document.getAsJsonArray("minecraft:geometry")) {
                JsonObject model = entry.getAsJsonObject();
                if (model.getAsJsonObject("description").get("identifier").getAsString().equals(id)) {
                    require(selected == null, "duplicate-geometry-identifier");
                    selected = model;
                }
            }
        } else if (document.has(id)) {
            require(!id.contains(":"), "unsupported-geometry-inheritance");
            JsonObject old = document.getAsJsonObject(id);
            selected = new JsonObject();
            JsonObject d = new JsonObject();
            d.addProperty("identifier", id);
            d.add("texture_width", old.get("texturewidth"));
            d.add("texture_height", old.get("textureheight"));
            for (String key : old.keySet()) {
                if (key.startsWith("visible_bounds_"))
                    d.add(key, old.get(key));
                else if (!Set.of("texturewidth", "textureheight", "bones").contains(key))
                    require(config.geometryMode == CheckConfig.GeometryMode.ALLOW, "unsupported-legacy-property");
            }
            selected.add("description", d);
            selected.add("bones", old.get("bones"));
        }
        require(selected != null, "selected-geometry-missing");
        return selected;
    }

    private Map<String, JsonObject> bones(JsonObject model) {
        JsonArray array = model.getAsJsonArray("bones");
        require(array != null && array.size() <= config.maxBones, "too-many-or-missing-bones");
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement entry : array) {
            JsonObject bone = entry.getAsJsonObject();
            String name = bone.get("name").getAsString();
            require(result.put(name, bone) == null, "duplicate-bone");
        }
        for (String name : result.keySet()) {
            Set<String> seen = new HashSet<>();
            String current = name;
            while (current != null) {
                require(seen.add(current), "cyclic-bone-parent");
                JsonObject b = result.get(current);
                require(b != null, "missing-bone-parent");
                current = b.has("parent") ? b.get("parent").getAsString() : null;
            }
        }
        return result;
    }

    private void validateHumanoid(JsonObject model, Map<String, JsonObject> actual, boolean slim) {
        require(model.keySet().equals(Set.of("description", "bones")), "unsupported-model-property");
        JsonObject d = model.getAsJsonObject("description");
        require(Set.of("identifier", "texture_width", "texture_height", "visible_bounds_width",
                "visible_bounds_height", "visible_bounds_offset").containsAll(d.keySet()),
                "unsupported-description-property");
        if (d.has("visible_bounds_width"))
            require(number(d.get("visible_bounds_width")) <= 4
                    && number(d.get("visible_bounds_width")) >= 1, "invalid-visible-bounds");
        if (d.has("visible_bounds_height"))
            require(number(d.get("visible_bounds_height")) <= 4
                    && number(d.get("visible_bounds_height")) >= 2, "invalid-visible-bounds");
        if (d.has("visible_bounds_offset"))
            require(vectorEquals(d.get("visible_bounds_offset"), new double[] { 0, 1, 0 }), "shifted-visible-bounds");
        Map<String, JsonObject> expected = bones(vanilla(slim));
        require(actual.keySet()
                .containsAll(Set.of("root", "waist", "body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg")),
                "missing-body-part");
        double decorationArea = 0;
        for (var entry : actual.entrySet()) {
            String name = entry.getKey();
            JsonObject bone = entry.getValue();
            require(Set.of("name", "parent", "pivot", "cubes", "rotation", "mirror", "locators")
                    .containsAll(bone.keySet()), "unsupported-bone-property:" + name);
            require(!bone.has("rotation") || vectorEquals(bone.get("rotation"), new double[] { 0, 0, 0 }),
                    "rotated-bone:" + name);
            require(!bone.has("mirror") || !bone.get("mirror").getAsBoolean(), "mirrored-bone:" + name);
            JsonObject base = expected.get(name);
            require(!bone.has("locators")
                    || (base != null && Objects.equals(bone.get("locators"), base.get("locators"))),
                    "unsupported-locators:" + name);
            if (base != null) {
                require(Objects.equals(bone.get("parent"), base.get("parent")), "changed-body-parent:" + name);
                require(vectorEquals(bone.get("pivot"), vector(base.get("pivot"))), "changed-body-pivot:" + name);
            } else {
                require(config.geometryMode == CheckConfig.GeometryMode.MINOR, "custom-bone-disabled");
                if (bone.has("pivot"))
                    vector(bone.get("pivot"));
            }
            boolean requiredFound = !BODY.contains(name);
            JsonArray list = bone.has("cubes") ? bone.getAsJsonArray("cubes") : new JsonArray();
            for (JsonElement element : list) {
                JsonObject cube = element.getAsJsonObject();
                require(Set.of("origin", "size", "uv", "inflate", "mirror").containsAll(cube.keySet()),
                        "unsupported-cube-property:" + name);
                require(!cube.has("mirror") || !cube.get("mirror").getAsBoolean(), "mirrored-cube:" + name);
                double[] origin = vector(cube.get("origin")), size = vector(cube.get("size"));
                double inflate = cube.has("inflate") ? number(cube.get("inflate")) : 0;
                require(inflate >= 0 && inflate <= config.decorationMargin + .5, "invalid-inflate");
                boolean standard = false;
                if (base != null && base.has("cubes")) {
                    for (JsonElement reference : base.getAsJsonArray("cubes")) {
                        if (sameCube(cube, reference.getAsJsonObject()))
                            standard = true;
                    }
                }
                if (standard)
                    requiredFound = true;
                else {
                    require(config.geometryMode == CheckConfig.GeometryMode.MINOR, "custom-cube-disabled");
                    for (int axis = 0; axis < 3; axis++) {
                        double lower = axis == 1 ? 0 : -8;
                        double upper = axis == 1 ? 32 : 8;
                        if (axis == 2) {
                            lower = -4;
                            upper = 4;
                        }
                        require(size[axis] >= 0 && origin[axis] - inflate >= lower - config.decorationMargin
                                && origin[axis] + size[axis] + inflate <= upper + config.decorationMargin,
                                "decoration-out-of-bounds");
                    }
                    decorationArea += 2 * ((size[0] + 2 * inflate) * (size[1] + 2 * inflate)
                            + (size[0] + 2 * inflate) * (size[2] + 2 * inflate)
                            + (size[1] + 2 * inflate) * (size[2] + 2 * inflate));
                    require(cube.get("uv").isJsonArray() && cube.getAsJsonArray("uv").size() == 2,
                            "unsupported-decoration-uv");
                }
            }
            require(requiredFound, "changed-body-cube:" + name);
        }
        require(decorationArea <= config.maxDecorationSurfaceArea, "too-much-decoration");
    }

    private boolean sameCube(JsonObject a, JsonObject b) {
        if (!vectorEquals(a.get("origin"), vector(b.get("origin")))
                || !vectorEquals(a.get("size"), vector(b.get("size"))))
            return false;
        if (!a.has("uv") || !a.get("uv").equals(b.get("uv")))
            return false;
        double ai = a.has("inflate") ? number(a.get("inflate")) : 0;
        double bi = b.has("inflate") ? number(b.get("inflate")) : 0;
        return Math.abs(ai - bi) < .001;
    }

    private void checkOpacity(SkinInput skin, Map<String, JsonObject> bones, int tw, int th, boolean builtin) {
        int checked = 0;
        boolean humanoid = builtin || config.geometryMode != CheckConfig.GeometryMode.ALLOW
                || hasCanonicalBody(bones, skin.slim());
        for (var entry : bones.entrySet()) {
            if (humanoid && !BODY.contains(entry.getKey()))
                continue;
            JsonObject bone = entry.getValue();
            if (!bone.has("cubes"))
                continue;
            long[] total = new long[2];
            for (JsonElement element : bone.getAsJsonArray("cubes")) {
                JsonObject cube = element.getAsJsonObject();
                if (humanoid) {
                    JsonObject expected = bones(vanilla(skin.slim())).get(entry.getKey()).getAsJsonArray("cubes").get(0)
                            .getAsJsonObject();
                    if (!sameCube(cube, expected))
                        continue;
                    if (skin.height() * 2 == skin.width() && entry.getKey().startsWith("left")) {
                        String right = entry.getKey().replace("left", "right");
                        cube = cube.deepCopy();
                        cube.add("uv", bones(vanilla(skin.slim())).get(right).getAsJsonArray("cubes").get(0)
                                .getAsJsonObject().get("uv"));
                    }
                }
                List<double[]> faces = faces(cube);
                for (double[] face : faces) {
                    if (face[2] == 0 || face[3] == 0)
                        continue;
                    long[] count = sample(skin, face, tw, (builtin && skin.height() * 2 == skin.width()) ? 64 : th);
                    require(count[1] > 0 && (double) count[0] / count[1] >= config.minimumFaceOpacity,
                            "transparent-face:" + entry.getKey());
                    total[0] += count[0];
                    total[1] += count[1];
                }
            }
            if (total[1] > 0) {
                checked++;
                require((double) total[0] / total[1] >= config.minimumBodyOpacity,
                        "transparent-body-part:" + entry.getKey());
            }
        }
        require(checked > 0 && (!humanoid || checked == 6), "no-checkable-body-surfaces");
    }

    private boolean hasCanonicalBody(Map<String, JsonObject> actual, boolean slim) {
        Map<String, JsonObject> expected = bones(vanilla(slim));
        for (String name : BODY) {
            JsonObject bone = actual.get(name);
            if (bone == null || !bone.has("cubes"))
                return false;
            boolean found = false;
            for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                if (sameCube(cube.getAsJsonObject(),
                        expected.get(name).getAsJsonArray("cubes").get(0).getAsJsonObject()))
                    found = true;
            }
            if (!found)
                return false;
        }
        return true;
    }

    private List<double[]> faces(JsonObject cube) {
        double[] s = vector(cube.get("size"));
        require(Arrays.stream(s).allMatch(n -> n >= 0 && n <= 1024), "invalid-cube-size");
        JsonElement uv = cube.get("uv");
        require(uv != null, "missing-cube-uv");
        List<double[]> result = new ArrayList<>();
        if (uv.isJsonArray()) {
            JsonArray a = uv.getAsJsonArray();
            require(a.size() == 2, "invalid-box-uv");
            double u = number(a.get(0)), v = number(a.get(1)), x = s[0], y = s[1], z = s[2];
            result.add(new double[] { u + z, v, x, z });
            result.add(new double[] { u + z + x, v, x, z });
            result.add(new double[] { u, v + z, z, y });
            result.add(new double[] { u + z, v + z, x, y });
            result.add(new double[] { u + z + x, v + z, z, y });
            result.add(new double[] { u + 2 * z + x, v + z, x, y });
        } else {
            JsonObject a = uv.getAsJsonObject();
            for (var entry : a.entrySet()) {
                require(Set.of("north", "south", "east", "west", "up", "down").contains(entry.getKey()),
                        "unknown-uv-face");
                JsonObject f = entry.getValue().getAsJsonObject();
                JsonArray p = f.getAsJsonArray("uv"), size = f.getAsJsonArray("uv_size");
                require(p.size() == 2 && size != null && size.size() == 2, "unsupported-face-uv");
                result.add(
                        new double[] { number(p.get(0)), number(p.get(1)), number(size.get(0)), number(size.get(1)) });
            }
            require(result.size() == 6, "missing-uv-faces");
        }
        return result;
    }

    private long[] sample(SkinInput skin, double[] face, int tw, int th) {
        double scaleX = (double) skin.width() / tw;
        double scaleY = (double) skin.height() / th;
        if (skin.height() * 2 == skin.width() && th == 64 && tw == 64)
            scaleY = scaleX;
        int x0 = (int) Math.floor(Math.min(face[0], face[0] + face[2]) * scaleX);
        int x1 = (int) Math.ceil(Math.max(face[0], face[0] + face[2]) * scaleX);
        int y0 = (int) Math.floor(Math.min(face[1], face[1] + face[3]) * scaleY);
        int y1 = (int) Math.ceil(Math.max(face[1], face[1] + face[3]) * scaleY);
        require(x0 >= 0 && y0 >= 0 && x1 <= skin.width() && y1 <= skin.height(), "uv-outside-texture");
        long opaque = 0, total = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                total++;
                if ((skin.rgba()[(y * skin.width() + x) * 4 + 3] & 255) >= config.alphaThreshold)
                    opaque++;
            }
        return new long[] { opaque, total };
    }

    private static Map<Boolean, JsonObject> loadVanilla() {
        try (var in = SkinInspector.class.getResourceAsStream("/vanilla-geometry.json")) {
            if (in == null)
                throw new IllegalStateException("Missing vanilla geometry");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<Boolean, JsonObject> models = new HashMap<>();
            for (JsonElement m : root.getAsJsonArray("minecraft:geometry")) {
                JsonObject model = m.getAsJsonObject();
                models.put(model.getAsJsonObject("description").get("identifier").getAsString().endsWith("Slim"),
                        model);
            }
            return Map.copyOf(models);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static double[] vector(JsonElement e) {
        require(e != null && e.isJsonArray() && e.getAsJsonArray().size() == 3, "invalid-vector");
        return new double[] { number(e.getAsJsonArray().get(0)), number(e.getAsJsonArray().get(1)),
                number(e.getAsJsonArray().get(2)) };
    }

    private static boolean vectorEquals(JsonElement e, double[] target) {
        double[] v = vector(e);
        for (int i = 0; i < 3; i++)
            if (Math.abs(v[i] - target[i]) > .001)
                return false;
        return true;
    }

    private static double number(JsonElement e) {
        double value = e.getAsDouble();
        require(Double.isFinite(value) && Math.abs(value) <= 1_000_000, "invalid-number");
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void require(boolean value, String reason) {
        if (!value)
            throw new Rejected(reason);
    }

    private static final class Rejected extends RuntimeException {
        Rejected(String reason) {
            super(reason);
        }
    }
}
