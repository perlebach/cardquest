# Card Quest (prototype)

A card RPG prototype in Kotlin + Compose Multiplatform: a Windows exe and a web page from the same code.

```
./gradlew run            # play
./gradlew run -Pdev      # play with the 🛠️ Dev tab (gold, levels, any card) for testing evolution etc.
./gradlew test           # rule tests
./gradlew simulate       # balance bot: plays the whole game automatically with each starter, prints progress
./gradlew simulate -Pseeds=40   # same, 40 runs per starter (steadier averages)
# env SIM_EFF=1: also print the party's effective level at each first clear; SIM_NO_BUY=1: the bot never buys cards
# env SIM_HEROES_ONLY=1: the bot fields only heroes; SIM_NO_COUNTER=1: it ignores boss traits (strategy comparisons)
# env SIM_NO_STARS=1: the bot doesn't collect stars (always auto skills, no replays of cleared stages)
# env SIM_NO_CAKES=1: the bot doesn't buy cakes (by default it spends gold above a reserve on cakes for cards below their level cap)
# env SIM_OLD_FEED=1: the old feeding (all loot at once into the strongest party card, most of it overflowed); SIM_DEBUG_PARTY=1: print the party at 4-15
./gradlew traits         # how many levels each hero is worth against each boss (is the trait's counter on top?)
# env TRAIT_BOSS_ONLY=1: only the boss wave; TRAIT_SKILL=5: skills at max level; TRAIT_DEBUG=4-10:40:WARRIOR,MAGE,…: play-by-play
./gradlew screenshots    # renders the main screens to build/screens (dev helper)
./gradlew stages         # prints every stage: fee, gold (and its ratio to the fee), monster counts, rarities, bosses
./gradlew economy       # shop hero copy vs raising bought greens with loot: replays needed per stage (env ECON_EFF, ECON_SELL, ECON_STAGES; ECON_MIX=1: idea 5b2, mixed monster rarities)
./gradlew gauntlet       # late dungeons vs all-hero parties: lowest winning effective level per stage (env GAUNTLET_DUNGEONS=8,9,10, GAUNTLET_SKILL=5)
```

Games are named; each one is saved automatically after every change to `~/.cardquest/saves/<name>.json`.
The start screen lists the saved games (name + party strength = sum of HP + ATK of the party cards) and starts new ones
(type a name, then pick the starter hero). ⚙️ menu → "Switch game" goes back there, "Start this game over" resets the open game.
An old single save (`~/.cardquest/save.json`) is converted into the game "Paja" on first start (the original is kept as `save.migrated.json`).

## Exe, updates and syncing saves (home server)

```
./gradlew packageExe     # build/distributions/CardQuest-windows.zip: CardQuest.exe with its own Java – unzip and run
./gradlew createDistributable   # just build/compose/binaries/main/app/CardQuest (the same folder, not zipped)
```

Both also publish the build to the home server (project `../gameServer`, run there with `gradlew run`):
the jars go to `~/.gameServer/games/cardQuest/update`, and every exe in the LAN downloads them at its next start
(into `~/.cardquest/updates/`), so an exe has to be copied to a computer only once. Exception: a new Compose/skiko
version needs a new exe (the native skiko library comes from the exe folder).

The exe also syncs all saved games with the server (each `saves/<file>.json` is the save `<file>` there): at startup,
before opening a game, and after each save. If a game changed on both sides it asks which version to keep and backs up
the other one (`saves/backup/` locally, `~/.gameServer/games/cardQuest/history/` on the server).
`gradlew run` (and `-Pdev`) never contacts the server; `-Dcardquest.network=true` forces it.
A manual server address goes to `~/.cardquest/sync.properties` as `server=host` or `server=host:port`.

## Web version

```
./gradlew wasmJsBrowserDevelopmentRun   # opens the game in the browser (dev build, reloads on changes)
./gradlew wasmJsBrowserDistribution     # build/dist/wasmJs/productionExecutable: the static web page to upload
```

