import {execFileSync} from "node:child_process";
import {join} from "node:path";

const PIXEL_AUDIT = String.raw`
import json, sys
from PIL import Image
from hashlib import sha256

path = sys.argv[1]
expected = json.loads(sys.argv[2])
image = Image.open(path).convert("RGBA")
alpha = image.getchannel("A")
transparent = sum(1 for value in alpha.getdata() if value == 0)
image = image.convert("RGB")
small = image.resize((min(320, image.width), min(240, image.height)))
pixels = list(small.getdata())
n = max(1, len(pixels))
magenta = sum(1 for r, g, b in pixels if r >= 150 and b >= 150 and g <= 100 and max(r, b) - g >= 80)
black = sum(1 for r, g, b in pixels if r <= 8 and g <= 8 and b <= 8)
colors = {}
for item in expected:
    name = item["name"]
    target = tuple(int(item["rgb"][i:i + 2], 16) for i in (0, 2, 4))
    crop = image.crop((int(image.width * 0.74), int(image.height * 0.35), image.width, int(image.height * 0.94)))
    crop = crop.resize((160, 160))
    crop_pixels = list(crop.getdata())
    close = sum(1 for r, g, b in crop_pixels if max(abs(r - target[0]), abs(g - target[1]), abs(b - target[2])) <= 58)
    colors[name] = {"pixels": close, "fraction": round(close / max(1, len(crop_pixels)), 6), "target": target, "sample": item.get("sample", "held-item crop")}
grid = image.crop((int(image.width * 0.355), int(image.height * 0.336), int(image.width * 0.625), int(image.height * 0.632)))
print(json.dumps({"size": [image.width, image.height], "transparentFraction": round(transparent / max(1, image.width * image.height), 6), "magentaFraction": round(magenta / n, 6),
    "blackFraction": round(black / n, 6), "colors": colors,
    "creativeGridHash": sha256(grid.tobytes()).hexdigest()}))
`;

const INVENTORY_PIXEL_AUDIT = String.raw`
import json, sys
from PIL import Image
from hashlib import sha256
path = sys.argv[1]
image = Image.open(path).convert("RGBA")
alpha = image.getchannel("A")
transparent = sum(1 for value in alpha.getdata() if value == 0)
image = image.convert("RGB")
width, height = image.size
grid = image.crop((int(width * 0.34), int(height * 0.29), int(width * 0.66), int(height * 0.73)))
small = grid.resize((min(160, grid.width), min(160, grid.height)))
pixels = list(small.getdata())
magenta = sum(1 for r, g, b in pixels if r >= 150 and b >= 150 and g <= 100 and max(r, b) - g >= 80)
black = sum(1 for r, g, b in pixels if r <= 8 and g <= 8 and b <= 8)
bad_cells = []
for row in range(5):
    for column in range(9):
        left = int(width * (0.355 + column * 0.03))
        right = int(width * (0.385 + column * 0.03))
        top = int(height * (0.335 + row * 0.058))
        bottom = int(height * (0.393 + row * 0.058))
        cell = image.crop((left, top, right, bottom)).resize((32, 32))
        cell_pixels = list(cell.getdata())
        cell_magenta = sum(1 for r, g, b in cell_pixels if r >= 150 and b >= 150 and g <= 100 and max(r, b) - g >= 80)
        if cell_magenta >= 32:
            bad_cells.append({"row": row, "column": column, "magentaPixels": cell_magenta})
expected = json.loads(sys.argv[2])
colors = {}
for item in expected:
    slot = int(item.get("slot", 0))
    center_x = int(width * (0.372 + slot * 0.0316))
    center_y = int(height * 0.967)
    half = max(8, int(width * 0.012))
    icon = image.crop((center_x - half, center_y - half, center_x + half, center_y + half)).resize((64, 64))
    icon_pixels = list(icon.getdata())
    target = tuple(int(item["rgb"][i:i + 2], 16) for i in (0, 2, 4))
    close = sum(1 for r, g, b in icon_pixels if max(abs(r-target[0]), abs(g-target[1]), abs(b-target[2])) <= 45)
    colors[item["name"]] = {"slot": slot, "pixels": close, "fraction": round(close / max(1, len(icon_pixels)), 6), "target": target}
print(json.dumps({"creativeGridHash": sha256(grid.tobytes()).hexdigest(),
    "magentaFraction": round(magenta / max(1, len(pixels)), 6),
    "transparentFraction": round(transparent / max(1, width * height), 6),
    "blackFraction": round(black / max(1, len(pixels)), 6), "badCells": bad_cells, "iconColors": colors}))
`;

const TINTS = [
  {name: "swiftness", rgb: "7CAFC6"},
  {name: "poison", rgb: "4E9331"},
  {name: "healing", rgb: "F82423"},
  {name: "fire_resistance", rgb: "E49A2F"},
];

