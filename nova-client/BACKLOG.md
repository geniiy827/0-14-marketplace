# NOVA — задачи (можно бросать и возвращаться)

Статусы: `[ ]` не начато · `[~]` начато (что сделано / что дальше) · `[?]` ждёт проверки на телефоне · `[x]` готово.
Каждая задача — с заметками, чтобы продолжить с того же места даже через неделю.

## NOVA Engine (свой движок на Java) — главное направление, план: docs/ENGINE_PLAN.md
- [ ] этап 1: прототип — мир 0.14 из LevelDB, текстуры из установленной игры, ходьба, блоки (GLES 3.0, Java 8 + dx)
- [?] beta3: генератор из 0.14.3 (world/Generator, Noises, Layers, McRandom, Biomes). Точно по дизасму: MT Random,
  ImprovedNoise/PerlinNoise/SimplexNoise (float, тот же порядок операций), getHeights (3x3 смешивание биомов,
  весы из +0x3c), prepareHeights, buildSurfaceAtDefault/_placeBedrock, оверрайды ExtremeHills/Taiga/MutatedSavanna.
  Слои биомов = цепочка getDefaultLayers (как Java 1.9 GenLayer, проверены init/initRandom/nextRandom, списки
  BiomeInit: в medium 3 plains, Voronoi во float). Глубины/температуры биомов взяты как в Java 1.9 — не сверены
  с Biome::initBiomes (0x5bdf28). Сверить с оригиналом по сиду (скрин одного места в 0.14.3 и в движке).
- [ ] генератор дальше: LargeCaveFeature (LargeFeature::apply 0x5ebfc4, Random потока после buildSurfaces),
  RandomLevelSource::postProcess 0x644680 + BiomeDecorator (деревья, руды, цветы, трава), MesaBiome 0x59d208,
  SwampBiome 0x59d7c0. Сейчас пещеры/руды/растения — временные.
- [?] beta4: «полупиксели» — причина: отступ в полтекселя в Atlas (16 текселей в 15). Убран. Песчаник/кварц/подзол
  по newtex_map (sandstone: 0 верх, 1 резной, 2 гладкий, 3 бок, 4 низ). Кактус: бока внутрь на 1/16 (Blocks.inset).
  HUD: хотбар из gui.png 0.14 (бар 182x22 @0,0; выделение 24x24 @0,22).
- [?] beta5: Decorator.java — порядок/счётчики BiomeDecorator::decorate, сид чанка, руды (decorateOres+OreFeature
  точно), дуб TreeFeature (точно: высота r%3+5, _prepareSpawn, листва), позиции деревьев/цветов/травы. Генерация в
  2 фазы: рельеф (потоки) -> декор, когда есть рельеф 3x3 (один поток) -> показ, когда 3x3 задекорированы.
  Трава на боках блока: свой оверлей (атлас: nova_grass_side + nova_grass_side_overlay, красится биомом).
  Управление: тап = поставить, удержание = ломать (повтор 250 мс). Кнопки и прицел из gui.png / icons.png.
  Инструменты: engine/tools/publish.sh (сборка+пуш+проверка), GitHub Actions nova-engine.yml -> релиз engine-latest.
- [?] beta6: управление 0.14 из кода (MinecraftBindingFactory::onConfigChanged: px/мм = 3+(H/96-3)*0.5, шаг 16 мм,
  кнопка 20 мм, прыжок 20 мм справа, полёт: вверх/вниз = прыжок -/+0.85J x0.8; глифы gui.png 26x26, масштаб 0.85,
  цвета ButtonColors (1,1,1,.498)/(.753..), BUTTONS_TRANSPARENCY 0.65), центр крестовины = присед, двойной тап прыжка =
  полёт; тап = поставить, удержание = ломать ПОД ПАЛЬЦЕМ (useFreeformPickDirection); кольцо прогресса у пальца
  (ui_invert_overlay: 1-dst), подсветка блока (ui3D selection_overlay: DestColor/SourceColor, цвет 0.65, offset 5).
  Не перенесено: пауза/чат (их прямоугольники не разобраны), точная геометрия кольца (меши GuiData +0xcc).
  Вода: туман под водой 0..10 блоков (LevelRenderer::_setupFog), цвет пока Java (DEFAULT_UNDERWATER_COLOR не найден),
  поверхность воды видна снизу. Цвета травы/листвы: FoliageColor (углы из static init через эмуляцию
  work/tools/emu_init.py + unicorn), Mesa/Swamp как в коде. Снежный бок травы. Тыква с лицом по data.