The same game compiled to WebAssembly (Kotlin/Wasm); the folder above is plain static files, any static hosting works.
Games are saved in the browser's localStorage (same JSON as the save files; this browser on this device only).
`?dev` in the address shows the 🛠️ Dev tab. A browser has no system fonts, so the emoji and symbols the game uses come
with it (`src/wasmJsMain/composeResources/font`) – after adding new emoji or symbols run `python tools/web-fonts.py`
(needs `pip install fonttools`), otherwise they show as boxes on the web.

## Code map

Source sets: `src/commonMain` (rules + all screens, shared), `src/desktopMain` (exe: files, home server, updates),
`src/wasmJsMain` (web: localStorage, fonts, `index.html`), `src/desktopTest` (tests and dev reports).


| Package | What |
|---|---|
| `model/Model.kt` | rarities, skills, card types (base stats), **all balance formulas** (`Balance`), `OwnedCard` |
| `battle/Battle.kt` | deterministic battle engine (turn order, targeting, skills, cooldowns, waves); UI-independent |
| `content/Dungeons.kt` | the 10 dungeons × 20 stages, generated from a monster roster per dungeon (waves, levels, rarities, drop chances, fees) |
| `game/Game.kt` | `GameState` (what's saved) and `Rules` (fight rewards, feeding, evolving, selling, buying, party) |
| `game/SaveStore.kt` | named saved games (JSON) – shared logic; `SaveManager` (desktop: files, conversion of the old single save) and `BrowserSaveStore` (web: localStorage) store them |
| `game/ServerSaves.kt` | (desktop) syncing the saved games with the home server (library from `../gameServer`) |
| `ui/*` | Compose screens: saved games, starter pick, Cards, Party, Dungeons, Battle, Dev |
| `composeResources/drawable/*.svg` | hand-drawn hero portraits (cards without one use their emoji) |

## Numbers (all in `Balance` / `StageDef`)

* **Stats**: `base × 1.1^(effectiveLevel − 1)`, where `effectiveLevel = level + 5 × rarityIndex + 7.5 × evolutionStage`.
  So white 25 = green 20 = blue 15 = purple 10 = orange 5, and each "+" is worth 7.5 levels.
  Legendary **heroes** get +15 more (`LEGENDARY_HERO_BONUS`, `Balance.cardEffectiveLevel`): a maxed legendary is 80, above the maxed purple+++ (77.5)
  it was evolved from; legendary monsters don't get it.
* **Base stats (hp/atk)**: heroes Warrior 56/6, Mage 26/12, Rogue 30/10, Hunter 28/9, Warlock 26/10, Paladin 50/7, Berserker 40/8, Priest 35/6;
  monsters 14–50 hp, 3–8 atk. Bosses get ×1.6 hp per spot they occupy.
* **Card XP**: level L→L+1 needs `100 × 1.15^(L−1)` (`CARD_XP_GROWTH`; 1→2: 100, 29→30: ~5k; was 1.3 → ~155k). Hero cards need 2× more ("slow to level up"; `HERO_XP_FACTOR`).
* **Player XP**: `100 × 1.35^(L−1)`.
* **Dungeons** (each opens after stage 10 of the previous one): 🌲 Whispering Woods, 💎 Crystal Caves, 🌋 Fire Mountain, 🏔️ Frozen Peaks,
  🏜️ Scorching Desert, 🌊 Sunken Reef, 🌑 Shadow Realm, 🏰 Sky Citadel, 🍭 Candy Kingdom, 🌌 Cosmic Rift. Locked dungeons are not shown. Bosses at X-5/10/15/20.
  Difficulty tier = `10 × (dungeon − 1) + (stage − 1)`, so 2-1 ≈ 1-11 and 3-1 ≈ 2-11.
* **Stage generator**: monsters per wave grow with the stage number (1-1: 1, 1, 2 … X-20: 5, 5, 5), the same in every dungeon.
  How dangerous a stage is (`THREAT` by tier) is split into "effective level + log₁.₁(monsters per wave)", so 1-11 has
  more, weaker monsters (good for the Mage) and 2-1 fewer, stronger ones (good for the Rogue). X-10 monsters are about effective level
  11, 22, 31, 39, 46, 52, 55, 58 in dungeons 1..8 – the curve flattens because late cards grow slowly. A boss's level is set so that its
  wave is about as dangerous as an average wave of the stage (a boss on k spots has 1.6·k× hp). Monster types appear gradually
  (a few in stage 1, all by stage 11), at most one healer per wave. The layout is random but fixed (seeded by the stage).
* **Monster rarity ladder**: white, green, green+, blue, blue+, blue++, purple, purple+, purple++, purple+++, legendary (`Balance.ladderStep`).
  Dungeon d shows step d−1 in stages 1-5, step d in 6-15 and step d+1 in 16-20 (mixed with one step lower; the share of the new step
  grows through the band); the X-20 boss is one step above. So: green from 1-6, green+ from 1-16, the only blue of the woods is the 1-20 boss,
  first collectible blue in 2-16, blue+ boss in 2-20, first collectible blue+ in 3-16, blue++ boss in 3-20, …, legendary boss in 8-20, first legendary monsters in 9-16 (they never drop), legendary++ boss in 10-20.
  A monster whose level would be above its rarity's max level gets one more "+" instead (same strength).
  **Mixed rarities** (`MIXED_RARITIES` in `content/Dungeons.kt`, 2026-11-12): then every regular monster gets a ladder step picked evenly from white
  up to the one above, so a "rare" stage is about ¼ rare, ¼ green+, ¼ green, ¼ white. It keeps the original's strength, so lower rarities have higher
  levels, often above their max (white L52), plus `MIX_LEVELS_PER_RARITY` (0.5) per rarity below and `MIX_NO_SKILL_LEVELS` (2) for whites (no skill);
  it pays the original's gold (`MonsterSpec.goldBase`), so gold/fee stays ~3 (5–10 % lower: more drops). Bosses aren't mixed.
  Its card drops at its own rarity's chance, keeps its level (not above the player's), can't level up, feeds and sells as a max-level card.
  **Dungeons 9–10** are the end game: `THREAT` rises steeply after tier 89 (99 → 85, 109 → 93) so they are hard even for maxed purple+++
  heroes (`./gradlew gauntlet`, skills at level 5: purple+++ wins all of dungeon 9 and most of 10 up to 10-14; 10-18..10-20 need legendary++/+++).