const LEATHER_DYES = [
  {name: "white", rgb: "F9FFFE"}, {name: "orange", rgb: "F9801D"}, {name: "magenta", rgb: "C74EBD"},
  {name: "light_blue", rgb: "3AB3DA"}, {name: "yellow", rgb: "FED83D"}, {name: "lime", rgb: "80C71F"},
  {name: "pink", rgb: "F38BAA"}, {name: "gray", rgb: "474F52"}, {name: "light_gray", rgb: "9D9D97"},
  {name: "cyan", rgb: "169C9C"}, {name: "purple", rgb: "8932B8"}, {name: "blue", rgb: "3C44AA"},
  {name: "brown", rgb: "835432"}, {name: "green", rgb: "5E7C16"}, {name: "red", rgb: "B02E26"},
    {name: "black", rgb: "1D1D21"},
].map(dye => ({...dye, tintName: `leather-${dye.name}`}));

// Registry-backed living types for profile 26.3, plus LivingEntity types placed in MISC.
const LIVING_TYPES = [
  "allay", "armadillo", "armor_stand", "axolotl", "bat", "bee", "blaze", "bogged", "breeze", "camel",
  "camel_husk", "cat", "cave_spider", "chicken", "cod", "copper_golem", "cow", "creaking", "creeper",
  "dolphin", "donkey", "drowned", "elder_guardian", "enderman", "endermite", "evoker",
  "fox", "frog", "ghast", "giant", "glow_squid", "goat", "guardian", "happy_ghast", "hoglin", "horse",
  "husk", "illusioner", "iron_golem", "llama", "magma_cube", "mooshroom", "mule", "nautilus", "ocelot",
  "panda", "parched", "parrot", "phantom", "pig", "piglin", "piglin_brute", "pillager", "polar_bear",
  "pufferfish", "rabbit", "ravager", "salmon", "sheep", "shulker", "silverfish", "skeleton", "skeleton_horse",
  "slime", "snow_golem", "sniffer", "spider", "squid", "stray", "strider", "sulfur_cube", "tadpole",
  "trader_llama", "tropical_fish", "turtle", "vex", "vindicator", "villager", "wandering_trader", "warden",
  "witch", "wither", "wither_skeleton", "wolf", "zoglin", "zombie", "zombie_horse", "zombie_nautilus",
  "zombie_villager", "zombified_piglin", "mannequin",
];

const BABY_TYPES = [
  "armadillo", "axolotl", "bee", "camel", "cat", "chicken", "cow", "donkey", "drowned", "fox", "frog",
  "goat", "hoglin", "horse", "husk", "llama", "mooshroom", "mule", "nautilus", "ocelot", "panda", "pig",
  "piglin", "polar_bear", "rabbit", "sheep", "sniffer", "strider", "turtle", "wolf", "zoglin", "zombie",
  "zombie_horse", "zombie_nautilus", "zombie_villager", "zombified_piglin",
];
const AQUATIC_TYPES = new Set([
  "axolotl", "cod", "dolphin", "drowned", "elder_guardian", "glow_squid", "guardian", "nautilus",
  "pufferfish", "salmon", "squid", "tadpole", "tropical_fish", "zombie_nautilus",
]);

const VILLAGER_TYPES = ["plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga"];
const VILLAGER_PROFESSIONS = [
  "none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher", "leatherworker",
  "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith",
];
const ENTITY_VARIANTS = [
  {entity: "cat", field: "variant", forms: ["tabby", "black", "red", "siamese", "british_shorthair", "calico", "persian", "ragdoll", "white", "jellie", "all_black"]},
  {entity: "wolf", field: "variant", forms: ["pale", "ashen", "black", "chestnut", "rusty", "snowy", "spotted", "striped", "woods"]},
  {entity: "fox", field: "Type", forms: ["red", "snow"]},
  {entity: "axolotl", field: "Variant", forms: [0, 1, 2, 3, 4]},
  {entity: "frog", field: "variant", forms: ["minecraft:temperate", "minecraft:warm", "minecraft:cold"]},
  {entity: "tropical_fish", field: "Variant", forms: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]},
  {entity: "horse", field: "Variant", forms: [0, 1, 2, 3, 4, 5, 6]},
  {entity: "llama", field: "Variant", forms: [0, 1, 2, 3]},
  {entity: "parrot", field: "Variant", forms: [0, 1, 2, 3, 4]},
  {entity: "rabbit", field: "RabbitType", forms: [0, 1, 2, 3, 4, 5, 99]},
  {entity: "panda", field: "MainGene", forms: ["normal", "lazy", "worried", "playful", "brown", "weak", "aggressive"]},
  {entity: "mooshroom", field: "Type", forms: ["red", "brown"]},
];

const CREATIVE_TABS = [
  "Building Blocks", "Colored Blocks", "Natural Blocks", "Functional Blocks", "Redstone Blocks",
  "Saved Hotbars", "Search Items", "Tools & Utilities", "Combat", "Food & Drinks", "Ingredients",
  "Spawn Eggs", "Operator Utilities", "Survival Inventory",
];
const SCROLLABLE_TABS = new Set([
  "Building Blocks", "Colored Blocks", "Natural Blocks", "Functional Blocks", "Redstone Blocks",
  "Tools & Utilities", "Combat", "Food & Drinks", "Ingredients", "Spawn Eggs",
]);

function quote(value) {
  return JSON.stringify(String(value));
}

function floor(value) {
  return Math.floor(Number(value) || 0);
}