- [ ] FoliageColor::buildGrassColor (смешивание по столбцам гауссом + шум), Swamp шум, roofed.
- [ ] ДЕКОРАТОР дальше (approx в Decorator.java): Birch/Spruce/Pine/Jungle/Fancy/Swamp/Savanna/Roof/Mega деревья,
  Sand/Clay, TallGrass, FlowerFeature + getRandomFlowerTypeAndData, Reeds, Cactus, DeadBush, Waterlily, Pumpkin
  (_getRandomSurfacePosition), HugeMushroom, Spring; оверрайды decorate (Forest/Plains/Taiga/Jungle/Desert/
  ExtremeHills/Ice/Savanna); озёра, данжи, лёд в postProcess; LargeCaveFeature. Уже разобрано (для справки):
  postProcess 0x644680: Random(levelSeed) -> a=(r>>2)*2+1, b=(r>>2)*2+1; seed = levelSeed ^ (cx*a + cz*b) (координаты
  чанка); озёра/данжи/структуры/лёд до декора; декор = biome vtable[14] decorate(src, rnd, chunkMin, flag=0, FLT_MAX).
  BiomeDecorator::decorate 0x599778 порядок: decorateOres (vtable[0]); +0xec x(песок +8) / +0xf0 (глина +4) / +0xe8
  (гравий +0xc) в getTopSolidBlock(x+r&15, z+r&15); деревья: t=+0xcc float; r%10==0 -> count=(int)(nextFloat*t),
  трава=как у plains; t<1 -> (t>nextFloat); иначе (int)t + max(0, r%10-7); дерево = biome vtable[4] getTreeFeature,
  позиция _getRandomTreePosition (x,z = 1+r%14, y=heightmap), после дерева TallGrassFeature(31,1).place(..,23,3);
  +0xf4 большие грибы(+0x48); цветы +0xd0 (y=r%(heightmap+32), getRandomFlowerTypeAndData, placeFlower если там
  НЕ воздух); трава +0xd4 (y=_getRandomHeight = r%(heightmap*2), getGrassFeature); +0xd8 сухие кусты(+0x64);
  +0xc8 кувшинки(+0x54, опуск. по пустоте); +0xdc грибы (r&3==0 коричн. на heightmap, r&7==0 красн.); ещё r&3, r&7;
  тростник +0xe0 (+0x4c) и ещё 10; тыква r&31==0 (_getRandomSurfacePosition, +0x58); кактус +0xe4 (+0x50);
  если +0xf8: 50 водных родников y=r%(r%120+8), 20 лавовых y=r%(r%(r%112+8)+8); FoliageColor::buildGrassColor.
  Значения по умолчанию: деревья 0, песок 3, трава 1, гравий 1, глина 1, цветы 2. Дуб: высота r%3+5, листва как в Java.
  Осталось: значения по биомам (конструкторы *Biome), фичи (Tree/_prepareSpawn/_placeTrunk, Birch, Spruce, Pine,
  Ore+decorateOres, Sand, Clay, TallGrass, Flower, Reeds, Cactus, DeadBush...), getTreeFeature биомов.

## Моды: блоки
- [?] alpha10: иконка модельного блока в инвентаре — рисуются только грани к камере, от дальних к ближним
  (раньше «просвечивало»). Если всё ещё плохо — смотреть материал bt+0x2c0 в `renderGuiBlock` (глубина/отсечение).
- [?] alpha13: лампа в руке всё ещё чёрная (alpha12 тоже). Теперь: настоящая нормаль грани (Tessellator::normal) + внешний порядок.
  Если не помогло — возможно, это тёмная стойка лампы вблизи; попросить скрин крупно.
- [?] alpha10: столкновения блоков `collision` (список боксов / model / full / none). vtable 8 addAABBs, 10 addCollisionShapes,
  9 getAABB (рамка). Проверить: стол (по ножкам и столешнице), лампа.
- [?] alpha10: частицы ломания/шагов с модами — у частиц шаги UV зашиты под атлас 512x256, с полосой модов атлас 512x512.
  `nativeTerrainHeight` правит 2 константы (0x363d10/0x363d18). Если частицы были неправильные и БЕЗ модов — искать дальше.
- [?] свет лампы (проверить ночью).
- [ ] поворот модельного блока при установке (по взгляду игрока) — data 0..3 → поворот всей модели.

## Размер игрока и мобов (чинить ТОЛЬКО когда пользователь сам вспомнит про размер)
- [~] alpha11 работает «неплохо». Что сделано: native/scale.inc (хитбокс, глаза +0xc0, шаг +0xcc, физика через гейты
  Mob::travel + Entity::move, прыжок, модель через MobRenderer::setupPosition, ближняя плоскость 0x46d5d0/0x46d5d4).
- [ ] замечания пользователя: в начале двигается «не туда»; камера от 1-го и 3-го лица; частицы шагов по размеру;
  чем дальше от 1, тем больше багов (физика, скорость анимаций ходьбы и т.д.).

## Предметы
- [?] alpha13: предметов не было в инвентаре — скорее всего в конце длинных вкладок (ползунок не давал долистать).
  Теперь блоки и предметы модов ПЕРВЫМИ в каждой вкладке (сдвиг Item::mCreativeList); при входе в мир — красное
  сообщение, если предметы не зарегистрировались.
- [?] alpha12: `item` (texture любого квадратного размера, size hd/pixel, hand item/tool, stack, durability, category,
  action — кнопка). Иконки в свободных строках атласа предметов (y 176..511), Item::setIcon(TexSet*), id 470..499.
  «pixel»: гейты ItemInHandRenderer::renderItem + _renderObject масштабируют MatrixStack::World.
- [ ] 3D-предметы из Blockbench (модель в руке, настройка положения относительно руки: display в .bbmodel).
- [ ] еда, рецепты (`food`, `recipe` — пока ошибка «появится в следующих версиях»).

