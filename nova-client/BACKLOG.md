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
- [ ] ДЕКОРАТОР (следующий шаг). Уже разобрано:
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
