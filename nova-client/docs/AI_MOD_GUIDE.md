# NOVA mods — guide for AI assistants

You are writing a mod for **NOVA Client**, a modified Minecraft Pocket Edition **0.14.3** (Android, 32-bit ARM,
libminecraftpe.so). Mods are written in **McMod Script** (an interpreted language, Russian error messages) and can
call or hook **any C++ function of the game by name**. There is no fixed API to learn; the game itself is the API.
Answer the user in Russian unless asked otherwise.

## 1. Package

A mod is a zip archive renamed to `.mcmod`:

```
mod.json   {"id": "my_mod", "name": "My mod", "version": "1.0.0", "author": "me", "main": "main.mcs"}
main.mcs   the script
textures/  png files (optional), models/ .bbmodel / .geo.json (optional), icon.png (optional)
```
`id`: latin letters, digits, `_` `-`. The user imports it in the game: Хранилище → Импорт, then enables it in «Моды».
For fast iteration: put `name.mcs` (or a folder with `main.mcs`) into `/sdcard/games/com.mojang/nova_mods/`.
The file is reloaded about 1 second after it is saved, and its old hooks are removed. Items and blocks are
registered only when the game starts, so a new `item`/`block` needs a restart.

## 2. Language (short)

```
// comment                       # also a comment
var x = 5                        // number (double), "string", true/false, null, [list], {map: 1}
chat("x = {x + 1}")              // {expression} inside strings
if a and not b { } else if c { } else { }
for i in 1..10 { }   for v in list { }   while c { }   break continue return
fn f(a, b = 2) { return a + b }  var g = fn(x) { return x * 2 }
a ? b : c      + - * / %      == != < > <= >=      += -= *= /=
```
String methods: `len upper lower trim contains starts ends find replace sub split num`.
List methods: `len add insert pop removeAt remove contains find join clear copy random`.
Map methods: `keys values has remove len`. Built-ins: `print str num int len type random`.
`type(v)` returns "number" "string" "bool" "list" "map" "fn" "null".
Top-level variables are re-created on every world join. Use `data.save(key, v)` and `data.load(key, default)` to persist.
Infinite loops are stopped with an error, so the game does not freeze.

## 3. Events

```
on world.join(e) { }      on world.leave(e) { }      on tick(e) { }   (20 per second)
on chat(e) { e.text }     on player.jump(e) { }      on player.sneak(e) { e.sneaking }
on block.use(e)   { e.x e.y e.z e.face e.block e.data e.item e.itemData }
on block.break(e) { e.x e.y e.z e.block e.data e.item }
on item.use(e) { e.item e.itemData }     on entity.attack(e) / entity.use(e) { e.target e.type e.item }
on action(e) { e.item }                  // the custom action button of an item (see item `action:`)
every 20 ticks { }   every 5 seconds { }
e.cancel()                               // cancels the game's action (break, use, chat message, jump...)
```

## 4. Small convenience API (optional)

`player.x/y/z/pos/yaw/pitch/look/id/item/itemData/sneaking/onGround/target`, `player.setScale(s)`,
`world.block(x,y,z)`, `world.data`, `world.setBlock(x,y,z,block,data)`, `world.fill(...)`, `world.time`,
`world.setTime(t)`, `chat(text)`, `ui.tip(text)` (line above the hotbar), `ui.popup(title, text)`, `ui.toast`,
`ui.action("text")` (action button, `""` hides it), `entity.list(radius)`, `entity.type/pos/scale/setScale`,
`item.id("diamond_sword")`, `data.save/load`.
`override chat.send(text) { original(...) }` replaces a built-in for all mods.

## 5. Items and blocks