## Триггеры и API
- [?] alpha12: события player.jump/sneak, block.use/break, item.use, entity.attack/use, action, chat;
  player.target/item/itemData/sneaking/onGround; ui.action; math.* (27 новых) и vec.*.
  GameMode — патч vtable (слоты 3,12,13,14,15); кнопка действия — гейты MinecraftInputHandler::canInteract/getInteractText
  и MinecraftClient::handleInteractButtonPress.
- Гейтов: 25 из 31 (место для пещер расширено до 0x6ea160 — до файлового начала RW-сегмента).

## Идеи (большие)
- [ ] Эффекты для модов (ответ на трейлер MegaCraft 0.2.0): fx.* — светящиеся спрайты/кольца/лучи/молнии/«чёрная дыра»
  (квадраты с аддитивным смешиванием, вращение, анимация), частицы с физикой, обломки блоков (падающие блоки),
  камера для роликов (полёт по точкам, плавность), цвет неба/тумана, свои модели сущностей (Blockbench) + анимации.
  Рисовать в мире через Tessellator + свой материал (как модели блоков), хук после отрисовки мира.
- [ ] Движущиеся постройки (платформы, лифты, вращающиеся комнаты — как в трейлере MegaCraft / мод Create):
  область блоков -> один меш, двигается матрицей каждый кадр (плавно, 60 fps); столкновения — боксы блоков,
  добавляемые в сбор AABB уровня; игрок/мобы на платформе едут вместе с ней (сдвиг на дельту платформы);
  в конце блоки возвращаются в мир. API: structure.capture(x1..z2) -> s; s.move/rotate/to(..., время, плавность).
- [ ] Свои сущности (босс): модель Blockbench + анимации .animation.json с интерполяцией каждый кадр (не по тикам),
  скриптовое управление (идти к точке, атака, фаза), хитбокс, здоровье.
- [ ] Иммерсивные порталы (как мод Immersive Portals): видеть и проходить через портал в другое место без загрузки.

## Интерфейс
- [?] alpha13: ползунок творческого инвентаря (ПК-вид): свой scroller_desktop в inventory_screen.json — шире (12),
  без скрытого touch_box (подозрение: он «возвращал» ползунок наверх), длина — движок (_updateVisability).
- [?] alpha13: иконка приложения (tools/gen_icon.py -> res_new/): бок блока травы + логотип NOVA + «CLIENT» шрифтом игры.
- [?] alpha13: кукла + 3-е лицо — дёргается голова. Попытка: кукла сохраняет/восстанавливает позы костей (parts[] f[0..5]).
  Если не помогло — нужно видео; смотреть PaperDollRenderer::render -> PlayerRenderer::renderGui (тот же PlayerModel).
- [?] alpha9/10: переход заставка → логотип без пустого кадра.

## Идеи на потом
- NoCubes (сглаженный ландшафт) — отложено, сложно.

## beta8 (decoration)
- RE checked: BiomeDecorator::decorate has NO grass after each tree (removed), TallGrassFeature::place() = 90 tries / spread 8,
  FlowerFeature::placeFlower = 64 tries (&7,&3), tree count branch, Plains/Taiga/Savanna/Forest::decorate structure (DoublePlantFeature x7 etc).
- Trees ported from the same-era (Java 1.8) algorithms: big oak, birch/tall birch, swamp, acacia, dark oak (roofed grid + huge mushrooms),
  mega spruce/pine (+podzol), mega jungle, jungle, shrub. Not byte-checked vs the .so yet: vines/cocoa skipped (RNG order differs).
- Double plants (175, top = 8|type), tulips/bluets/daisies by info noise in plains, ferns, mossy boulders, desert well, melons, emerald.
- TODO structures: villages, mineshafts, strongholds, desert/jungle temples, dungeons, ocean? (0.14 has villages + mineshafts + strongholds? check StructureFeature list in .so)

## beta9 (seeds, caves)
- Seeds = LevelSettings::parseSeedString (0x53a248): len<=1 -> random(!), trim, sscanf %d (prefix), -1 only from "-1",
  else Util::hashCode = h*31 + byte (UTF-8). World menu (top right): type seed / recent / copy; last seed remembered.
- Caves = LargeCaveFeature exact port (apply 0x5ebfc4 range 8, addFeature 0x622a20, addRoom 0x622880, addTunnel 0x621d30,
  isDiggable 0x605858: stone dirt grass hardened/stained clay sandstone red sandstone mycelium podzol, sand unless water above;
  _thinSand: 3 sand above -> sandstone/red sandstone). No ravines in 0.14. Applied in loadChunk after buildSurfaces.
- Next: RandomLevelSource::postProcess 0x644680 (structures postProcess, lakes?, MonsterRoom), StructureFeature (villages,
  mineshafts, strongholds, scattered: SmallTemple/SwamplandHut...), spawn (Level::validateSpawn?).

