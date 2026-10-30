package dev.gaius.browser.kernel.mesh;

import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.client.renderer.chunk.SnapshotRegion;

public final class MeshGolden {
    static final String[] FAMILIES = {"solid", "fluid", "waterlogged", "transparent", "connected",
        "variant", "biome-tint", "height-edge", "mixed", "empty"};
    static final sun.misc.Unsafe UNSAFE;
    static int semantics;
    static Object sprite;
    static Object baked;
    static Object colors;
    static Object compiler;
    static final Map<BlockState, Object> models = new HashMap<>();
    static final Map<BlockState, List<Object>> parts = new HashMap<>();
    static final Set<BlockState> multipart = new HashSet<>();
    static {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static Object blank(String name) throws Exception { return UNSAFE.allocateInstance(Class.forName(name)); }
    public static void field(Object target, String name, Object value) throws Exception {
        for (Class<?> owner = target.getClass(); owner != null; owner = owner.getSuperclass()) {
            try {
                Field found = owner.getDeclaredField(name);
                found.setAccessible(true); found.set(target, value); return;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    static Object call(Object target, String name, Object... arguments) throws Exception {
        Class<?> owner = target instanceof Class<?> type ? type : target.getClass();
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < types.length; index++) {
                if (arguments[index] != null && !types[index].isPrimitive() && !types[index].isInstance(arguments[index])) compatible = false;
            }
            if (!compatible) continue;
            try { return method.invoke(target instanceof Class<?> ? null : target, arguments); }
            catch (InvocationTargetException failure) { throw new RuntimeException(name, failure.getCause()); }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
    static Object constant(String owner, String name) throws Exception { return Class.forName(owner).getField(name).get(null); }
    static Object construct(String name, Object... arguments) throws Exception {
        for (Constructor<?> constructor : Class.forName(name).getDeclaredConstructors()) {
            if (constructor.getParameterCount() == arguments.length) {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            }
        }
        throw new NoSuchMethodException(name);
    }
    static Object proxy(String name, InvocationHandler handler) throws Exception {
        return Proxy.newProxyInstance(MeshGolden.class.getClassLoader(), new Class<?>[]{Class.forName(name)}, handler);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static BlockState property(BlockState state, String name, String value) {
        for (Property candidate : state.getProperties()) {
            if (candidate.getName().equals(name)) return state.setValue(candidate, (Comparable) candidate.getValue(value).orElseThrow());
        }
        throw new IllegalArgumentException(name);
    }
    static Object layer(String name) throws Exception { return constant("net.minecraft.client.renderer.chunk.ChunkSectionLayer", name); }
    static List<Object> quads(String layerName, int tint, int variant, float[] box) throws Exception {
        float[][] corners = {
            {0,0,1, 0,0,0, 1,0,0, 1,0,1}, {0,1,0, 0,1,1, 1,1,1, 1,1,0},
            {1,1,0, 1,0,0, 0,0,0, 0,1,0}, {0,1,1, 0,0,1, 1,0,1, 1,1,1},
            {0,1,0, 0,0,0, 0,0,1, 0,1,1}, {1,1,1, 1,0,1, 1,0,0, 1,1,0}};
        List<Object> result = new ArrayList<>();
        for (float[] face : corners) for (int corner = 0; corner < 4; corner++) for (int axis = 0; axis < 3; axis++) {
            int index = corner * 3 + axis;
            face[index] = box[axis] + face[index] * (box[axis + 3] - box[axis]);
        }
        for (Direction direction : Direction.values()) {
            float[] coordinates = corners[direction.ordinal()];
            List<Object> arguments = new ArrayList<>();
            for (int corner = 0; corner < 4; corner++) arguments.add(construct("org.joml.Vector3f",
                coordinates[corner * 3], coordinates[corner * 3 + 1], coordinates[corner * 3 + 2]));
            for (int corner = 0; corner < 4; corner++) {
                float horizontal = (corner == 0 || corner == 1 ? 0 : 1) + variant / 64.0f;
                float vertical = corner == 0 || corner == 3 ? 0 : 1;
                arguments.add(Integer.toUnsignedLong(Float.floatToRawIntBits(horizontal))
                    | Integer.toUnsignedLong(Float.floatToRawIntBits(vertical)) << 32);
            }
            if (semantics == 0) arguments.addAll(Arrays.asList(tint, direction, sprite, true, 0));
            else {
                Constructor<?> constructor = Class.forName("net.minecraft.client.resources.model.geometry.BakedQuad$MaterialInfo").getConstructors()[0];
                Class<?>[] types = constructor.getParameterTypes();
                Object[] material = new Object[types.length];
                for (int index = 0; index < types.length; index++) {
                    if (types[index].getSimpleName().equals("TextureAtlasSprite")) material[index] = sprite;
                    else if (types[index].getSimpleName().equals("ChunkSectionLayer")) material[index] = layer(layerName);
                    else if (types[index] == int.class) material[index] = index == types.length - 1 ? 0 : tint;
                    else if (types[index] == boolean.class) material[index] = true;
                }
                arguments.add(direction);
                arguments.add(constructor.newInstance(material));
            }
            result.add(construct(semantics == 0 ? "net.minecraft.client.renderer.block.model.BakedQuad" : "net.minecraft.client.resources.model.geometry.BakedQuad", arguments.toArray()));
        }
        return result;
    }

    static void model(BlockState state, String layerName, int tint, boolean variant) throws Exception {
        String prefix = semantics == 0 ? "net.minecraft.client.renderer.block.model." : "net.minecraft.client.renderer.block.dispatch.";
        String partName = semantics == 0 ? "BlockModelPart" : "BlockStateModelPart";
        List<Object> choices = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        if (state.getBlock() instanceof net.minecraft.world.level.block.CrossCollisionBlock) {
            multipart.add(state);
            boxes.add(new float[]{0.375f, 0, 0.375f, 0.625f, 1, 0.625f});
            for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                var property = net.minecraft.world.level.block.CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(direction);
                if (!state.getValue(property)) continue;
                boxes.add(switch (direction) {
                    case NORTH -> new float[]{0.4375f, 0.25f, 0, 0.5625f, 0.75f, 0.5f};
                    case SOUTH -> new float[]{0.4375f, 0.25f, 0.5f, 0.5625f, 0.75f, 1};
                    case WEST -> new float[]{0, 0.25f, 0.4375f, 0.5f, 0.75f, 0.5625f};
                    default -> new float[]{0.5f, 0.25f, 0.4375f, 1, 0.75f, 0.5625f};
                });
            }
        } else boxes.add(new float[]{0, 0, 0, 1, state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock ? 0.5f : 1, 1});
        for (int choice = 0; choice < (variant ? 3 : boxes.size()); choice++) {
            List<Object> faces = quads(layerName, tint, variant ? choice : 0, boxes.get(variant ? 0 : choice));
            choices.add(proxy(prefix + partName, (target, method, arguments) -> switch (method.getName()) {
                case "getQuads" -> arguments[0] == null ? List.of() : List.of(faces.get(((Direction) arguments[0]).ordinal()));
                case "useAmbientOcclusion" -> true;
                case "particleMaterial" -> baked;
                case "particleIcon" -> sprite;
                case "materialFlags" -> layerName.equals("TRANSLUCENT") ? 1 : 0;
                case "toString" -> "synthetic-cube-part";
                default -> throw new UnsupportedOperationException(method.toString());
            }));
        }
        parts.put(state, choices);
        List<Object> variants = new ArrayList<>();
        for (Object part : choices) variants.add(construct(prefix + "SingleVariant", part));
        if (multipart.contains(state)) {
            List<Object> selectors = new ArrayList<>();
            java.util.function.Predicate<BlockState> condition = candidate -> candidate == state;
            for (Object model : variants) selectors.add(construct(prefix + "multipart.MultiPartModel$Selector", condition, model));
            Object shared = construct(prefix + "multipart.MultiPartModel$SharedBakedState", selectors);
            models.put(state, construct(prefix + "multipart.MultiPartModel", shared, state));
        } else if (variant) {
            Object builder = construct("net.minecraft.util.random.WeightedList$Builder");
            for (int index = 0; index < variants.size(); index++) call(builder, "add", variants.get(index), index + 1);
            String weighted = semantics == 0 ? "net.minecraft.client.resources.model.WeightedVariants" : prefix + "WeightedVariants";
            models.put(state, construct(weighted, call(builder, "build")));
        } else models.put(state, variants.get(0));
    }

    static BlockState[] initialize() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        sprite = blank("net.minecraft.client.renderer.texture.TextureAtlasSprite");
        Object contents = blank("net.minecraft.client.renderer.texture.SpriteContents");
        field(contents, "width", 16);
        field(contents, "height", 16);
        if (semantics != 0) field(contents, "transparency", constant("com.mojang.blaze3d.platform.Transparency", "NONE"));
        field(sprite, "contents", contents);
        field(sprite, "u0", 0.0f); field(sprite, "u1", 1.0f);
        field(sprite, "v0", 0.0f); field(sprite, "v1", 1.0f);
        if (semantics != 0) baked = construct("net.minecraft.client.resources.model.sprite.Material$Baked", sprite, false);
        colors = call(Class.forName("net.minecraft.client.color.block.BlockColors"), "createDefault");
        Block stained;
        if (semantics == 0) stained = (Block) constant("net.minecraft.world.level.block.Blocks", "WHITE_STAINED_GLASS");
        else stained = (Block) call(constant("net.minecraft.world.level.block.Blocks", "STAINED_GLASS"), "white");
        BlockState[] states = {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            property(Blocks.WATER.defaultBlockState(), "level", "3"), property(Blocks.WATER.defaultBlockState(), "level", "8"),
            Blocks.LAVA.defaultBlockState(), property(Blocks.OAK_FENCE.defaultBlockState(), "waterlogged", "true"),
            Blocks.GLASS.defaultBlockState(), stained.defaultBlockState(),
            property(Blocks.GLASS_PANE.defaultBlockState(), "north", "true"),
            property(property(Blocks.OAK_FENCE.defaultBlockState(), "north", "true"), "east", "true"),
            Blocks.COBBLESTONE.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(), Blocks.ICE.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
        for (int index = 0; index < states.length; index++) {
            String renderLayer = index == 6 || index == 7 || index == 8 || index == 13 ? "TRANSLUCENT" : index == 12 ? "CUTOUT" : "SOLID";
            model(states[index], renderLayer, index == 11 || index == 12 ? 0 : -1, index == 10);
        }
        compiler = ExportModels.compiler(models, models.get(states[0]), sprite, baked, colors);
        return states;
    }

    static int regionIndex(int horizontal, int vertical, int depth) { return ((vertical + 2) * 20 + depth + 2) * 20 + horizontal + 2; }
    static byte[] job(SnapshotRegion region, int number, boolean ambient, boolean cutout) {
        ByteBuffer bytes = ByteBuffer.allocate(50000).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(1).putInt(number).putInt(1).putInt(4 | 16 | 512 | (ambient ? 1 : 0) | (cutout ? 2 : 0));
        bytes.putInt(region.sectionX).putInt(region.sectionY).putInt(region.sectionZ).putInt(region.tint.palette.length);
        for (float shade : new float[]{0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f}) bytes.putFloat(shade);
        bytes.putFloat(3.25f).putFloat(7.5f).putFloat(20.0f).putInt(0).putLong(region.tint.seed).putInt(region.tint.radius);
        bytes.position(96);
        for (BlockState state : region.states) {
            int stateId = Block.getId(state);
            if (stateId > 65535) throw new IllegalStateException("snapshot needs wide state IDs");
            bytes.putShort((short) stateId);
        }
        bytes.put(region.light).put(region.tint.quarts);
        while ((bytes.position() & 7) != 0) bytes.put((byte) 0);
        for (int[] colors : region.tint.palette) for (int color : colors) bytes.putInt(color);
        bytes.put(new byte[50]);
        while ((bytes.position() & 7) != 0) bytes.put((byte) 0);
        return Arrays.copyOf(bytes.array(), bytes.position());
    }

    static byte[] buffer(Object mesh, String method) throws Exception {
        ByteBuffer bytes = (ByteBuffer) call(mesh, method);
        if (bytes == null) return new byte[0];
        ByteBuffer copy = bytes.duplicate();
        byte[] result = new byte[copy.remaining()]; copy.get(result); return result;
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        semantics = Integer.parseInt(arguments[1]);
        int vertexStride = (int) call(constant("com.mojang.blaze3d.vertex.DefaultVertexFormat", "BLOCK"), "getVertexSize");
        if (vertexStride != Integer.parseInt(arguments[2])) throw new IllegalStateException("profile vertex layout differs from vanilla");
        Files.createDirectories(output);
        BlockState[] choices = initialize();
        Files.write(output.resolve("table.bin"), ExportModels.table(models, models.get(choices[0]), sprite, baked, colors, semantics));
        Object pack = construct("net.minecraft.client.renderer.SectionBufferBuilderPack");
        Object sorting = call(Class.forName("com.mojang.blaze3d.vertex.VertexSorting"), "byDistance", 3.25f, 7.5f, 20.0f);
        StringBuilder rows = new StringBuilder("[\n");
        for (int number = 0; number < 300; number++) {
            boolean ambient = (number / 10 & 1) == 0;
            boolean cutout = (number / 20 & 1) == 0;
            SnapshotRegion region = (SnapshotRegion) UNSAFE.allocateInstance(SnapshotRegion.class);
            region.states = new BlockState[8000]; Arrays.fill(region.states, Blocks.AIR.defaultBlockState());
            region.light = new byte[8000];
            Random random = new Random(0x4d455348L + number);
            random.nextBytes(region.light);
            region.sectionX = number % 7 - 3; region.sectionZ = number % 11 - 5;
            region.sectionY = number % 10 == 7 ? (number / 10 % 2 == 0 ? -4 : 19) : 4;
            region.tint = new SnapshotTint(region.sectionX, region.sectionY, region.sectionZ, number);
            int[][] families = {{0}, {1,2,3,4}, {5}, {6,7,13}, {8,9}, {10}, {11,12}, {0,1,11}, {0,1,5,6,9,10,11,12,14,15}, {}};
            int[] family = families[number % 10];
            for (int placement = 0; placement < 52 && family.length != 0; placement++) {
                int horizontal = placement < 4 ? (placement % 2 == 0 ? 0 : 15) : random.nextInt(20) - 2;
                int vertical = placement < 4 ? (placement < 2 ? 0 : 15) : random.nextInt(20) - 2;
                int depth = placement < 4 ? 15 : random.nextInt(20) - 2;
                region.states[regionIndex(horizontal, vertical, depth)] = choices[family[placement % family.length]];
            }
            if (family.length != 0) for (int vertical = 5; vertical < 8; vertical++) for (int depth = 5; depth < 9; depth++) for (int horizontal = 5; horizontal < 9; horizontal++) {
                region.states[regionIndex(horizontal, vertical, depth)] = choices[family[(horizontal + vertical + depth + number) % family.length]];
            }
            for (int vertical = -2; vertical < 18; vertical++) if (region.sectionY * 16 + vertical < -64 || region.sectionY * 16 + vertical >= 320) {
                for (int depth = -2; depth < 18; depth++) for (int horizontal = -2; horizontal < 18; horizontal++) {
                    int index = regionIndex(horizontal, vertical, depth);
                    region.states[index] = Blocks.AIR.defaultBlockState(); region.light[index] = 0;
                }
            }
            ExportModels.options(compiler, ambient, cutout);
            region.tint.configure();
            Object result = call(compiler, "compile", SectionPos.of(region.sectionX, region.sectionY, region.sectionZ), region, sorting, pack);
            @SuppressWarnings("unchecked") Map<Object, Object> layers = (Map<Object, Object>) result.getClass().getField("renderedLayers").get(result);
            String name = String.format(Locale.ROOT, "section-%03d", number);
            Files.write(output.resolve(name + ".job"), job(region, number, ambient, cutout));
            for (String renderLayer : new String[]{"SOLID", "CUTOUT", "TRANSLUCENT"}) {
                Object mesh = layers.get(layer(renderLayer));
                Files.write(output.resolve(name + "." + renderLayer.toLowerCase(Locale.ROOT) + ".vertices"), mesh == null ? new byte[0] : buffer(mesh, "vertexBuffer"));
                Files.write(output.resolve(name + "." + renderLayer.toLowerCase(Locale.ROOT) + ".indices"), mesh == null ? new byte[0] : buffer(mesh, "indexBuffer"));
            }
            call(result, "release"); call(pack, "clearAll");
            rows.append(number == 0 ? "" : ",\n").append("{\"name\":\"").append(name).append("\",\"family\":\"")
                .append(FAMILIES[number % 10]).append("\",\"ambientOcclusion\":").append(ambient).append(",\"cutoutLeaves\":").append(cutout)
                .append(",\"biomeBlendRadius\":").append(region.tint.radius).append(",\"sectionY\":").append(region.sectionY).append("}");
        }
        call(pack, "close");
        Files.writeString(output.resolve("sections.json"), rows.append("\n]\n"));
        System.out.println("300 vanilla SectionCompiler sections written");
    }
}