* **Entry fee**: `5 × 2^(tier/10)` – 5 at 1-1, 10 at 1-11/2-1, 20 at 2-11/3-1, … ~1200 at 8-20.
* **Gold**: every beaten monster gives `1 + stepValue × 1.1^(effectiveLevel − 1)` (`Balance.cardGold`), where `stepValue` doubles per ladder
  step (×2.6 per step from purple+ on), times `0.5^(tier/10)` (`Balance.goldSlowdown`, so gold grows ×2 per 10 tiers like the fees).
  Bosses pay (spots + 1)/2 times that. A monster that drops its card gives no gold (bosses always pay). The first win doubles it.
  The gold shows up in the 🎁 Loot tray during the fight and is kept only if the fight is won. Result (`./gradlew stages`): a whole stage pays
  ~3× its fee on average (1.1–1.6× in 1-1..1-6, 2–4× in most stages, up to ~6× in boss stages; dungeons 7–8 are a bit lower, ~2–3×).
* **Selling**: 20 × `cardGold` (`SELL_FACTOR`, was 10) × `0.5^ladderStep` (`Balance.sellPrice`; a rarity step shows up about every 10 tiers, so selling slows down like the gold).
* **Fight XP**: aimed at a planned player level by tier (`PLAYER_LEVEL_PLAN`: 11 at tier 10, 22 at 20, 30 at 30, 35 at 40, 40 at 55, 45 at 70):
  player XP keeps ~2.5 fights per tier on 1.35 × that plan (`PLAYER_LEVEL_FACTOR`; scaling the level, not the XP, since ×1.5 XP is only
  ~+1.35 levels), so the player stays ~35–50 % above the monsters at each dungeon's start; card XP (every party card, knocked-out ones too) is 50 % of a level at that level (`CARD_XP_SHARE`, was 36 %).
