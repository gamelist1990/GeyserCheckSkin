package com.pexserver.checkskin;

/**
 * Bedrock skin pixels are RGBA (alpha is byte 3), despite the API's ARGB
 * documentation.
 */
public record SkinInput(int width, int height, byte[] rgba, String resourcePatch,
        String geometry, boolean slim, boolean persona, String animationData) {
}