async function screenshotAndAudit({screenshot, output, name, expected = []}) {
  const path = await screenshot(name);
  if (!path) return {name, path: null, error: "screenshot failed"};
  let pixels;
  try {
    pixels = JSON.parse(execFileSync("python", ["-c", PIXEL_AUDIT, path, JSON.stringify(expected)], {
      encoding: "utf8",
      env: {...process.env, TMP: process.env.TMP || process.env.TEMP, TEMP: process.env.TEMP || process.env.TMP},
    }));
  } catch (error) {
    return {name, path: path.split(/[\\/]/).pop(), error: String(error)};
  }
  return {name, path: path.split(/[\\/]/).pop(), pixels};
}

async function screenshotInventoryAudit({screenshot, name}) {
  const path = await screenshot(name);
  if (!path) return {name, path: null, error: "screenshot failed"};
  try {
    const pixels = JSON.parse(execFileSync("python", ["-c", INVENTORY_PIXEL_AUDIT, path, "[]"], {
      encoding: "utf8",
      env: {...process.env, TMP: process.env.TMP || process.env.TEMP, TEMP: process.env.TEMP || process.env.TMP},
    }));
    return {name, path: path.split(/[\\/]/).pop(), pixels};
  } catch (error) {
    return {name, path: path.split(/[\\/]/).pop(), error: String(error)};
  }
}

async function send(cdp, method, params = {}) {
  return cdp.send(method, params);
}

async function clickWidget({cdp, evaluate}, predicate) {
  const widget = await evaluate(`(()=>{
    const s=globalThis.__gaiusMinecraftState||{};
    const ws=Array.isArray(s.screenWidgets)?s.screenWidgets:[];
    const p=${predicate};
    const w=ws.find(x=>x&&x.visible!==false&&p(x));
    const c=document.querySelector('canvas'),r=c&&c.getBoundingClientRect(),z=s.screenSize;
    if(!w||!r||!z||!z.width)return null;
    return {x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),
      y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height),
      type:String(w.type||''),text:String(w.text||'')};
  })()`);
  if (!widget) return null;
  await send(cdp, "Input.dispatchMouseEvent", {type: "mouseMoved", x: widget.x, y: widget.y, button: "none", buttons: 0});
  await send(cdp, "Input.dispatchMouseEvent", {type: "mousePressed", x: widget.x, y: widget.y, button: "left", buttons: 1, clickCount: 1});
  await send(cdp, "Input.dispatchMouseEvent", {type: "mouseReleased", x: widget.x, y: widget.y, button: "left", buttons: 0, clickCount: 1});
  return widget;
}

function commandText(x, y, z, item) {
  return `/item replace entity @s weapon.mainhand with ${item} 1`;
}

