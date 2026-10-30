package dev.gaius.browser;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Browser-safe Gson decoding for authlib classes without no-argument constructors. */
public final class BrowserAuthlibGson {
    private BrowserAuthlibGson() {
    }

    public static JsonDeserializer<MinecraftProfileTexture> textureDeserializer() {
        return TextureDeserializer.INSTANCE;
    }

    /** Decodes the authlib texture property without TeaVM's incomplete Gson reflection metadata. */
    public static MinecraftTexturesPayload decodeTextures(String json) throws JsonParseException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures =
                new EnumMap<>(MinecraftProfileTexture.Type.class);
        if (root.has("textures") && root.get("textures").isJsonObject()) {
            for (var entry : root.getAsJsonObject("textures").entrySet()) {
                try {
                    textures.put(MinecraftProfileTexture.Type.valueOf(entry.getKey()),
                            TextureDeserializer.INSTANCE.deserialize(entry.getValue(),
                                    MinecraftProfileTexture.class, null));
                } catch (IllegalArgumentException ignored) {
                    // Unknown upstream texture types are irrelevant to the vanilla client.
                }
            }
        }
        return new MinecraftTexturesPayload(0L, null, "", false, textures);
    }

    private enum TextureDeserializer implements JsonDeserializer<MinecraftProfileTexture> {
        INSTANCE;

        @Override
        public MinecraftProfileTexture deserialize(
                JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
            if (!json.isJsonObject()) {
                throw new JsonParseException("Minecraft profile texture must be an object");
            }
            JsonObject object = json.getAsJsonObject();
            String url = object.has("url") && !object.get("url").isJsonNull()
                    ? object.get("url").getAsString()
                    : "";
            if (!object.has("metadata") || !object.get("metadata").isJsonObject()) {
                return new MinecraftProfileTexture(url, Collections.emptyMap());
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            for (var entry : object.getAsJsonObject("metadata").entrySet()) {
                if (!entry.getValue().isJsonNull()) {
                    metadata.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return new MinecraftProfileTexture(url, metadata);
        }
    }
}