## beta10 (postProcess: lakes, dungeons)
- RandomLevelSource::postProcess 0x644680 order (normal worlds, flag +0x18 == 0): R = chunk random;
  water lake if biome(max corner) not desert/desertHills and (R&3)==0 at (R%9+3, R&127, R%9+3);
  lava lake if (R&7)==0: x=R%9+3, y=R%(R%120+8), z=R%9+3, only y>59 and (y<=63 or R%10==0);
  4 structure LargeFeature::apply (+0xc4,+0x168,+0xf4,+0x13c) reseed; R reseeded; StructureFeature::postProcess x4;
  8x MonsterRoomFeature at (R&15)+8,(R&127),(R&15)+8; freeze (ice/snow) loop; R reseeded -> Biome::decorate.
- LakeFeature::place 0x60ca70 ported (float blobs, centre f*(15-size/2) without the Java +1 offset, _check, grass, lava stone).
- MonsterRoomFeature::place 0x631b30 ported; TODO chest loot RNG (WeighedTreasureItem::addChestItems) + spawner mob id,
  chest entity model (now a block from chest_inventory tiles).
- TODO next: structures (StructureFeature: villages SimpleHouse/SmallTemple/BookHouse/SmallHut/PigHouse..., mineshafts,
  strongholds SH*, scattered: SwamplandHut + temples?), freeze loop.

## beta11
- Spawn = BiomeSource::getSpawnPosition 0x581b84 / findValidSpawnPosition 0x581bf8: x=0,40,80.. z=0, 10x10 cells of the 1:4 layer,
  cell + 4 neighbours in {forest, plains, taiga, taigaHills, jungle, jungleHills, forestHills}; pos = (x+col*4, z+row*4).
- TODO verify terrain vs original: emulator diff of RandomLevelSource::loadChunk for seed mimi (3351608).

## beta12 (biomes verified in the emulator)
- tools/emu_layers.py: runs Layer::getDefaultLayers + every layer's fillArea of the real .so in unicorn (fake Biome
  objects: vptr Biome/MutatedBiome, temp +0x4c, rain +0x50, type +0x90, id +0x94, parent +0x9c) -> layer-by-layer diff.
- BUG found: MCPE magnify gives every zoom seed+1 (not seed+i). Fixed -> all 32 layers + river branch + Voronoi equal
  on several seeds/windows (mimi, 12345, -1919 as unsigned).
- Spawn: neighbours by flat index of the 10x10 buffer (row wrap) -> mimi spawn 1392,32 == original screenshot.
- Next verification: RandomLevelSource heights/surface in the emulator (prepareHeights + buildSurfaces).

## beta13 (snow, ice)
- Ice: postProcess freeze loop (shouldFreeze 0x527f80: temp <= RAIN_TEMP_THRESHOLD(0.15), still water data 0, block light <= 9).
- Top snow: Seasons::postProcess 0x5b3530 after decorate: biome snow accumulation (setSnowAccumulation min/max):
  frozenOcean/frozenRiver/coldBeach .125/.25, iceFlats .25/1, iceMountains .375/1.5, taigaCold(+Hills, M), ice spikes .125/.5;
  depth = ceil((-0.17 + (noise3(pos)+26)*0.01923077) * int(min*8)), +1 per higher neighbour, -1 per lower (if >1);
  noise = PerlinSimplexNoise(0x15cf0, 5).getValue(Vec3) (3D simplex sum v(p*f)/f, f halves) - 3D simplex is the standard
  algorithm, not byte-verified. canPlaceTopSnow approximated. Snow layer height = (data+1)/8.

## beta14 (villages)
- VillageFeature::isFeatureChunk 0x5e6cb0: seed = ax*0x9939F508 + az*0xF1565BD5 + levelSeed + 10387312 where ax = cx (cx-39 if <0)
  (the CHUNK coordinate, not the region: MCPE quirk), spacing 40, offset u%28; biome check containsOnly r=0 {plains, desert, savanna}.
  The start random = that MT after 2 draws. No biome check for roads/houses (Java has one).
- Layout (VillageStart, weights, StraightRoad children, generatePieceFromSmallDoor, LightPost fallback) ported; mimi village at
  chunk 86,2: all 23 pieces identical to the emulator (tools/emu_village.py).
- Buildings: tools/emu_pieces.py runs every piece's postProcess in unicorn with placeBlock/generateBox/fillColumnDown/
  generateAirColumnUp/getBlock/createDoor/createChest hooked -> VillageData.java programs per orientation/variant, replayed
  in Village.piecePlace (clip to chunk, average ground per first chunk, biomeBlock for desert, crop ages 2+u%6).
- Roads: grass path on top solid (liquids included) - 1, planks over liquid. selectCrops: u%3!=1 wheat else start-position hash crop.
- TODO: chest loot (createChest consumes R), spawner/villagers, door hinge, structures: mineshaft, stronghold, scattered (temples, hut).
- Render: MODEL kind (stairs, fence, pane/bars, door, ladder, carpet, torch); ice culling (water back faces drawn explicitly,
  culling on in the translucent pass; water hides faces against ice); snow layers of different depth show their sides.

## beta15 (fixes, pyramids, witch huts)
- Stairs/doors in the recorded programs are raw: StructurePiece::getOrientationData 0x5f6418 applied at replay
  (stairs: S 2<->3; W 0>2 1>3 2>0 3>1; E 0>2 1>3 2>1 3>0; doors: S 0<->2, W +1, E +3). Torches/ladders were already converted.
