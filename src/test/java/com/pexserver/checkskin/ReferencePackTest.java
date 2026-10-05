package com.pexserver.checkskin;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ReferencePackTest {
    @Test
    void suppliedPacksCheckedByContent() throws Exception {
        for (String name : java.util.List.of("Inchman.mcpack", "Yoshi.mcpack", "ChibiGirls.mcpack")) {
            Path path = Path.of(name);
            Assumptions.assumeTrue(Files.exists(path), "Local reference pack missing: " + name);
            try (var zip = new ZipFile(path.toFile())) {
                JsonObject manifest = JsonParser
                        .parseString(new String(zip.getInputStream(zip.getEntry("skins.json")).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String geometry = zip.getEntry("geometry.json") == null ? ""
                        : new String(zip.getInputStream(zip.getEntry("geometry.json")).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                int checked = 0;
                for (JsonElement entry : manifest.getAsJsonArray("skins")) {
                    JsonObject skin = entry.getAsJsonObject();
                    String id = skin.get("geometry").getAsString(), texture = skin.get("texture").getAsString();
                    var image = ImageIO.read(zip.getInputStream(zip.getEntry(texture)));
                    byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
                    for (int y = 0; y < image.getHeight(); y++)
                        for (int x = 0; x < image.getWidth(); x++) {
                            int color = image.getRGB(x, y), i = (y * image.getWidth() + x) * 4;
                            rgba[i] = (byte) (color >> 16);
                            rgba[i + 1] = (byte) (color >> 8);
                            rgba[i + 2] = (byte) color;
                            rgba[i + 3] = (byte) (color >> 24);
                        }
                    SkinInput input = new SkinInput(image.getWidth(), image.getHeight(), rgba,
                            "{\"geometry\":{\"default\":\"" + id + "\"}}", geometry, id.endsWith("Slim"), false, "");
                    var result = new SkinInspector(new CheckConfig()).inspect(input);
                    if (name.equals("ChibiGirls.mcpack"))
                        assertTrue(result.allowed(), name + " / " + texture + ": " + result.reason());
                    else
                        assertFalse(result.allowed(), name + " / " + texture);
                    System.out.println(name + " / " + texture + ": " + result.reason());
                    checked++;
                }
                assertTrue(checked > 0);
            }
        }
    }
}
