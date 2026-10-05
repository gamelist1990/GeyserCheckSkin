package com.pexserver.checkskin;

import org.geysermc.geyser.api.skin.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

public record FallbackSkin(byte[] rgba, String id) {
    private static final Set<String> BUNDLED_FILES = Set.of(
            "fallbacks/skin1.png", "fallbacks/skin2.png", "fallbacks/skin3.png");

    public static List<FallbackSkin> loadAll(Path folder, CheckConfig config) throws IOException {
        List<FallbackSkin> skins = new ArrayList<>();
        for (String file : config.fallbackFiles)
            skins.add(load(folder, file));
        return List.copyOf(skins);
    }

    public static FallbackSkin load(Path folder, String file) throws IOException {
        Path base = folder.toAbsolutePath().normalize();
        Path imagePath = base.resolve(file).normalize();
        if (!imagePath.startsWith(base))
            throw new IOException("fallbackFiles paths must be inside the extension folder: " + file);
        if (!Files.exists(imagePath) && BUNDLED_FILES.contains(file.replace('\\', '/'))) {
            Files.createDirectories(imagePath.getParent());
            try (var in = FallbackSkin.class.getResourceAsStream("/" + file.replace('\\', '/'))) {
                if (in == null)
                    throw new IOException("Missing bundled fallback: " + file);
                Files.copy(in, imagePath);
            }
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null || image.getWidth() != 64 || image.getHeight() != 64)
            throw new IOException("Fallback must be a 64x64 PNG: " + file);
        byte[] rgba = new byte[64 * 64 * 4];
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                int color = image.getRGB(x, y), offset = (y * 64 + x) * 4;
                rgba[offset] = (byte) (color >> 16);
                rgba[offset + 1] = (byte) (color >> 8);
                rgba[offset + 2] = (byte) color;
                rgba[offset + 3] = (byte) (color >> 24);
            }
        CheckConfig strict = new CheckConfig();
        strict.geometryMode = CheckConfig.GeometryMode.DENY;
        strict.minimumBodyOpacity = 1;
        strict.minimumFaceOpacity = 1;
        strict.alphaThreshold = 255;
        var result = new SkinInspector(strict).inspect(new SkinInput(64, 64, rgba,
                SkinGeometry.WIDE.geometryName(), "", false, false, ""));
        if (!result.allowed())
            throw new IOException("Fallback has transparent base surfaces: " + file + ": " + result.reason());
        try {
            String id = "checkskin:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba));
            return new FallbackSkin(rgba, id);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public SkinData data() {
        return new SkinData(new Skin(id, rgba), new Cape("", "", new byte[0]), SkinGeometry.WIDE);
    }
}
