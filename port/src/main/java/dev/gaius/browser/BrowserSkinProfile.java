package dev.gaius.browser;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.teavm.jso.JSBody;

/** Applies the public, short-lived textures property supplied by the browser shell. */
public final class BrowserSkinProfile {
    private BrowserSkinProfile() {
    }

    /** Adds the host profile's public textures property to a freshly-created login profile. */
    public static GameProfile apply(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        String profileName = profile.name();
        String profileUuid = profile.id() == null
                ? "" : profile.id().toString().replace("-", "").toLowerCase();
        String ownerName = descriptorName(profileUuid, profileName);
        String ownerUuid = descriptorUuid(profileUuid, profileName);
        if (ownerName == null || !ownerName.equals(profileName)
                || ownerUuid == null || !ownerUuid.equals(profileUuid)) {
            return profile;
        }
        String value = descriptorValue(profileUuid, profileName);
        if (value == null || value.isBlank() || value.length() > 16_384) {
            return profile;
        }
        String signature = descriptorSignature(profileUuid, profileName);
        // GameProfile(UUID, String) uses PropertyMap.EMPTY in authlib 9.x.
        // PropertyMap copies constructor input to an immutable multimap, so
        // use a delegate override for the browser supplied texture.
        MutablePropertyMap properties = new MutablePropertyMap();
        properties.put("textures", signature == null || signature.isBlank()
                ? new Property("textures", value)
                : new Property("textures", value, signature));
        return new GameProfile(profile.id(), profile.name(), properties);
    }

    /** A profile carrying one unsigned textures property (used for the profile screen preview). */
    public static GameProfile withTextures(java.util.UUID id, String name, String texturesValue) {
        if (texturesValue == null || texturesValue.isBlank()) {
            return new GameProfile(id, name);
        }
        MutablePropertyMap properties = new MutablePropertyMap();
        properties.put("textures", new Property("textures", texturesValue));
        return new GameProfile(id, name, properties);
    }

    /** PropertyMap's constructor freezes its input; this override keeps the login map mutable. */
    private static final class MutablePropertyMap extends PropertyMap {
        private final Multimap<String, Property> mutable = ArrayListMultimap.create();

        private MutablePropertyMap() {
            super(ArrayListMultimap.create());
        }

        @Override
        protected Multimap<String, Property> delegate() {
            return mutable;
        }
    }

    @JSBody(params = {"uuid", "username"}, script = """
            const profileUuid = String(uuid || '').replaceAll('-', '').toLowerCase();
            const profileName = String(username || '');
            const remote = globalThis.__gaiusRemoteSkinDescriptors;
            const remoteKey = profileUuid + ':' + profileName;
            const local = globalThis.__gaiusSkinDescriptor &&
              String(globalThis.__gaiusSkinDescriptor.uuid || '').replaceAll('-', '').toLowerCase() === profileUuid &&
              String(globalThis.__gaiusSkinDescriptor.username || '') === profileName
                ? globalThis.__gaiusSkinDescriptor : null;
            const descriptor = (remote && typeof remote === 'object' &&
              (remote[remoteKey] || (remote[profileUuid] &&
                String(remote[profileUuid].username || '') === profileName
                ? remote[profileUuid] : null))) || local;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.value || '') : '';
            """)
    private static native String descriptorValue(String uuid, String username);

    @JSBody(params = {"uuid", "username"}, script = """
            const profileUuid = String(uuid || '').replaceAll('-', '').toLowerCase();
            const profileName = String(username || '');
            const remote = globalThis.__gaiusRemoteSkinDescriptors;
            const remoteKey = profileUuid + ':' + profileName;
            const local = globalThis.__gaiusSkinDescriptor &&
              String(globalThis.__gaiusSkinDescriptor.uuid || '').replaceAll('-', '').toLowerCase() === profileUuid &&
              String(globalThis.__gaiusSkinDescriptor.username || '') === profileName
                ? globalThis.__gaiusSkinDescriptor : null;
            const descriptor = (remote && typeof remote === 'object' &&
              (remote[remoteKey] || (remote[profileUuid] &&
                String(remote[profileUuid].username || '') === profileName
                ? remote[profileUuid] : null))) || local;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.signature || '') : '';
            """)
    private static native String descriptorSignature(String uuid, String username);

    @JSBody(params = {"uuid", "username"}, script = """
            const profileUuid = String(uuid || '').replaceAll('-', '').toLowerCase();
            const profileName = String(username || '');
            const remote = globalThis.__gaiusRemoteSkinDescriptors;
            const remoteKey = profileUuid + ':' + profileName;
            const local = globalThis.__gaiusSkinDescriptor &&
              String(globalThis.__gaiusSkinDescriptor.uuid || '').replaceAll('-', '').toLowerCase() === profileUuid &&
              String(globalThis.__gaiusSkinDescriptor.username || '') === profileName
                ? globalThis.__gaiusSkinDescriptor : null;
            const descriptor = (remote && typeof remote === 'object' &&
              (remote[remoteKey] || (remote[profileUuid] &&
                String(remote[profileUuid].username || '') === profileName
                ? remote[profileUuid] : null))) || local;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.username || '') : '';
            """)
    private static native String descriptorName(String uuid, String username);

    @JSBody(params = {"uuid", "username"}, script = """
            const profileUuid = String(uuid || '').replaceAll('-', '').toLowerCase();
            const profileName = String(username || '');
            const remote = globalThis.__gaiusRemoteSkinDescriptors;
            const remoteKey = profileUuid + ':' + profileName;
            const local = globalThis.__gaiusSkinDescriptor &&
              String(globalThis.__gaiusSkinDescriptor.uuid || '').replaceAll('-', '').toLowerCase() === profileUuid &&
              String(globalThis.__gaiusSkinDescriptor.username || '') === profileName
                ? globalThis.__gaiusSkinDescriptor : null;
            const descriptor = (remote && typeof remote === 'object' &&
              (remote[remoteKey] || (remote[profileUuid] &&
                String(remote[profileUuid].username || '') === profileName
                ? remote[profileUuid] : null))) || local;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.uuid || '') : '';
            """)
    private static native String descriptorUuid(String uuid, String username);
}