```
item pistol {
    name: {ru: "Пистолет", en: "Pistol"}
    texture: "textures/pistol.png"        // square png, or a game texture name like "apple"
    hand: "tool"   stack: 1   category: "tools"   durability: 500   size: "hd" | "pixel"
    action: "Огонь"                       // shows a button while held -> on action(e)
}
block ruby_block {
    name: "Рубиновый блок"   texture: "textures/ruby.png"   hardness: 3   sound: "metal"   light: 0
    // textures: {up: "planks", down: "planks", side: "log:0"}   box: [0,0,0,16,4,16]
    // model: "models/lamp.bbmodel"   collision: [[0,0,0,16,2,16]] | "model" | "full" | "none"
}
```
Redstone (the game's own circuits, like the block of redstone and the lamp; the host computes them in a friends'
world): `redstone: "source"` (signal 15), `redstone: {type: "source", power: 4}`,
`redstone: {type: "lamp", on: "my_lamp_lit"}` + a second block `redstone: {type: "lamp_on", off: "my_lamp"}`
(lights with a signal; the lit block is not in the creative inventory and drops the unlit one).
Recipes: see `mcmod/examples/redstone_update/main.mcs` (`Recipes::addShapedRecipe` with a `vector<Recipes::Type>`).
The name becomes a global holding the numeric id: `if e.item == pistol`, `world.setBlock(x, y, z, ruby_block)`.
If registration failed, the name holds a string, so check `type(pistol) == "number"`.

## 6. The whole game: hook, mc.call, mem

The game has about 22,800 functions, named like `Class::method(args)`, the demangled C++ names of 0.14.3.
They live in `assets/nova/symbols.tsv` inside the APK. Search them in-game: send `> mc.find("Jump")` in chat
(lines starting with `>` are evaluated as code). `mc.info("Mob::travel")` shows the address, size, argument
kinds, return kind and hook mode.

**Hook** (the body runs INSTEAD of the function):
```
hook "LocalPlayer::travel" (self, strafe, forward) {
    return original(self, strafe * 2, forward * 2)   // original() = same args, original(a, b...) = new args
}
hook "Mob::getJumpPower" float (self) { return original() * 2 }   // return kind: float | int | double | void
hook "Mob::causeFallDamage" (self, d) { }    // no original() and no return value = the call is cancelled
mc.hook("Level::setTime(int)", fn(self, t, original) { return original(self, 6000) })   // dynamic form
```
**Call**:
```
mc.call("GuiData::displayClientMessage", mc.gui(), "hello")
var j = mc.callf("Mob::getJumpPower", mc.player())     // callf / calld / calli force the return kind
mc.callAt(address, w1, w2, ...)                         // raw call by address with 32-bit words (virtual calls)
```
**Memory**: `mem.int(p, off)`, `mem.float(p, off)`, `mem.byte/ubyte/short/ushort/ptr(p, off)`,
`mem.vec3(p, off)` returns `{x,y,z}`, `mem.setInt(p, v)`, `mem.setFloat(p, v)`, `mem.setPtr(p, v)`,
`mem.setByte/setShort(p, v)`, `mem.str(p)` reads a std::string, `mem.cstr(p)` reads a char*,
`mem.string("t")` makes a new std::string, `mem.alloc(n)` uses operator new and zero-fills,
`mem.free(p)`, `mem.hex(n)`.
**Objects**: `mc.client()` (MinecraftClient), `mc.player()` (LocalPlayer), `mc.region()` (the player's
BlockSource), `mc.level()`, `mc.gui()` (GuiData). Each returns an address, or `null` outside a world.

### Calling convention (important)
- ARM softfp: every argument is a 32-bit word; float is passed as its bits; double and int64 take 2 aligned words.
  Conversion is automatic from the symbol table.
- For member functions the **first argument is the object** (`self`). Static functions have no `self`.
- `Vec3`, `Vec2`, `BlockPos`, `ChunkPos`, `Color` by value, or `const&`, can be passed as `{x: .., y: .., z: ..}`.
  Hooks receive by-value ones as maps.
- Strings: a script string becomes a `std::string` for `std::string const&` parameters, and a `char*` for
  `char const*`. In names you may write `std::string` instead of `std::basic_string<char, ...>`.
- Pointers and references are plain numbers (addresses). An enum is a number. An unknown by-value class takes one
  word, which may be wrong; pass an address instead.
- Overloaded names must be written in full, with the exact argument list: `"Level::setTime(int)"`.
- Return kind is guessed from the code. If a function returns float but is shown as int, write `float`
  in the hook or use `mc.callf`.
