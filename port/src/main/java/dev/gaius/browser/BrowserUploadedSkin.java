package dev.gaius.browser;

import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.Base64;

/** Decodes a browser-local skin before vanilla's HTTP texture download path. */
public final class BrowserUploadedSkin {
    private static final String PREFIX = "data:image/png;base64,";

    private BrowserUploadedSkin() {
    }

    public static boolean isUploadedSkin(String url) {
        return url != null && url.startsWith(PREFIX) && url.length() <= 16_384;
    }

    /**
     * Signature state SkinManager uses to mark a skin as secure. Remote players
     * only render secure skins, and uploaded skins can never carry a Mojang
     * signature, so without this every other player saw a default skin. Only a
     * lone uploaded data:image/png skin (no cape or elytra) is treated as
     * trusted; every other texture set keeps its real signature state.
     */
    public static SignatureState skinSignatureState(MinecraftProfileTextures textures) {
        MinecraftProfileTexture skin = textures.skin();
        if (skin != null && textures.cape() == null && textures.elytra() == null
                && isUploadedSkin(skin.getUrl())) {
            return SignatureState.SIGNED;
        }
        return textures.signatureState();
    }

    public static NativeImage readUploadedSkin(String url) throws IOException {
        if (!isUploadedSkin(url)) {
            throw new IOException("Uploaded skin URL is invalid");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(url.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Uploaded skin PNG is invalid", exception);
        }
        if (bytes.length < 24 || bytes.length > 12_000) {
            throw new IOException("Uploaded skin PNG exceeds the supported size");
        }
        NativeImage image = NativeImage.read(bytes);
        if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
            image.close();
            throw new IOException("Uploaded skin must be 64x64 or 64x32 pixels");
        }
        return image;
    }
}