* **Feeding** (`Balance.sacrificeValue`): 80 % (`SACRIFICE_EFFICIENCY`) of the XP of the eaten card's level (monster XP curve, a level above the
  rarity's max counts as the max) + flat bonus = total XP of the first `5 + 5×rarity + 2×evo` levels (`Balance.sacrificeBonus`).
  It depends only on level, rarity and evo – not on how the card got there (dropped, raised or evolved; there is no lifetime XP any more).
* **Skill level** (max 5, kept through evolution): +15 % power per level above 1 (`Skill.power`). For Shield Up the 15 % shrinks the damage that gets through
  (80 % block → 83 % → 85 % …), so it never reaches 100 %.
* **Max skill level bonus** (level 5, `Skill.maxBonus`): Shield Up also guards every friend in the warrior's row; Fireball sets every enemy hit
  on fire (`BURN_SHARE` 50 % of the fireball's damage at the start of each of its turns); Sneak Attack poisons (`POISON_SHARE` 40 %);
  Aimed Shot hits the whole row of the target (damage split between them) and stuns them all; Soul Leech also hits (and drains) the enemy
  right behind a front-row target; Flash Heal heals the paladin first, then the lowest-HP friend (can be itself again); Whirlwind also stuns
  everything it hits; Heal also removes every debuff (stun, burning, poison, `Fighter.cleanse`) from all friends. Burning/poison don't stack (a second one keeps the stronger), hit right before the card would act
  (they can kill it), go through shields and end with the wave.
* **Monster skill level**: regular monsters fight at skill level 1 (legendary ones at 4, `LEGENDARY_MONSTER_SKILL_LEVEL`), bosses always at the max level (with the bonus). To make up for it a
  boss gets `BOSS_SKILL_LEVELS` fewer levels (`content/Dungeons.kt`: Fireball 7, Whirlwind 3, Sneak Attack / Soul Leech 1.5), tuned with the bot.
* **Skill level-up**: every eaten uncommon+ card with the same skill adds a chance of 10 / 20 / 30 / 40 % (uncommon / rare / epic / legendary; was 10 / 30 / 50 / 70);
  the chances of one feeding add up (max 100 %) and one roll decides +1 level (`Rules.skillUpChance`, `Balance.SKILL_UP_CHANCE`).
* **Evolution**: max level + `evo+1` cards of the same type and rarity + `50 × 2^ladderStep` gold (100 for green, 200 for green+, …; was ×4, slowed down with the fees).
* **Buying hero cards** (Cards tab → 🛒 Buy cards): any hero as a level 1 green card. 100 gold, +50 per purchase, at most 1000
  (`Balance.HERO_PRICE_START / _STEP / _MAX`, `GameState.heroesBought`). Spare copies are what heroes need to evolve and rarity-up.
  Once a hero has been evolved to a higher rarity (the player owns it at blue, purple, …), the shop also sells it as a level 1 card of that
  rarity and every rarity below: blue 10k, purple 100k, orange 1M (`Balance.HERO_PRICE_BY_RARITY`, `GameState.heroShopRarities`).
* **Food cards** (🍰 Yummy Cake, `CardType.FOOD`): always level 1, no skill, can't fight, evolve or eat. Feeding one gives
  `FOOD_XP_FACTOR` (10) × the XP a normal card of its rarity/evo would give. Bosses drop one with `FOOD_DROP_CHANCE` (20 %) in their
  rarity/evo (and still pay their gold). The shop sells them (no evo): white 500, then the same XP per gold rounded down to 2 digits
  (`Balance.foodPrice`): 500 / 1600 / 4000 / 8800 / 18000.
* **Stars**: a stage keeps the best rating of a single fight: 1 for a win, +1 if no card was knocked out, +1 if no player skill was used
  (`GameState.bestStars`, `Rules.starsFor`). The conditions don't combine across fights: a no-skill win with a loss plus a no-loss win
  with skills is still 2 stars.
  Each dungeon gives rewards at 20 / 40 / 60 stars (`DungeonDef.milestones`): `1000 × 2^(d−1)` gold, 5 food cards of ladder step d−1
  (white, green, green+, …), and the dungeon's first front-row monster at ladder step d+2 (rare Snail, rare+ Skeleton, rare++ Rhino, …); from dungeon 8 on
  (where that would be a legendary monster) gold instead: 1M / 2M / 4M for dungeons 8 / 9 / 10.
  A dungeon with all 60 stars is shown folded up (click its title to open it).
* **Drops**: common 25 %, uncommon 20 %, rare 15 %, epic 10 %, legendary 0 % (never) per monster, ×0.75 per "+" (bosses drop food instead) – `monster()` in `content/Dungeons.kt`.
  Rolled the moment a monster is beaten (the card flies into the 🎁 Loot tray) and given to the player if the fight is won. Dropped cards have the monster's rarity/evo and 100 % of its level (`LOOT_LEVEL_SHARE`, was 80 %; rounded down, at least 1, capped like any card: `Balance.lootLevel`). A looted card carries the XP of its level, so it feeds exactly like the same card raised to that level by the player.

* **Party**: at most one hero of each class (`Rules.heroTwin`); monsters can be doubled.
* **Boss traits** (`BossTrait`, bosses from Fire Mountain on): each has one skill that breaks it – the matching hero or any monster with that skill.
  🪨 Stone Skin (Lava Rex, Pufferfish): 10 % damage until a 🌪️ Whirlwind shatters it · 🌋 Firestorm (Lava Dragon): Fireball every other turn,
  burning = 100 % of it; a 💚 Heal (any level) puts the fire out and makes the party fire-proof · 🔨 Crusher (Yeti, Sky Whale): triple-damage hits
  at the biggest card (or a raised shield), 10 % damage until it hits a 🛡️ shield · 📯 Commander (Frost Giant, Elder Dragon): fills the front row,
  25 % damage while its 2 back-row helpers (50 % hp, 130 % attack, 10 % from area hits) live – 🎯 Aimed Shot · 🎯 Ambusher (Sand Crocodile):
  double hits on the lowest-HP card, 10 % damage until a 💛 Flash Heal reveals it · 🐜 Swarm (Desert Wyrm, Void Lord): one spot in the back,
  helpers everywhere else (40 % hp, 50 % attack, 10 % from single-target hits), boss 10 % while they live – 🔥 Fireball ·
  ♻️ Regeneration (Leviathan): +25 % HP per turn until a 🗡️ Sneak Attack · ⚰️ Undying (Lich): 3 spare lives unless a 🔮 Soul Leech drains them.
  Numbers in `battle/Battle.kt` (top), layouts and `TRAIT_LEVELS` (boss level offsets) in `content/Dungeons.kt`.

The `simulate` bot (greedy, auto skills, naive formation, one hero per class, at most 2 monsters of a type) buys spare copies of its heroes
(green, raised and evolved with loot into the material its heroes need – like a human player),
starts a healer once it is rich, keeps evolution material aside and, when it can't win a new stage, takes a spare hero along to farm XP.
Before a trait boss it also tries the party with the counter skill (buying the counter hero if it has no such card) and keeps whichever wins.
Battles are deterministic (only loot is random), so the bot simulates a fight before it starts it and never loses: it never retries a stage
hoping for luck. When it can't win any new stage it first replays the highest cleared stage where it can now get more stars
(fighting without skills when that gives the "no skills" star), and otherwise farms the highest cleared stage it can win. It also buys
inventory slots when it's nearly full (or sells its weakest spare monster card). The `simulate` output splits the fights into
new stage / replay for stars / farming / lost, and a stage's "wall" counts every fight since the previous first clear (farming included).
With dungeons 9–10, the legendary hero bonus and the hero shop (60 seeds × 3 starters): median ~543 fights to clear all 200 stages
(p10 413, p90 708; 8-20 at ~319, 9-20 at ~449, 10-20 at ~543), 2 of 180 stuck; final parties are mostly legendary heroes (Berserker in 93 %).
The bot saves up for the shop copies its raised heroes need (otherwise cakes eat all the gold and its heroes stall at purple++).
**Feeding like a human** (2026-11-12): the bot feeds its loot one card at a time to a card that can take all of its XP: a hero's main card that can
learn its skill from it first (even if XP overflows), then the party, then the most advanced bench hero (a freshly evolved one at level 1), then the
tightest fit; a card that would lose over half its XP is kept for later while there are 4+ free slots (sold if it would lose ~all of it).
Before, it dumped all loot into the strongest party card (35 % of the fed XP was lost above the cap and its rarity-upped heroes sat at level 1).
With skill-ups 10/20/30/40 and selling ×20 (60 runs): median ~402 fights (2-16 @74, 4-15 @65, 8-20 @218), none stuck; card XP fights 14 %,
feeding 45 %, cakes 42 % (up to 4-15: 20 / 76 / 4 %); party heroes' average skill level 2.3 at 4-15, 3.8 at 8-20.
Value by level/rarity/evo only + mixed rarities (0.5 / 2, 60 runs): median ~417 fights (unmixed 390), none stuck; 2-16 @80, 4-15 @66, 6-20 @141,
8-20 @259 (unmixed 225); up to 4-15 feeding 85 % of card XP (unmixed 74 %). Loot XP per replay is 2–3.5× the unmixed one (`ECON_EFF=0.8 ./gradlew economy`):
the mixed-in low rarities drop at their max level, where the level part of the feed value is most of it.
SACRIFICE_EFFICIENCY 0.65 / 0.5 hardly changes it (median 404 / 402; up to 4-15 feeding 73 / 70 %): for typical drops the flat bonus is most of the value.
Older: Current numbers (g = `CARD_XP_GROWTH` 1.15, loot at 100 % of the monster's level, 60 seeds × 3 starters, ~1 minute on 16 cores):
median ~424 fights (p10 323, p90 698; ~160 new, ~85 star replays, ~175 farming), 1 of 180 runs stuck at 8-20; card XP from fights 18 %,
feeding cards 44 %, cakes 37 %. With the old 1.3 / 80 % (same 180 runs): median ~812, 19 of 180 stuck in dungeon 8, XP 87 / 11 / 1 %.
Older notes: with star hunting (6 seeds × 3 starters): median ~855 fights (~155 new, ~130 star replays, ~570 farming), ~435 of 480 stars,
4 of 18 runs stuck in 8-16/8-20; without it: median ~740, ~215 stars, 5 of 18 stuck. The star rewards don't pay back the replays yet.
With A+B+E and boss traits (10 seeds × 3 starters): median ~780 fights, 7 of 30 runs still stuck at 8-20; ignoring the counters, 18 of 30 get stuck
in dungeons 7–8; heroes only: median ~920, none stuck. (Before these changes: median ~450.) Still being tuned.

## Skills

| Skill | Green power | Effect | Heroes | Monsters |
|---|---|---|---|---|
| 🗡️ Sneak Attack | 400 % | the normal target | Rogue | Fox, Wolf, Lion, Boar, Scorpion, Rhino, Hellhound, Mountain Goat, Snow Tiger, Leopard, Sand Crocodile, Shark, Shadow Cat, Goblin, Pegasus |
| 🔥 Fireball | 50 % | all enemies | Mage | Imp, Crystal Dragon, Salamander, Lava Dragon, Frost Sprite, Frost Giant, Sun Sprite, Water Sprite, Leviathan, Void Spawn, Void Lord, Storm Sprite, Elder Dragon |
| 🛡️ Shield Up | blocks 80 % | itself, until its next turn | Warrior | Snail, Crab, Turtle, Hedgehog, Mammoth, Seal, Snowman, Cactus, Hermit Crab, Gravestone, Clockwork Knight |
| 🎯 Aimed Shot | 250 % | back row first (rows of the normal order swapped) + stun if it survives | Hunter | Bat, Frog, Owl, Raccoon, Octopus, Eagle, Penguin, Wasp, Shrimp, Clownfish, Watcher, Dark Elf, Comet, Thunderbird |
| 🔮 Soul Leech | 200 % | the normal target, heals itself by the damage dealt | Warlock | Skeleton, Ghost, Giant Spider, Vampire, Snake, Scarab, Anglerfish, Plague Rat, Lich |
| 💛 Flash Heal | 300 % | the wounded friend with the lowest HP | Paladin | Fairy, Deer, Phoenix Chick, Swan, Desert Rose, Dolphin, Star Spirit |
| 🌪️ Whirlwind | 100 % | the whole front row (back row if the front is empty) | Berserker | Bear, Grumpy Oak, Troll, Golem, Zombie, Cave Kraken, Ogre, Lava Rex, Ram, Ice Golem, Yeti, Camel, Giant Ant, Desert Wyrm, Lobster, Giant Pufferfish, Pumpkin Head, Sky Knight, Storm Cloud, Sky Whale |
| 💚 Heal | 50 % | all friends | Priest | Mushroom, Unicorn, Mermaid, Genie, Snow Dove, Peacock, Otter, Will-o'-Wisp, Cherub |

Old monster skills were merged into these: Swoop and Sticky Goo → Aimed Shot, Big Swipe → Whirlwind, Mend → Flash Heal.

## Decisions I made where the spec was open

* **Card background** = the rarity colour (light → dark gradient), so a white Troll no longer looks green.
  The old per-type colours are still in `CardType.bgTop/bgBottom`; `RARITY_BACKGROUND = false` in `ui/CardView.kt` switches back.
* **Skill modes**: 🤖 Auto (use whenever ready), ✋ I decide (ask every time), 🚫 Never (only normal attacks). Monsters always use theirs.
  The mode can be picked below the Fight button (and on the result screen) before a fight starts, or switched during it.
* **Skill timing**: a skill replaces the card's normal attack on its turn. Cooldown 4 = after using it, the card can't use it on its next 4 turns. Skills are ready at the start of a fight;
  cooldowns carry over between waves (`Balance.RESET_COOLDOWN_EACH_WAVE = false`, `Balance.SKILL_COOLDOWN`).
* **Guard** ends at the start of the card's next turn. Minimum damage of a hit is 1.
* **Stun**: the stunned card skips its next turn (shown greyed out with circling stars).
* **Level cap by player level**: XP above the cap fills the bar for the next level and the rest is lost, so the card levels up as soon as the player does.
* **Bosses** occupy several spots; they act at their first (lowest) spot and are hit through any of their spots.
* Hero cards can't be sold or fed in the UI (to protect kids from losing the starter). Cards in the party can't be fed/sold/used as evolution material.
* Running away from a fight counts as a loss. Closing the app mid-fight also forfeits the fee.
* A wave that lasts 60 rounds counts as a loss (prevents endless heal loops).