- Virtual call: `mc.callAt(mem.ptr(mem.ptr(obj), slot * 4), obj, args...)`. `mem.ptr(obj)` is the vtable
  pointer, which points at slot 0. Itanium order: slot 0 is the destructor, slot 1 is the deleting destructor.
- `unique_ptr<T>` by value is passed as the address of a 4-byte slot that holds the pointer.
- A wrong address in `mem.*` or a wrong call crashes the game, as in C++. The crash report is saved to `NOVA-crash.txt`.
- One function can be hooked by one mod only. Hooks on functions called thousands of times per frame (rendering
  every block) are slow. Hooks called from another thread while a script runs fall back to the original.

### Known offsets (0.14.3, ARM)
| object | offset | meaning |
|---|---|---|
| Entity/Player | +8 | Vec3 position (for players y = eyes, feet = y - 1.62) |
| Entity | +56 / +60 | pitch / yaw (float) |
| Player | +0x78 | Level* |
| Player | +0xda0 | Inventory* (`Inventory::add(ItemInstance&, bool)`) |
| MinecraftClient | +0x130 | GuiData* |
| Level | vtable slot 12 | `addEntity(unique_ptr<Entity>)` |
| Arrow | size 0x1a8 | `Arrow::Arrow(Mob&, float power, ItemInstance const*)` |

### Recipes that work
```
// spawn a projectile the way BowItem::releaseUsing does
var p = mc.player()
var lvl = mem.ptr(p, 0x78)
if mc.call("Level::isClientSide() const", lvl) == 0 {          // only the host spawns entities
    var a = mem.alloc(0x1a8)
    mc.call("Arrow::Arrow(Mob&, float, ItemInstance const*)", a, p, 7, 0)
    mc.call("Arrow::setBaseDamage(float)", a, 7)
    var holder = mem.alloc(4)
    mem.setPtr(holder, a)
    mc.callAt(mem.ptr(mem.ptr(lvl), 12 * 4), lvl, holder)        // level->addEntity
    if mem.ptr(holder) != 0 { mc.callAt(mem.ptr(mem.ptr(a), 4), a) }
    mem.free(holder)
}
// sound
mc.call("Level::playSound(Entity*, std::string const&, float, float, EntityLocation)", lvl, p, "random.bow", 1, 1, 0)
mc.call("Level::playSound(Vec3 const&, std::string const&, float, float)", lvl, mem.vec3(p, 8), "random.click", 1, 1)
// give an item
var it = mem.alloc(64)
mc.call("ItemInstance::ItemInstance(int, int, int)", it, id, count, aux)
mc.call("Inventory::add(ItemInstance&, bool)", mem.ptr(p, 0xda0), it, true)
```
Sound names come from 0.14: `random.bow`, `random.explode`, `random.click`, `random.pop`, `random.orb`,
`random.levelup`, `random.break`, `mob.*`, `dig.*`, `step.*`.

## 7. How to build a new feature
1. Find how the game already does something similar. Use `mc.find("Bow")` and read function names. With the
   developer's reference (`re014/classes/<Class>.s`, generated by `nova ref-build`), read the disassembly of that function.
2. Repeat the same calls from the script, or hook the function and change its arguments or result.
3. Test it live through `nova_mods/` and the `>` console, then pack it as `.mcmod`.

## 8. Limits to tell the user honestly
- On a remote server, only the server creates entities and changes the world. A client mod changes only the client.
- Held items are 0.14-style extruded sprites. A real 3D hand model needs a rendering hook, which is not done yet.
- New items and blocks need a game restart. Scripts and hooks do not.
- Field offsets not listed above have to be found in the disassembly; a wrong guess crashes the game.

## 9. Output format
Give the user the complete `mod.json` and `main.mcs`, plus a description of each texture: size, and pixels if
you can generate it. Keep comments in Russian. Prefer `hook`/`mc.call` built on real 0.14 function names
over inventing API functions. If you are not sure a function exists, say so and have the user check it with
`> mc.find("...")`.