export async function run(context) {
  const {cdp, command, evaluate, key, screenshot, sleep, output, profile, backend, player, groundY, log} = context;
  const commands = [];
  const shots = [];
  const findings = [];
  const scope = process.env.TEXTURE_AUDIT_SCOPE || "full";
  if (!["full", "focused"].includes(scope)) throw new Error(`unknown TEXTURE_AUDIT_SCOPE: ${scope}`);
  const dimension = process.env.TEXTURE_AUDIT_DIMENSION || "overworld";
  if (!["overworld", "the_end"].includes(dimension)) {
    throw new Error(`unknown TEXTURE_AUDIT_DIMENSION: ${dimension}`);
  }
  const targets = (process.env.TEXTURE_AUDIT_TARGETS || (scope === "full" ? "entities,arrows,inventory" : "arrows"))
    .split(",").map(value => value.trim()).filter(Boolean);
  if (!targets.length || targets.some(value => !["entities", "arrows", "inventory"].includes(value))) {
    throw new Error(`unknown TEXTURE_AUDIT_TARGETS: ${targets.join(",")}`);
  }
  const targetSet = new Set(targets);
  const runEntities = targetSet.has("entities");
  const runArrows = targetSet.has("arrows");
  const runInventory = targetSet.has("inventory");
  let creativeScreen = null;
  const inventoryPages = [];
  const widgetSnapshots = [];
  const tabPoints = [];
  const expectedFor = text => {
    if (text.startsWith("/gamerule ")) {
      const [, rule, value] = text.split(" ");
      return new RegExp(`(?:Gamerule|Game rule) ${rule} is (?:now|already) set to:? ${value}(?:$|\\s)`, "i");
    }
    if (text.startsWith("/gamemode ")) return /Set own game mode to (?:Spectator|Creative|Survival|Adventure) Mode/i;
    if (text.startsWith("/time ")) return /Set the time to|Set minecraft:overworld to 6000 tick/i;
    if (text.startsWith("/weather ")) return /Set the weather to clear/i;
    if (text.startsWith("/summon ")) return /Summoned new|Successfully summoned/i;
    if (text.startsWith("/tp ")) return /Teleported/i;
    if (text.startsWith("/fill ")) return /Successfully filled \d+ block/i;
    if (text.startsWith("/setblock ")) return /Changed the block at|Block placed|Block updated|The block is already/i;
    if (text.startsWith("/item replace ")) return /Replaced \d+ slot/i;
    if (text.startsWith("/kill ")) return /Killed \d+ entities?|No entity was found/i;
    if (text.startsWith("/data get ")) return /-\d+/;
    if (text.startsWith("/execute in ")) return /Teleported/i;
    return /\[CHAT\]/;
  };
  const sendCommand = async (text, expected = null) => {
    commands.push(text);
    let result;
    try { result = await command(text, expected || expectedFor(text)); }
    catch (error) { result = {ok: false, error: String(error)}; }
    const response = Array.isArray(result?.response) ? result.response.join(" ") : String(result?.response || "");
    const rejected = /Unknown (?:entity|item|block|command)|Couldn't summon|Can't summon|Cannot summon|Invalid (?:entity|item|block)|Failed to/i.test(response)
      || (/No entity was found/i.test(response) && !text.startsWith("/kill "));
    if (result?.error || result?.ok === false || rejected) {
      findings.push({kind: "command-failed", command: text, result});
      const screen = await evaluate("(()=>String((globalThis.__gaiusMinecraftState||{}).screen||''))()")
        .catch(error => `state-unavailable:${String(error)}`);
      if (/DisconnectedScreen|ConnectScreen/.test(screen)) {
        const error = new Error(`connection lost during texture audit after ${text}: ${screen}`);
        error.textureAuditAbort = true;
        throw error;
      }
    }
    return result;
  };
  const clickAtGamePoint = async (x, y) => {
    const point = await evaluate(`(()=>{const c=document.querySelector('canvas'),r=c&&c.getBoundingClientRect(),s=(globalThis.__gaiusMinecraftState||{}).screenSize;if(!r||!s||!s.width||!s.height)return null;return{x:r.left+${Number(x)}*r.width/s.width,y:r.top+${Number(y)}*r.height/s.height}})()`);
    if (!point) return false;
    await send(cdp, "Input.dispatchMouseEvent", {type: "mouseMoved", x: point.x, y: point.y, button: "none", buttons: 0});
    await send(cdp, "Input.dispatchMouseEvent", {type: "mousePressed", x: point.x, y: point.y, button: "left", buttons: 1, clickCount: 1});
    await send(cdp, "Input.dispatchMouseEvent", {type: "mouseReleased", x: point.x, y: point.y, button: "left", buttons: 0, clickCount: 1});
    return true;
  };
  const summon = async (name, x, y, z, nbt = "") => {
    const result = await sendCommand(`/summon minecraft:${name} ${x} ${y} ${z}${nbt ? ` ${nbt}` : ""}`,
      /Summoned new|Successfully summoned/i);
    if (result?.error || result?.ok === false || /Unknown entity|Couldn't summon|Can't summon|Cannot summon|Invalid entity/i.test(JSON.stringify(result))) {
      findings.push({kind: "summon-failed", entity: name, position: [x, y, z], result});
    }
    return result;
  };
  const atPosition = (position, type) => `@e[type=minecraft:${type},distance=..1,sort=nearest,limit=1,x=${position[0]},y=${position[1]},z=${position[2]}]`;
  const capture = async (name, expected = []) => {
    const shot = await screenshotAndAudit({screenshot, output, name, expected});
    shots.push(shot);
    if (shot.error || !shot.pixels) {
      findings.push({kind: "screenshot-audit-failed", shot: shot.path, name, error: shot.error || "pixel audit returned no data"});
      return shot;
    }
    const p = shot.pixels;
    if (p?.magentaFraction > 0.0005) findings.push({kind: "missing-texture-checkerboard", shot: shot.path, pixels: p});
    if (p?.blackFraction > 0.94) findings.push({kind: "rendered-scene-blank-or-transparent", shot: shot.path, pixels: p});
    for (const color of expected) {
      if ((p?.colors?.[color.name]?.pixels || 0) < 40) {
        findings.push({kind: "expected-tint-not-detected", tint: color.name, shot: shot.path, pixels: p});
      }
    }
    return shot;
  };

  const entityRounds = [];
  const babyRounds = [];
  const villagerRounds = [];
  const variantRounds = [];
  let villagerMatrix = [];

  try {
  if (dimension !== "overworld") {
    await sendCommand(`/execute in minecraft:${dimension} run tp @s 0 80 0`);
    await sleep(1800);
  }
  if (scope === "full") await sendCommand("/gamemode creative");
  await sendCommand("/gamerule advance_time false");
  await sendCommand("/time set 6000");
  await sendCommand("/weather clear");
  const x0 = floor(player.x) + 20;
  const z0 = floor(player.z);
  const y0 = floor(groundY) + 3;
  await sendCommand(`/fill ${x0 - 10} ${y0 - 1} ${z0 - 10} ${x0 + 10} ${y0 - 1} ${z0 + 10} minecraft:stone`);
  await sendCommand(`/fill ${x0 - 10} ${y0} ${z0 - 10} ${x0 + 10} ${y0 + 8} ${z0 + 10} minecraft:air`);
  await sendCommand("/kill @e[type=!player]");

  const villagerVariants = [
    ["plains-farmer-baby", "minecraft:plains", "minecraft:farmer", -24000],
    ["desert-librarian-baby", "minecraft:desert", "minecraft:librarian", -24000],
    ["taiga-armorer-baby", "minecraft:taiga", "minecraft:armorer", -24000],
    ["savanna-nitwit-baby", "minecraft:savanna", "minecraft:none", -24000],
    ["snowy-cleric-adult", "minecraft:snow", "minecraft:cleric", 0],
    ["jungle-cartographer-adult", "minecraft:jungle", "minecraft:cartographer", 0],
  ];
  for (let i = 0; runEntities && i < villagerVariants.length; i++) {
    const [label, type, profession, age] = villagerVariants[i];
    const x = x0 - 2.5 + (i % 3) * 2.5;
    const z = z0 - 2 + Math.floor(i / 3) * 4;
    await summon("villager", x, y0, z, `{NoAI:1b,Age:${age},VillagerData:{type:"${type}",profession:"${profession}",level:2}}`);
  }
  if (runEntities) {
    await sleep(1800);
    await sendCommand(`/gamemode spectator`);
    await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
    await sleep(2200);
    await capture("texture-before-villagers.png");
  }

  if (runEntities && scope === "full") {
    await sendCommand("/kill @e[type=!player]");
    for (let start = 0; start < LIVING_TYPES.length; start += 12) {
      const types = LIVING_TYPES.slice(start, start + 12);
      entityRounds.push({start, types});
      for (let i = 0; i < types.length; i++) {
        const x = x0 - 3.75 + (i % 4) * 2.5;
        const z = z0 - 3 + Math.floor(i / 4) * 4;
        if (AQUATIC_TYPES.has(types[i])) await sendCommand(`/setblock ${Math.floor(x)} ${y0} ${Math.floor(z)} minecraft:water`);
        await summon(types[i], x.toFixed(1), y0, z.toFixed(1), "{NoAI:1b}");
      }
      await sleep(1800);
      await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
      await sleep(850);
      await capture(`texture-before-living-grid-${String(entityRounds.length).padStart(2, "0")}.png`);
      await sendCommand("/kill @e[type=!player]");
      await sendCommand(`/fill ${x0 - 10} ${y0} ${z0 - 10} ${x0 + 10} ${y0} ${z0 + 10} minecraft:air`);
    }

    for (let start = 0; start < BABY_TYPES.length; start += 12) {
      const types = BABY_TYPES.slice(start, start + 12);
      babyRounds.push({start, types});
      for (let i = 0; i < types.length; i++) {
        const x = x0 - 3.75 + (i % 4) * 2.5;
        const z = z0 - 3 + Math.floor(i / 4) * 4;
        const position = [x.toFixed(1), y0, z.toFixed(1)];
        if (AQUATIC_TYPES.has(types[i])) await sendCommand(`/setblock ${Math.floor(Number(position[0]))} ${y0} ${Math.floor(Number(position[2]))} minecraft:water`);
        const result = await summon(types[i], ...position, "{NoAI:1b,Age:-24000}");
        if (result?.ok) {
          const age = await sendCommand(`/data get entity ${atPosition([Number(position[0]), y0, Number(position[2])], types[i])} Age`, /-\d+/);
          const response = Array.isArray(age?.response) ? age.response.join(" ") : String(age?.response || "");
          if (!age?.ok || !/-\d+/.test(response)) findings.push({kind: "baby-age-not-confirmed", entity: types[i], position, result: age});
        }
      }
      await sleep(1800);
      await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
      await sleep(850);
      await capture(`texture-before-baby-grid-${String(babyRounds.length).padStart(2, "0")}.png`);
      await sendCommand("/kill @e[type=!player]");
      await sendCommand(`/fill ${x0 - 10} ${y0} ${z0 - 10} ${x0 + 10} ${y0} ${z0 + 10} minecraft:air`);
    }

    const sheepVariants = Array.from({length: 16}, (_, color) => ({entity: "sheep", color}));
    for (let start = 0; start < sheepVariants.length; start += 12) {
      const variants = sheepVariants.slice(start, start + 12);
      variantRounds.push({kind: "sheep-dye", start, variants});
      for (let i = 0; i < variants.length; i++) {
        const {color} = variants[i];
        const x = x0 - 3.75 + (i % 4) * 2.5;
        const z = z0 - 3 + Math.floor(i / 4) * 4;
        await summon("sheep", x.toFixed(1), y0, z.toFixed(1), `{NoAI:1b,Color:${color}}`);
      }
      await sleep(1200);
      await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
      await sleep(650);
      await capture(`texture-before-sheep-colors-${String(variantRounds.length).padStart(2, "0")}.png`);
      await sendCommand("/kill @e[type=!player]");
    }

    for (const set of ENTITY_VARIANTS) {
      for (let start = 0; start < set.forms.length; start += 12) {
        const forms = set.forms.slice(start, start + 12);
        variantRounds.push({kind: "entity-variant", entity: set.entity, field: set.field, start, forms});
        for (let i = 0; i < forms.length; i++) {
          const value = forms[i];
          const x = x0 - 3.75 + (i % 4) * 2.5;
          const z = z0 - 3 + Math.floor(i / 4) * 4;
          let nbt;
          if (set.field === "variant" && typeof value === "string") nbt = `{NoAI:1b,variant:"${value}"}`;
          else if (set.field === "MainGene") nbt = `{NoAI:1b,MainGene:"${value}",HiddenGene:"${value}"}`;
          else if (set.field === "Type" && set.entity === "mooshroom") nbt = `{NoAI:1b,Type:"${value}"}`;
          else if (set.field === "Type") nbt = `{NoAI:1b,Type:"${value}"}`;
          else nbt = `{NoAI:1b,${set.field}:${typeof value === "string" ? `"${value}"` : value}}`;
          await summon(set.entity, x.toFixed(1), y0, z.toFixed(1), nbt);
        }
        await sleep(1400);
        await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
        await sleep(650);
        await capture(`texture-before-variant-${set.entity}-${String(variantRounds.length).padStart(2, "0")}.png`);
        await sendCommand("/kill @e[type=!player]");
      }
    }

    for (const type of VILLAGER_TYPES) {
      for (const profession of VILLAGER_PROFESSIONS) villagerMatrix.push({type, profession});
    }
    for (let start = 0; start < villagerMatrix.length; start += 12) {
      const variants = villagerMatrix.slice(start, start + 12);
      villagerRounds.push({start, variants});
      for (let i = 0; i < variants.length; i++) {
        const {type, profession} = variants[i];
        const x = x0 - 3.75 + (i % 4) * 2.5;
        const z = z0 - 3 + Math.floor(i / 4) * 4;
        await summon("villager", x.toFixed(1), y0, z.toFixed(1), `{NoAI:1b,VillagerData:{type:"minecraft:${type}",profession:"minecraft:${profession}",level:2}}`);
      }
      await sleep(1800);
      await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
      await sleep(850);
      await capture(`texture-before-villager-matrix-${String(villagerRounds.length).padStart(2, "0")}.png`);
      await sendCommand("/kill @e[type=!player]");
    }

    // Keep the potentially crashing dragon last so all other evidence survives.
    entityRounds.push({start: LIVING_TYPES.length, types: ["ender_dragon"], isolated: true});
    try {
      await sendCommand("/summon minecraft:ender_dragon 7.8 67 21.0 {NoAI:1b}");
      await sleep(1800);
      await sendCommand(`/tp @s ${x0} ${y0 + 8} ${z0 + 16} 180 28`);
      await sleep(850);
      await capture("texture-before-living-ender-dragon.png");
    } catch (error) {
      findings.push({kind: "isolated-entity-failed", entity: "ender_dragon", error: String(error)});
      await log("texture-audit-ender-dragon", String(error));
    }
    await sendCommand("/kill @e[type=!player]").catch(() => {});
  }

  if (runArrows) {
  await sendCommand("/kill @e[type=!player]");
  if (scope === "full") await sendCommand("/gamemode creative");
  await sendCommand("/kill @e[type=item]");
    await sendCommand(`/tp @s ${x0} ${y0 + 2} ${z0 - 8} 0 0`);
  await sendCommand(commandText(x0, y0, z0, "minecraft:tipped_arrow[potion_contents={potion:\"minecraft:swiftness\"}]"));
  await sleep(1000);
  await capture("texture-before-tipped-arrow-hand-swiftness.png", [TINTS[0]]);
  for (const [name, potion] of [["poison", "poison"], ["healing", "healing"], ["fire-resistance", "fire_resistance"]]) {
    await sendCommand(commandText(x0, y0, z0, `minecraft:tipped_arrow[potion_contents={potion:"minecraft:${potion}"}]`));
    await sleep(500);
    await capture(`texture-before-tipped-arrow-hand-${name}.png`, [TINTS.find(x => x.name === potion) || TINTS[0]]);
  }
  for (const item of ["minecraft:arrow", "minecraft:spectral_arrow"]) {
    await sendCommand(commandText(x0, y0, z0, item));
    await sleep(500);
    await capture(`texture-before-${item.split(":")[1]}-hand.png`);
  }

  await sendCommand("/kill @e[type=!player]");
  for (let i = 0; i < 5; i++) {
    const id = i < 3 ? "minecraft:tipped_arrow" : (i === 3 ? "minecraft:arrow" : "minecraft:spectral_arrow");
    const potion = ["swiftness", "poison", "healing"][i];
    const itemData = i < 3
      ? `,components:{"minecraft:potion_contents":{potion:"minecraft:${potion}"}}` : "";
    const x = x0 - 4 + i * 2;
    const z = z0 + 3;
    await sendCommand(`/setblock ${x} ${y0 + 2} ${z} minecraft:stone`);
    const position = [x + 0.5, y0 + 2.5, z - 0.03125];
    await summon("item_frame", position[0], position[1], position[2],
      `{Facing:2b,TileX:${x},TileY:${y0 + 2},TileZ:${z},Fixed:1b,Item:{id:"${id}",count:1${itemData}}}`);
  }
  await summon("arrow", x0 - 3, y0 + 2, z0 + 2, "{NoGravity:1b,pickup:0b,Motion:[0.0d,0.0d,0.15d]}");
  await summon("spectral_arrow", x0, y0 + 2, z0 + 2, "{NoGravity:1b,pickup:0b,Motion:[0.0d,0.0d,0.15d]}");
  await summon("arrow", x0 + 3, y0 + 2, z0 + 2, "{NoGravity:1b,pickup:0b,Motion:[0.0d,0.0d,0.15d],Potion:\"minecraft:healing\"}");
  await sendCommand(`/tp @s ${x0} ${y0 + 5} ${z0 - 10} 0 18`);
  await sleep(700);
  await capture("texture-before-arrow-frame-flight.png");
  }

  if (runInventory) {
  await sendCommand("/kill @e[type=!player]");
  if (scope === "full") await sendCommand("/gamemode creative");
  await sendCommand(`/tp @s ${x0} ${y0 + 2} ${z0 - 8} 0 0`);
  if (scope === "full") {
    for (const dye of LEATHER_DYES) {
      const rgb = Number.parseInt(dye.rgb, 16);
      await sendCommand(commandText(x0, y0, z0, `minecraft:leather_chestplate[dyed_color=${rgb}]`));
      await sleep(350);
      await capture(`texture-before-leather-${dye.name}.png`, [{name: dye.tintName, rgb: dye.rgb}]);
    }
  }
  await sendCommand(commandText(x0, y0, z0, "minecraft:tipped_arrow[potion_contents={potion:\"minecraft:swiftness\"}]"));
  await sleep(500);
  {
    await key("e", "KeyE", 69, "e");
    await sleep(1800);
    creativeScreen = await evaluate("(()=>String((globalThis.__gaiusMinecraftState||{}).screen||''))()");
    if (/PauseScreen/.test(creativeScreen || "")) {
      const resumed = await clickWidget({cdp, evaluate}, w => /Back to Game/.test(String(w.text || "")));
      if (resumed) {
        await sleep(700);
        await key("e", "KeyE", 69, "e");
        await sleep(1200);
        creativeScreen = await evaluate("(()=>String((globalThis.__gaiusMinecraftState||{}).screen||''))()");
      }
    }
    if (!/CreativeModeInventoryScreen/.test(creativeScreen || "")) {
      findings.push({kind: "creative-inventory-not-open", observedScreen: creativeScreen});
    }
    const screenSize = await evaluate("(()=>((globalThis.__gaiusMinecraftState||{}).screenSize)||null)()");
  const inventoryReady = /CreativeModeInventoryScreen/.test(creativeScreen || "") && Boolean(screenSize?.width);
  if (/CreativeModeInventoryScreen/.test(creativeScreen || "") && screenSize?.width) {
    const center = screenSize.width / 2;
    // The category tabs sit in two rows at the top and bottom of the inventory panel.
    for (const [index, x, y] of [
      [0, center - 84, 77], [1, center - 58, 77], [2, center - 32, 77], [3, center - 6, 77],
      [4, center + 20, 77], [5, center + 58, 77], [6, center + 84, 77],
      [7, center - 84, 235], [8, center - 58, 235], [9, center - 32, 235], [10, center - 6, 235],
      [11, center + 20, 235], [12, center + 58, 235], [13, center + 84, 235],
    ]) {
      tabPoints.push({index, label: CREATIVE_TABS[index], x, y});
    }
  }
  let previousTabHash = null;
  for (let tab = 0; inventoryReady && tab < Math.max(1, tabPoints.length); tab++) {
    if (tabPoints[tab]) {
      const clicked = await clickAtGamePoint(tabPoints[tab].x, tabPoints[tab].y);
      if (!clicked) findings.push({kind: "creative-tab-click-failed", tab, tabPoint: tabPoints[tab]});
      await sleep(450);
    }
    let previousGridHash = null;
    let unchangedPages = 0;
    for (let page = 0; page < 128; page++) {
      if (page === 0) {
        const rect = await evaluate("(()=>{const c=document.querySelector('canvas'),r=c&&c.getBoundingClientRect(),s=(globalThis.__gaiusMinecraftState||{}).screenSize;return r&&s&&s.width?{x:r.left+(s.width/2+64)*r.width/s.width,y:r.top+165*r.height/s.height}:null})()");
        if (rect) await send(cdp, "Input.dispatchMouseEvent", {type: "mouseWheel", x: rect.x, y: rect.y, deltaX: 0, deltaY: -12000});
        await sleep(260);
      } else {
        const rect = await evaluate("(()=>{const c=document.querySelector('canvas'),r=c&&c.getBoundingClientRect(),s=(globalThis.__gaiusMinecraftState||{}).screenSize;return r&&s&&s.width?{x:r.left+(s.width/2+64)*r.width/s.width,y:r.top+165*r.height/s.height}:null})()");
        if (rect) await send(cdp, "Input.dispatchMouseEvent", {type: "mouseWheel", x: rect.x, y: rect.y, deltaX: 0, deltaY: 120});
        await sleep(260);
      }
      const hover = await evaluate("(()=>{const c=document.querySelector('canvas'),r=c&&c.getBoundingClientRect(),s=(globalThis.__gaiusMinecraftState||{}).screenSize;return r&&s&&s.width?{x:r.left+(s.width/2+64)*r.width/s.width,y:r.top+165*r.height/s.height}:null})()");
      if (hover) await send(cdp, "Input.dispatchMouseEvent", {type: "mouseMoved", x: hover.x, y: hover.y, button: "none", buttons: 0});
      const state = await evaluate("(()=>{const s=globalThis.__gaiusMinecraftState||{};return {screen:String(s.screen||''),screenSize:s.screenSize||null,widgets:Array.isArray(s.screenWidgets)?s.screenWidgets.map(w=>({x:w.x,y:w.y,width:w.width,height:w.height,type:String(w.type||''),text:String(w.text||''),active:w.active,visible:w.visible})):[]}})()");
      widgetSnapshots.push({tab, page, state});
      if (!/CreativeModeInventoryScreen/.test(state?.screen || "")) {
        findings.push({kind: "creative-inventory-screen-lost", tab, page, observedScreen: state?.screen || null});
        break;
      }
      const shot = await screenshotInventoryAudit({screenshot, name: `texture-before-creative-tab-${String(tab).padStart(2, "0")}-page-${String(page).padStart(2, "0")}.png`});
      shots.push(shot);
      if (shot.error || !shot.pixels) {
        findings.push({kind: "inventory-screenshot-audit-failed", tab, page, shot: shot.path, error: shot.error || "pixel audit returned no data"});
        break;
      }
      if (shot.pixels?.magentaFraction > 0.01 || shot.pixels?.badCells?.length) findings.push({kind: "creative-item-missing-texture", shot: shot.path, pixels: shot.pixels});
      if (!shot.pixels?.creativeGridHash) findings.push({kind: "creative-page-hash-missing", tab, page, label: tabPoints[tab]?.label, shot: shot.path});
      if (shot.pixels?.blackFraction > 0.88) findings.push({kind: "creative-grid-blank-or-transparent", shot: shot.path, pixels: shot.pixels});
      const gridHash = shot.pixels?.creativeGridHash;
      if (page === 0 && previousTabHash && gridHash === previousTabHash) {
        findings.push({kind: "creative-tab-did-not-change-content", tab, tabPoint: tabPoints[tab] || null, shot: shot.path, gridHash});
      }
      inventoryPages.push({tab, tabLabel: tabPoints[tab]?.label || null, tabPoint: tabPoints[tab] || null, page,
        pageChanged: page === 0 ? null : gridHash !== previousGridHash,
        screen: state?.screen || null, shot: shot.path, gridHash,
        widgetCount: state?.widgets?.length || 0});
      if (page === 1 && gridHash === previousGridHash && SCROLLABLE_TABS.has(tabPoints[tab]?.label)) {
        findings.push({kind: "creative-scroll-did-not-change-content", tab, label: tabPoints[tab]?.label, shot: shot.path});
      }
      unchangedPages = gridHash && gridHash === previousGridHash ? unchangedPages + 1 : 0;
      previousGridHash = gridHash || previousGridHash;
      if (unchangedPages >= 2) break;
    }
    if (inventoryPages.length && inventoryPages.at(-1).tab === tab) previousTabHash = inventoryPages.findLast(entry => entry.tab === tab && entry.page === 0)?.gridHash || previousTabHash;
  }
  if (scope === "full" && runInventory && inventoryPages.length === 0) findings.push({kind: "creative-inventory-coverage-empty"});
  if (scope === "full" && runInventory && tabPoints.length !== CREATIVE_TABS.length) {
    findings.push({kind: "creative-tab-coverage-incomplete", expected: CREATIVE_TABS.length, observed: tabPoints.length});
  }
  if (scope === "full" && runInventory) {
    for (let tab = 0; tab < CREATIVE_TABS.length; tab++) {
      const pages = inventoryPages.filter(entry => entry.tab === tab);
      if (!pages.some(entry => entry.page === 0)) findings.push({kind: "creative-tab-page-missing", tab, label: CREATIVE_TABS[tab]});
      if (SCROLLABLE_TABS.has(CREATIVE_TABS[tab]) && pages.length < 2) {
        findings.push({kind: "creative-tab-pagination-unverified", tab, label: CREATIVE_TABS[tab], observedPages: pages.length});
      }
    }
  }
  await key("Escape", "Escape", 27, "");
  await sleep(500);
  }
  }

  } catch (error) {
    if (!error?.textureAuditAbort) throw error;
    findings.push({kind: "texture-audit-aborted", error: String(error)});
  }

  const inventoryCoverage = CREATIVE_TABS.map((label, tab) => {
    const pages = inventoryPages.filter(entry => entry.tab === tab);
    return {tab, label, pages: pages.length, uniqueHashes: new Set(pages.map(entry => entry.gridHash).filter(Boolean)).size,
      observedScrollChange: pages.some(entry => entry.page > 0 && entry.pageChanged)};
  });
  const report = {profile, backend, scope, dimension, targets, commands, shots, findings, creativeScreen, tabPoints, inventoryPages, inventoryCoverage,
    widgetSnapshots, livingTypes: LIVING_TYPES, babyTypes: BABY_TYPES, entityRounds, babyRounds,
    villagerMatrix, villagerRounds, variantRounds, creativeTabs: CREATIVE_TABS, expectedTintRules: TINTS,
    output: join(output, "texture-sweep.json")};
  await import("node:fs/promises").then(fs => fs.writeFile(report.output, JSON.stringify(report, null, 2)));
  await log("texture-audit-summary", JSON.stringify({profile, backend, shots: shots.length, findings: findings.length, inventoryPages: inventoryPages.length}));
  return report;
}