- Shapes.java: boxes per block for mesher, selection overlay (now the block's own boxes) and the pick ray (ray vs boxes).
- Torch: 2/16 stick from the tile centre, wall torches lean out (data 1..4).
- Water: no faces against any non-plant block (coplanar with farmland etc. flickered).
- Auto-jump: decided after the blocked move, jump on the next tick with the full 0.42 (was 0.83 blocks high).
- Hold ring: radius = 3.5 * min(4 mm in px (xdpi), 40) px (HudProgressRenderer); shown only over a block; hold restarts
  over air / on another block. NOTE 0.14: the ring is GameMode::getDestroyProgress (survival); creative breaks right away
  then every 250 ms (GameMode::continueDestroyBlock, delay 0xfa ms) - no ring in creative.
- Buttons x1.5 of the original (was x2); fly up directly above fly down, touching.
- RandomScatteredLargeFeature 0x5e7c40: spacing 32 sep 8 with the LargeFeature chunk random (1 draw by addFeature);
  biome at centre: desert/desertHills -> DesertPyramidPiece 21x15x21, swampland(M) -> SwamplandHut 7x7x9, facing S.
  Programs recorded by tools/emu_scattered.py. Hut height = updateAverageGroundHeight (max(top, 64)).
- TODO: mineshafts, strongholds; chest loot; spawners' mobs.

## beta16
- Hold ring = TouchTurnInteractControl state machine: shown while a finger is down; look stage (moved past threshold) never breaks;
  pressed 400 ms -> destroy (creative: break now, then every 250 ms; ring = time to next break). Tap < 250 ms = build.
- Selection outlines the real block mesh (Mesher.single), crosses = 2 planes, torches = torch mesh.
- Torch = renderTorchAtAngle (4 planes +-1/16, top cap 0.625, wall: offset 0.1 / lift 0.2 / tilt 0.4).
- Structure finder: world dialog -> "Постройки рядом" (villages / pyramids / witch huts within 100 chunks, tap = teleport).
- TODO: touch move threshold is 10dp (original 0.05 normalized), door hinges.

## beta17 (ring traced through the whole chain this time)
- TouchTurnInteractControl (0x2c340c) + switchState: down -> press button.turn_interact (GuiData::setShowProgress(true));
  move delta = sum |d| x 4 (jumps > 200 = 0), moved when delta / width >= 0.05; 0.4 s still -> press
  button.destroy_or_interact (stays pressed while turning until the finger is lifted); up: tap (build) when < 0.25 s and
  not moved; idle releases both.
- MinecraftClient::tickBuildAction: while destroy is held, aimed block -> GameMode::continueDestroyBlock (creative:
  _creativeDestroyBlock every 250 ms, 0xfa at GameMode+0x50); aimed at nothing -> stopDestroyBlock (no-op creative).
- HudProgressRenderer::_renderProgressIndicator: alpha via GuiData::calcNewAlpha (+0.02 / -0.04 per frame) toward
  showProgress; destroyProgress > 0 (survival only; creative never sets it) -> ring + disc (0.5+0.5p), alpha x 0.8;
  else ring only, alpha x 0.8 on block/entity, min(alpha x 0.4, 0.4) on no hit.
- Stairs corners: StairBlock::setStepShape / setInnerPieceShape ported (outer quarter / inner extra quarter).
- Verified: hut roof stairs (getOrientationData stairs branch, hut orientation fixed S by ScatteredFeaturePiece ctor).
- Village variants in 0.14: only plain and desert (VillagePiece::biomeBlock checks one desert flag).
- TODO structures: mineshafts, strongholds, nether fortress (no Nether yet). Field 0x2c of the touch control (move
  sensitivity) assumed 1.

## beta18
- Ring: material ui_invert_overlay = flat_white (always vec4(1)): full inversion always, the alpha only decides while it is
  drawn (fade +0.02/-0.04 per frame -> stays ~0.4 s after lifting).
- Block::use on tap (GameMode::useItemOn, sneaking skips): door (lower data ^ 4), trapdoor 96 (data ^ 8, new block),
  chest/workbench/furnace consume the tap. Door shape = DoorBlock::getVisualShape (0.1825, open + hinge from the composite).
- Liquids: LiquidBlock::shouldRenderFace (same liquid / ice no; up always; else neighbour not solid), corner heights
  BlockTessellator::getWaterHeight (x11 for source/falling), top - 0.001, sides 0.001 inside.
- Biome colours: FoliageColor::buildGrassColor per chunk column (5x5 biome grass 4 apart, sum*4/96, + (floor(noise)-30)/2
  + rand&7 - 4, MT(min.x*0x88f9fa+min.z), GRASS_NOISE = PerlinSimplexNoise(0x15cf0, 5)); leaves = avg of 8 foliage 4 apart;
  water = avg 3x3 of Biome +0x6c (default 0.596,0.910,1.0; swamp 0.784,0.686,0.294); swamp grass noise 2345 @0.0225,
  mutated -> parent colours. TODO: Seasons leaf tint (fancy leaves layer).
- JSON UI engine (ui/JsonUi): _ui_defs.json files from the game, inheritance, variables, anchors, sizes, layers, images
  with nineslice, labels (lang files, default8 + unicode glyph pages), buttons. Title screen = start.start_screen +
  CubemapBackgroundScreen panorama (fov 120, T += dt*30, sin(T*0.0025)*25+20 X, -T*0.1 Y) + gradient 0.35, splash
  (1.3 + sin(t*7.222)^4*0.06) * 100/(len+32), -20 deg. Gui scale = MinecraftClient::calculateGuiScale. Only Play works.
- TODO: mineshafts, strongholds; other buttons/screens; touch threshold 0x2c.

## beta19
- Fix: the title screen was empty - the UI json, lang files, splashes, panorama, newgui, title, glyph pages were not
  packed into our APK (assets come from the engine APK). Packed now (assets/ui, loc, images/gui/..., font glyph 00 01 04 20 21 22 25).
- First person empty hand (render/HandRenderer): InGamePlayScreen::_renderItemInHand fov 70 / near 0.05, setPlayerBob
  (0.1, 4th root above 1, x2, clamp 10; bob 0.4 per tick in LocalPlayer::aiStep), ItemInHandRenderer::render empty hand
  chain, PlayerRenderer::renderRightHand (setupAnim zeros -> zRot 0.1), geometry.humanoid.custom rightArm + rightSleeve,
  steve.png, entity.vertex FANCY lighting. Mob::swing / updateAttackAnim (6 ticks) on break / place / use.
- TODO: LevelRenderer::bobView (walk bob) for the hand and camera, tile light colour at the player, equipped height anim.

## beta20
- Mineshafts (world/Mineshaft.java), ported from the binary and checked against the original run in unicorn
  (tools/emu_mineshaft.py + tools/MineshaftCheck.java.txt): 6 starts (incl. surface variant), every piece and every
  block identical. MCPE quirks kept: room addChildren gate nextFloat > factor, spiders only when bb.y1 <= 49 at
  creation, cobweb placed at z - 1 of the light check, spawner light read with world coords through the local
  getBrightness, rails 0.7 below light 9 else 0.9, getBrightness two blocks above, getTorchData table, rail data 1 on
  W/E. Surface (dark oak, factor 0.5, moved to 68) when the chunk corner biome class is 8 (river).
  postProcess order now village -> mineshaft -> scattered (stronghold slot between them still empty).
  Sky light approximated: 15 when nothing opaque above, else 0.
  Finder lists mineshafts too.
- Strongholds (TODO next): StrongholdFeature::isFeatureChunk: MT(level seed); angle = nextFloat*2pi, dist = (u&15)+40;
  for 3: ring point (cos, sin)*dist (Mth sin table), search 16x16 chunks around for a VILLAGE chunk
  (VillageFeature::isFeatureChunk); found -> angle += 2pi/3*, dist += 8; not found -> angle += pi/4, dist += 4, retry.
  Pieces SH* = Java 1.8 set (13 types) - port + emulator check like the mineshafts.
- New from the original: LevelRenderer::bobView walking sway (camera + hand): angle = (f*a - walkDistO)*pi,
  translate(sin*b*0.5, -|cos*b|), rotZ sin*b*3, rotX |cos(angle-0.2)*b|*5, rotX tilt; Player::aiStep bob 0.4 toward
  min(speed, 0.1) on ground, tilt 0.8 toward atan(-yd*0.2)*15 in the air; walkDist += horizontal move * 0.6.

## beta21
- Fix: no rails / cobwebs in mineshafts - blocks 30 (web, cross) and 66 (rail, flat model) were not registered.
  Rail = BlockTessellator::tessellateRailInWorld (Java layout, data 0..9; mineshafts use 0/1) - not checked in the .so.
- Fix: walking micro-shake - bobView angle was (f*a - walkDistO)*pi; LevelRenderer::bobView 0x468d38:
  (old - cur)*a - cur = -(cur + (cur-old)*a), times pi.
- Fix: glass (and any solid-material block) under water flickered: LiquidBlock::shouldRenderFace hides the face
  against a solid MATERIAL (not only opaque cubes); water pass has polygon offset 0.1 (terrain_water material).
- LIGHT (world/Light.java): tables mLightEmission / mLightBlock / mTranslucency / mSolid dumped by emulating
  Block::initBlocks (tools/emu_blocks.py -> blocks.json). LevelChunk::recalcHeightmap column fill, LightUpdate::update rule
  (lb=max(1,lightBlock); emission sky=canSeeSky?15:0 / block=mLightEmission; light = max(emission, maxNeighbour-lb))
  computed as its fixed point: fill when 3x3 decorated, flood when 3x3 filled, visible after the flood; edits relight
  a 31 x 31 box (whole height if the column top moved) on the light thread (World.lightEdits).
  NOT exactly as the original at generation: MCPE updateFast only covers minHeight-4..maxHeight and leaves deeper
  caves with the column fill; we flood everything (= what its later update cascade converges to).
- Smooth light (Mesher): AmbientOcclusionCalculator::calculate (4 sides + corners only when a side is not solid,
  centre light = beside the face for solid blocks touching the edge else own, _blend zero->centre, >>2, shade 0.2 for
  solid, face shading 0.5/1/0.8/0.6); flat path for emitters (0.875/1/0.95/0.9) and translucency >= 0.9.
  Vertex gets TEXCOORD_1 = pair/16 (STRIDE 7), renderchunk shader multiplies by the 16x16 light texture.
- LightTexture::refresh (GameRenderer::_tickLightTexture): ramp (i/15)/((1-i/15)*3+1), block *1.6, sky *
  (0.05+0.95*skyDarken) with sunrise tint 0.3*a, red/green *(0.35+0.65*skyDarken), blue not, twice 0.03+0.96x, gamma 0.
- SKY (render/Sky.java): Dimension getTimeOfDay / getSkyColor (Biome HSB) / getFogColor / getSunriseColor /
  getSkyDarken / getStarBrightness / getCloudColor / getSunIntensity; renderSky: plane (centre = sky*getAmbientBrightness,
  ring 2000 = fog), sun 30 @120, moon 20 (8 phases), hidden 105..255 deg, stars (Random 10842, 1500, 0.25-2cos);
  clouds = TextureTessellator of clouds.png (16x4x16 cells, y 128.33, +0.02/tick, top or bottom faces by camera y 129,
  sides alpha>9, shades 1/0.75/0.925/1, alpha 0.7, fade past 0.9*far). Time +1 per tick from 0.
  NOTE: LevelRenderer::getAmbientBrightness reads +0x15a8 which nothing writes (0) -> 0.35 above y 55, 0.035 below:
  the sky top is that dark in the code. If the phone shows the original brighter, find the writer.
- FOG: view radius 12 chunks; onViewRadiusChanged far = max(R - 16, 40) = 176; updateFarChunksDistance
  m = clamp(far/2,48,160), fade = m*(0.5+amb*0.5), end = far+fade-m, start = end - clamp((end-40)/2,4,96)
  (~94..147 blocks at the surface); fog colour blended to sunrise by getSunIntensity(view)*sunrise.a; clear = fog colour.
- Hand lit by LightTexture colour at the player (TILE_LIGHT_COLOR).
- New from the original: cobweb slows you (WebBlock::entityInside -> makeStuckInWeb; Entity::move x0.25/0.05/0.25,
  motion cleared), not while flying.
- TODO: strongholds (placement decoded, see beta20), weather (rain/fog level terms are 0), underwater fog colour
  (Biome +0x7c), torch light flicker? (0.14 uses 1.6 fixed), entity shadows.

## beta21b (hotfix)
- World never appeared: the light tasks ran on the decoration thread behind its whole backlog (4 new decorations
  queued every frame) -> own light thread, up to 24 chunks per batch. largeHeap (12 chunks = ~1000 columns in memory),
  terrain / mesh worker errors no longer leave a chunk stuck.
- Sun glare from the code: LevelRenderer::startFrame stores getSunIntensity(a, camera, 0.9) at +0x1c9c;
  _skyDarkeningFactor = it * 0.2 is subtracted from fog (clear), sky and cloud colours; _renderSunOrMoon scales the sun
  by 1 + it*0.35 and above 0.2 draws it twice more with colour * it.
- Clouds: fancy.material "clouds" = Blending + DisableAlphaWrite, culling on (inner faces were visible), depth write on.

## beta21c (hotfix)
- Sky dark at noon (user: the original is bright): getAmbientBrightness v (+0x15a8) has no writer found; v = sky light
  at the camera (0..15) -> 1 in open air. TODO: find the real writer (memcpy / struct copy?) to confirm.
- Clouds opaque (user: no transparency in the original) = sad.material clouds (no Blending); fancy one had 0.7 alpha.

## beta21d (hotfix)
- Selection overlay turned dark brown: the flat path multiplied the texture in; selection_overlay uses the colour only.
- Water: renderchunk NEAR_WATER + FANCY ported (F = 1 - max(dot(view, up), 0.1); F = 1 - mix(F^4, 1, min(1, d/FAR_CHUNKS));
  rg -= F*56/255; near = mix((rgb*0.45, 0.8), (rgb*0.5, 1), colour.a); colour = mix((rgb,1), near, F); fragment alpha
  *= colour.a then mix to 1 by d/FAR_CHUNKS). Biome::DEFAULT_WATER_COLOR (0.596, 0.910, 1, a 0) from the init dump.
  FAR_CHUNKS_DISTANCE = LevelRenderer +0x1638 = min(fade, end - 13.856), >= 40 under water.
  Under water fog colour = Biome::DEFAULT_UNDERWATER_COLOR (0.0196, 0.0196, 0.196) * (0.35 + 0.65 b).
- Leaves (fancy) from the code: BlockProperty 0x20 (LeafBlock ctor) -> getShadeBrightness 0.2 (AO darkens around
  leaves); BlockOccluder::_shouldOccludeLeaves: deep leaves (isDeepLeafBlock) draw nothing; between two leaves only the
  down / south / east face; slabs / top snow / carpet / invisible bedrock rules; else hidden by opaque.
  LeafBlock::getRenderLayer: fancy -> layer 4 (alpha, single side), cold biomes [+0x30].

## beta21e (perf + shimmer)
- Distant shimmer: atlas had no mips and NEAREST. 0.14 ships terrain-atlas_mip0..3 = 2x2 box averages (verified) and
  samples with util.h texture2D_AA (TEXEL_AA + ATLAS_TEXTURE: mip from derivatives +0.5 bias, one-pixel bilinear edges,
  uv clamped inside the 16px face per level, two levels mixed). Ported; 4 box-filtered levels built for our atlas.
- FPS: real frustum culling of sections (planes from the mvp) instead of a view cone; opaque sections drawn near->far
  (early depth reject), blend far->near; chunk scans every 3rd frame (they box ~30k keys a frame); no per-face
  arrays in the mesher; one reused upload buffer.
- TODO perf from the original: LevelRenderer _updateColumnVisibility / VisibilityExtimator (occlusion of chunks behind
  terrain), RenderChunk sorting by camera, far-chunk fade-in.

## beta21f
- Leaves see-through from the side: fancy leaves are RENDERLAYER_ALPHATEST -> terrain_alpha (DisableCulling), so the
  one face kept between two leaves is seen from both sides (RenderChunk::_initLayers material order: opaque,
  opaque_seasons, doubleside, alpha, alpha_single_side, alpha_seasons, blend, water, far...). Leaf quads double-sided.
- Plants dark in the distance: the 0.14 atlas keeps colour in transparent texels (tallgrass 186, leaves 131); ours had
  black -> mips pulled cut-outs to black. Transparent texels now take their opaque neighbours' RGB (per 16px face).
- TexelAA is an option, off by default (RendererSettings +0x31 = 0 in Options::_initDefaultValues): shader uses plain
  texture2D; TextureOGL::setFilteringMode(TexelAA) then = mag NEAREST, min NEAREST_MIPMAP_LINEAR. Cheaper per pixel.

## beta22
- FPS: terrain split into the RenderChunk layers of the original: opaque (terrain_opaque, no ALPHA_TEST -> shader
  without discard, so early depth rejection works on mobile GPUs), alpha tested (leaves, plants, glass, models; own
  shader with discard), then blend/water. Before, all solid + cutout shared one discard shader -> no early-z anywhere.
- Light flash on place/break: relight reset the box to its sources in the live arrays and flooded back, meshes built
  in between came out dark. Now computed in a scratch box, only real differences written; section bumps batched
  (Light.TOUCHED + flush) so a flood triggers one rebuild per section, not hundreds.
- World.set writes id + data together before bumping sections (door: id then data race).
- Doors: could not reproduce in the JVM test (mesh changes with data ^ 4). Ask for exact case if still broken.

## open after beta22 (user report)
- FPS: first high, then falls to ~30 after a while (not only forest). Suspects: sections map only grows / far
  sections kept, mesh rebuild storms from light spreads, GC, thermal. First: on-screen counters (sections total /
  drawn / verts, meshes built per second, light tasks) to see what grows.
- Grass block side looks blurry since mips (beta21e/f): check mip choice at grazing angles (original
  NEAREST_MIPMAP_LINEAR too), the colour bleed on the side overlay's transparent texels, our overlay pass.
- Doors: still to confirm on device.

## beta23 (network start)
- Play screen = play.default_worlds_panel from play_screen.json (Worlds / Friends tabs, network_world_item with
  world_external, status dots, remove X, add server / new world / back). JsonUi: data bindings per built tree,
  binding_condition "visible" no longer hides bound labels, pressed node, scrolling panels drawn by the screen (clip).
  Layout checked offline with a Java2D stub renderer (work/uitest).
- Server list: external_servers.txt (id:name:ip:port), RakNet unconnected ping -> MOTD/players.
- net/RakNet.java: client (protocol 7 from RakPeer::RunUpdateCycle), split/reliable/ordered, ACK; tested in the JVM
  against Nukkit's 2016 RakNet server code (login 16 KB split up, 90 KB split down, ping MOTD). Fixed: no holes in
  reliable message indexes; first ordered index taken from the server.
- game/NetGame.java: protocol 70; Login (skin steve 64x64), StartGame, MovePlayer, Batch (zlib), FullChunkData
  (order 0 columns / 1 layered), UpdateBlock, Text (chat lines on the HUD), PlayStatus, Respawn, SetTime;
  sends MovePlayer, RequestChunkRadius, RemoveBlock(+PlayerAction), MobEquipment+UseItem, Animate.
- Add server = Android dialog (with nickname field) for now; TODO 1:1 AddExternalServerScreen.
- TODO next: skin picker 1:1 + custom skins, chat screen 1:1 (input), settings (nick), remote players/entities
  render (AddPlayer/AddEntity/MoveEntity), remaining blocks + collision shapes, survival inventory sync.

## beta23b: launch crash (prefs used before init for the nickname), INTERNET permission was missing, GL-thread errors shown instead of crashing, last crash shown on next start

## RE reference (re014)
- `nova ref-build` -> /home/claude/work/re014 (INDEX.md): per-class annotated .s (floats, strings, GOT, this+offsets,
  vtable slots, callers), fields/, vtables/, packets.md, calls.tsv, strings. Generators in engine/tools/re. Not committed.
