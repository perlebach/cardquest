package cz.erlebach.cardrpg.model

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Card rarity. [maxLevel] is the level cap, [maxEvo] the number of "+" evolution stages available. */
enum class Rarity(val label: String, val maxLevel: Int, val maxEvo: Int, val color: Long) {
    COMMON("Common", 25, 0, 0xFFE8E8E8),
    UNCOMMON("Uncommon", 30, 1, 0xFF3CC85A),
    RARE("Rare", 35, 2, 0xFF3C8CF0),
    EPIC("Epic", 40, 3, 0xFFA54BF0),
    LEGENDARY("Legendary", 45, 4, 0xFFFF9A1E);

    val next: Rarity? get() = entries.getOrNull(ordinal + 1)
}

/**
 * Skills. Each card type has one; common cards can't use it. [powerByRarity] is indexed by [Rarity.ordinal]
 * (common = 0). Power means % of attack (damage or healing); for [SHIELD_UP] it's the % of damage blocked.
 */
enum class Skill(val title: String, val icon: String, val powerByRarity: List<Int>) {
    SNEAK_ATTACK("Sneak Attack", "🗡️", listOf(0, 400, 450, 500, 560)),
    FIREBALL("Fireball", "🔥", listOf(0, 50, 60, 70, 80)),
    SHIELD_UP("Shield Up", "🛡️", listOf(0, 80, 84, 88, 92)),
    AIMED_SHOT("Aimed Shot", "🎯", listOf(0, 250, 280, 310, 350)),
    SOUL_LEECH("Soul Leech", "🔮", listOf(0, 200, 225, 250, 280)),
    FLASH_HEAL("Flash Heal", "💛", listOf(0, 300, 340, 380, 420)),
    WHIRLWIND("Whirlwind", "🌪️", listOf(0, 100, 115, 130, 150)),
    HEAL("Heal", "💚", listOf(0, 50, 60, 70, 80));

    /**
     * Power including the skill level bonus: every level above 1 adds [Balance.SKILL_LEVEL_BONUS] (15 %).
     * For Shield Up the bonus shrinks the damage that gets through instead (so it never reaches 100 %).
     */
    fun power(rarity: Rarity, skillLevel: Int): Int {
        val base = powerByRarity[rarity.ordinal]
        if (base == 0) return 0
        val factor = 1 + Balance.SKILL_LEVEL_BONUS * (skillLevel - 1)
        return if (this == SHIELD_UP) (100 - (100 - base) / factor).roundToInt()
        else (base * factor).roundToInt()
    }

    fun describe(power: Int): String = when (this) {
        SNEAK_ATTACK -> "Deals $power% of attack to the enemy it faces."
        FIREBALL -> "Deals $power% of attack to ALL enemies."
        SHIELD_UP -> "Blocks $power% of incoming damage until its next turn."
        AIMED_SHOT -> "Shoots over the front row: deals $power% of attack to a back-row enemy (if there is one) and stuns it – it skips its next turn."
        SOUL_LEECH -> "Deals $power% of attack to the enemy it faces and heals itself by the damage dealt."
        FLASH_HEAL -> "Heals the friend with the lowest HP by $power% of attack."
        WHIRLWIND -> "Deals $power% of attack to every enemy in the front row (or the back row, if the front row is empty)."
        HEAL -> "Heals ALL friends by $power% of attack."
    }

    /** [describe] plus the max-level bonus, if [skillLevel] is the max. */
    fun describe(power: Int, skillLevel: Int): String =
        describe(power) + (maxBonus?.takeIf { skillLevel >= Balance.MAX_SKILL_LEVEL }?.let { " ⭐ Max level: $it" } ?: "")

    /** Extra effect at [Balance.MAX_SKILL_LEVEL] (null = none). Every boss has its skill at max level. */
    val maxBonus: String? get() = when (this) {
        SHIELD_UP -> "Also shields every friend in its row."
        FIREBALL -> "Sets every enemy hit on fire: it burns for ${pct(Balance.BURN_SHARE)}% of the fireball's damage at the start of each of its turns (doesn't stack)."
        SNEAK_ATTACK -> "Poisons the enemy: it takes ${pct(Balance.POISON_SHARE)}% of the attack's damage at the start of each of its turns (doesn't stack)."
        AIMED_SHOT -> "Hits the whole row of the target (the damage is split between them) and stuns them all."
        SOUL_LEECH -> "If the target stands in the front row with an enemy right behind it, hits (and drains) that one too."
        FLASH_HEAL -> "Heals itself by the same amount first."
        WHIRLWIND -> "Also stuns every enemy it hits."
        HEAL -> "Also removes all debuffs (stun, burning, poison) from all friends."
    }

    private fun pct(share: Double) = (share * 100).roundToInt()
}

/**
 * Boss mechanics. Each one has a skill that is the best answer to it ([counter]); the stage can be won without it,
 * but it is much harder. Implemented in the battle engine (most) and the stage generator ([COMMANDER], [SWARM]).
 */
enum class BossTrait(val icon: String, val title: String, val description: String, val counter: Skill) {
    FIRESTORM("🌋", "Firestorm", "Casts its Fireball every other turn and burns hard. A Heal puts out the fire and makes your team fire-proof.", Skill.HEAL),
    COMMANDER("📯", "Commander", "Fills the front row and takes only a quarter of the damage until both its helpers are beaten. The helpers behind it are shielded from Fireball and Whirlwind.", Skill.AIMED_SHOT),
    CRUSHER("🔨", "Crusher", "Hits for triple damage. Stone armor (almost no damage) until it smashes into a raised shield – then it cracks.", Skill.SHIELD_UP),
    REGENERATION("♻️", "Regeneration", "Heals a quarter of its HP every turn – until a Sneak Attack wounds it deeply.", Skill.SNEAK_ATTACK),
    UNDYING("⚰️", "Undying", "Gets back up with full HP three times – unless Soul Leech drains its spare lives.", Skill.SOUL_LEECH),
    STONE_SKIN("🪨", "Stone Skin", "Takes almost no damage – until a Whirlwind shatters its stone skin.", Skill.WHIRLWIND),
    SWARM("🐜", "Swarm", "Takes almost no damage while its tiny helpers live. They are too small to hit one by one – but Fireball gets them all.", Skill.FIREBALL),
    AMBUSHER("🎯", "Ambusher", "Hides (almost no damage) and hits the card with the lowest HP for double damage – until the light of a Flash Heal reveals it.", Skill.FLASH_HEAL);

    val counterHero get() = CardType.entries.first { it.hero && it.skill == counter }
}

/**
 * Card types. Base stats are for effective level 1 (common, level 1).
 * Bosses can take several grid spots (only used by dungeon bosses, never dropped).
 */
enum class CardType(
    val title: String,
    val emoji: String,
    val hero: Boolean,
    val baseHp: Int,
    val baseAtk: Int,
    val skill: Skill,
    val bgTop: Long,
    val bgBottom: Long,
    val boss: Boolean = false,
    /** Hand-drawn picture (resources/art/<name>.svg) used instead of the emoji. */
    val art: String? = null,
    /**
     * Food card: always level 1, no skill, can't evolve or fight. Feeding it gives [Balance.FOOD_XP_FACTOR]× the XP
     * a max-level card of the same rarity and evo stage would give ([Balance.foodXp]). Bosses drop it, the shop sells it; it can't be sold.
     */
    val food: Boolean = false,
    /** Special boss mechanic (bosses from Fire Mountain on); one skill is the best answer to it. */
    val trait: BossTrait? = null,
) {
    // hero cards
    WARRIOR("Warrior", "🛡️", true, 56, 6, Skill.SHIELD_UP, 0xFFB0C4DE, 0xFF5A6E8C, art = "knight"),
    MAGE("Mage", "🧙", true, 26, 14, Skill.FIREBALL, 0xFFD6B8FF, 0xFF5B3A8C),
    ROGUE("Rogue", "🥷", true, 30, 10, Skill.SNEAK_ATTACK, 0xFFB8E0C8, 0xFF2F5E46),
    HUNTER("Hunter", "🏹", true, 28, 10, Skill.AIMED_SHOT, 0xFFD8EEB8, 0xFF4A6E2A, art = "hunter"),
    WARLOCK("Warlock", "🔮", true, 26, 12, Skill.SOUL_LEECH, 0xFF9A7AB8, 0xFF2A1A40, art = "warlock"),
    PALADIN("Paladin", "🔨", true, 50, 7, Skill.FLASH_HEAL, 0xFFFFD98A, 0xFFB0702A, art = "paladin"),
    BERSERKER("Berserker", "🪓", true, 40, 9, Skill.WHIRLWIND, 0xFFF0C8A8, 0xFF8A3A2A, art = "berserker"),
    PRIEST("Priest", "🙏", true, 35, 6, Skill.HEAL, 0xFFBFE0FF, 0xFF4A78C0, art = "priest"),

    // Whispering Woods
    SNAIL("Snail", "🐌", false, 30, 3, Skill.SHIELD_UP, 0xFFE8F5C8, 0xFF7FA650),
    BAT("Bat", "🦇", false, 14, 5, Skill.AIMED_SHOT, 0xFFC8C8E8, 0xFF50507F),
    MUSHROOM("Mushroom", "🍄", false, 22, 3, Skill.HEAL, 0xFFFFE0E0, 0xFFB05050),
    FROG("Frog", "🐸", false, 20, 5, Skill.AIMED_SHOT, 0xFFD0F0B0, 0xFF4F8F2F),
    FOX("Fox", "🦊", false, 20, 7, Skill.SNEAK_ATTACK, 0xFFFFE0C0, 0xFFC06A2A),
    WOLF("Wolf", "🐺", false, 26, 7, Skill.SNEAK_ATTACK, 0xFFE0E4EC, 0xFF606A80),
    OWL("Owl", "🦉", false, 18, 6, Skill.AIMED_SHOT, 0xFFF0E0C8, 0xFF8A6A40),
    BEAR("Bear", "🐻", false, 40, 7, Skill.WHIRLWIND, 0xFFF0D8C0, 0xFF7A5030),
    OAK("Grumpy Oak", "🌳", false, 34, 6, Skill.WHIRLWIND, 0xFFC8F0C8, 0xFF2F6F2F, boss = true),

    // Crystal Caves
    SKELETON("Skeleton", "💀", false, 22, 7, Skill.SOUL_LEECH, 0xFFF0F0F0, 0xFF8080A0),
    GHOST("Ghost", "👻", false, 20, 6, Skill.SOUL_LEECH, 0xFFE8F0FF, 0xFF7088B0),
    CRAB("Crab", "🦀", false, 34, 5, Skill.SHIELD_UP, 0xFFFFD8D0, 0xFFB04A3A),
    TURTLE("Turtle", "🐢", false, 44, 4, Skill.SHIELD_UP, 0xFFD0F0E0, 0xFF3A8A6A),
    IMP("Imp", "😈", false, 18, 7, Skill.FIREBALL, 0xFFF0D0FF, 0xFF7A3AA0),
    FAIRY("Fairy", "🧚", false, 18, 5, Skill.FLASH_HEAL, 0xFFFFE0F8, 0xFFC060B0),
    TROLL("Troll", "🧌", false, 40, 8, Skill.WHIRLWIND, 0xFFD8E8C8, 0xFF5A7040),
    GOLEM("Golem", "🗿", false, 50, 5, Skill.WHIRLWIND, 0xFFE0E0E0, 0xFF707070),
    LION("Lion", "🦁", false, 34, 8, Skill.SNEAK_ATTACK, 0xFFFFF0C0, 0xFFC09030),
    DRAGON("Crystal Dragon", "🐉", false, 44, 8, Skill.FIREBALL, 0xFFC8F8F0, 0xFF2A8A80, boss = true),

    // Whispering Woods, deeper part (stages 11-20)
    BOAR("Boar", "🐗", false, 30, 8, Skill.SNEAK_ATTACK, 0xFFE8D8C8, 0xFF6A4A30),
    HEDGEHOG("Hedgehog", "🦔", false, 32, 4, Skill.SHIELD_UP, 0xFFF0E8D8, 0xFF8A7050),
    RACCOON("Raccoon", "🦝", false, 20, 7, Skill.AIMED_SHOT, 0xFFE0E0E0, 0xFF505060),
    DEER("Deer", "🦌", false, 26, 5, Skill.FLASH_HEAL, 0xFFF8E8D0, 0xFFA07040),
    UNICORN("Unicorn", "🦄", false, 28, 6, Skill.HEAL, 0xFFFFE8FF, 0xFFB080D0),
    GIANT_SPIDER("Giant Spider", "🕷️", false, 40, 9, Skill.SOUL_LEECH, 0xFFD0D0E0, 0xFF303040, boss = true),

    // Crystal Caves, deeper part (stages 11-20)
    VAMPIRE("Vampire", "🧛", false, 26, 8, Skill.SOUL_LEECH, 0xFFF0D0D8, 0xFF701A30),
    ZOMBIE("Zombie", "🧟", false, 42, 6, Skill.WHIRLWIND, 0xFFD8E8D0, 0xFF4A6A4A),
    SCORPION("Scorpion", "🦂", false, 22, 8, Skill.SNEAK_ATTACK, 0xFFF8E0B0, 0xFF9A6A20),
    OCTOPUS("Octopus", "🐙", false, 24, 6, Skill.AIMED_SHOT, 0xFFFFD8E8, 0xFFB04A80),
    MERMAID("Mermaid", "🧜", false, 22, 6, Skill.HEAL, 0xFFD0F0FF, 0xFF2A80A0),
    KRAKEN("Cave Kraken", "🦑", false, 46, 9, Skill.WHIRLWIND, 0xFFFFD0D0, 0xFF8A2A40, boss = true),

    // Fire Mountain
    SALAMANDER("Salamander", "🦎", false, 20, 8, Skill.FIREBALL, 0xFFFFE0B0, 0xFFC04A1A),
    RHINO("Rhino", "🦏", false, 44, 7, Skill.SNEAK_ATTACK, 0xFFE0E0E8, 0xFF606070),
    OGRE("Ogre", "👹", false, 46, 8, Skill.WHIRLWIND, 0xFFFFD0C0, 0xFF9A2A1A),
    MAMMOTH("Mammoth", "🦣", false, 52, 5, Skill.SHIELD_UP, 0xFFF0E0D0, 0xFF7A5A3A),
    EAGLE("Eagle", "🦅", false, 20, 8, Skill.AIMED_SHOT, 0xFFF8F0E0, 0xFF8A6A3A),
    SNAKE("Snake", "🐍", false, 22, 8, Skill.SOUL_LEECH, 0xFFE0F8C0, 0xFF4A8A2A),
    GENIE("Genie", "🧞", false, 24, 6, Skill.HEAL, 0xFFD8E0FF, 0xFF4A5AC0),
    LAVA_REX("Lava Rex", "🦖", false, 48, 9, Skill.WHIRLWIND, 0xFFFFD8A0, 0xFFA03A1A, boss = true, trait = BossTrait.STONE_SKIN),
    LAVA_DRAGON("Lava Dragon", "🐲", false, 50, 10, Skill.FIREBALL, 0xFFFFC080, 0xFF8A1A10, boss = true, trait = BossTrait.FIRESTORM),
    HELLHOUND("Hellhound", "🐕", false, 24, 9, Skill.SNEAK_ATTACK, 0xFFFFD0B0, 0xFF8A2A1A),
    PHOENIX_CHICK("Phoenix Chick", "🐣", false, 22, 6, Skill.FLASH_HEAL, 0xFFFFF0B0, 0xFFD08020),

    // Frozen Peaks
    SEAL("Seal", "🦭", false, 44, 5, Skill.SHIELD_UP, 0xFFE0E8F0, 0xFF607080),
    MOUNTAIN_GOAT("Mountain Goat", "🐐", false, 30, 8, Skill.SNEAK_ATTACK, 0xFFF0F0E8, 0xFF8A8070),
    RAM("Ram", "🐏", false, 38, 7, Skill.WHIRLWIND, 0xFFF8F0E0, 0xFF9A8060),
    SNOW_TIGER("Snow Tiger", "🐅", false, 28, 9, Skill.SNEAK_ATTACK, 0xFFF8F8FF, 0xFF7080A0),
    ICE_GOLEM("Ice Golem", "🧊", false, 50, 5, Skill.WHIRLWIND, 0xFFE0F8FF, 0xFF5090C0),
    SNOWMAN("Snowman", "⛄", false, 40, 6, Skill.SHIELD_UP, 0xFFFFFFFF, 0xFF90A8C0),
    PENGUIN("Penguin", "🐧", false, 20, 7, Skill.AIMED_SHOT, 0xFFE8F0F8, 0xFF303848),
    FROST_SPRITE("Frost Sprite", "❄️", false, 18, 8, Skill.FIREBALL, 0xFFE0F0FF, 0xFF4A7AC0),
    SWAN("Swan", "🦢", false, 24, 5, Skill.FLASH_HEAL, 0xFFFFFFFF, 0xFFA0B0C0),
    SNOW_DOVE("Snow Dove", "🕊️", false, 22, 5, Skill.HEAL, 0xFFF8F8FF, 0xFF8090B0),
    YETI("Yeti", "🦍", false, 50, 9, Skill.WHIRLWIND, 0xFFF0F0F8, 0xFF5A6A8A, boss = true, trait = BossTrait.CRUSHER),
    FROST_GIANT("Frost Giant", "☃️", false, 52, 9, Skill.FIREBALL, 0xFFE8F8FF, 0xFF2A5A9A, boss = true, trait = BossTrait.COMMANDER),

    // Scorching Desert
    CAMEL("Camel", "🐪", false, 42, 7, Skill.WHIRLWIND, 0xFFF8E8C8, 0xFFB08040),
    CACTUS("Cactus", "🌵", false, 46, 5, Skill.SHIELD_UP, 0xFFD8F0C0, 0xFF3A7A3A),
    LEOPARD("Leopard", "🐆", false, 26, 9, Skill.SNEAK_ATTACK, 0xFFFFF0C8, 0xFFB08030),
    GIANT_ANT("Giant Ant", "🐜", false, 32, 8, Skill.WHIRLWIND, 0xFFF0D8D0, 0xFF6A3020),
    SCARAB("Scarab", "🪲", false, 34, 7, Skill.SOUL_LEECH, 0xFFD8E8F0, 0xFF2A4A6A),
    WASP("Wasp", "🐝", false, 18, 8, Skill.AIMED_SHOT, 0xFFFFF0A0, 0xFFA08010),
    SUN_SPRITE("Sun Sprite", "🌞", false, 20, 8, Skill.FIREBALL, 0xFFFFF8C0, 0xFFE0A020),
    PEACOCK("Peacock", "🦚", false, 24, 6, Skill.HEAL, 0xFFD0F0E8, 0xFF208070),
    DESERT_ROSE("Desert Rose", "🌹", false, 22, 5, Skill.FLASH_HEAL, 0xFFFFD8D8, 0xFFA02030),
    SAND_CROC("Sand Crocodile", "🐊", false, 50, 9, Skill.SNEAK_ATTACK, 0xFFE0F0C0, 0xFF5A7A2A, boss = true, trait = BossTrait.AMBUSHER),
    DESERT_WYRM("Desert Wyrm", "🦕", false, 54, 10, Skill.WHIRLWIND, 0xFFF0E0B0, 0xFF8A6A2A, boss = true, trait = BossTrait.SWARM),

    // Sunken Reef
    SHARK("Shark", "🦈", false, 30, 9, Skill.SNEAK_ATTACK, 0xFFD8E8F8, 0xFF40607A),
    LOBSTER("Lobster", "🦞", false, 38, 7, Skill.WHIRLWIND, 0xFFFFD0C0, 0xFFC03A20),
    HERMIT_CRAB("Hermit Crab", "🐚", false, 46, 5, Skill.SHIELD_UP, 0xFFFFF0E0, 0xFFC09070),
    ANGLERFISH("Anglerfish", "🐟", false, 28, 8, Skill.SOUL_LEECH, 0xFFD0E0F0, 0xFF203A5A),
    SHRIMP("Shrimp", "🦐", false, 18, 8, Skill.AIMED_SHOT, 0xFFFFE0D8, 0xFFE06A50),
    CLOWNFISH("Clownfish", "🐠", false, 20, 7, Skill.AIMED_SHOT, 0xFFFFE8C0, 0xFFE07A20),
    WATER_SPRITE("Water Sprite", "💧", false, 20, 8, Skill.FIREBALL, 0xFFD8F0FF, 0xFF2A80D0),
    DOLPHIN("Dolphin", "🐬", false, 26, 5, Skill.FLASH_HEAL, 0xFFD8F0FF, 0xFF3A7AB0),
    OTTER("Otter", "🦦", false, 22, 5, Skill.HEAL, 0xFFF0E0D0, 0xFF7A5A3A),
    PUFFERFISH("Giant Pufferfish", "🐡", false, 52, 9, Skill.WHIRLWIND, 0xFFFFF8D0, 0xFFB09030, boss = true, trait = BossTrait.STONE_SKIN),
    LEVIATHAN("Leviathan", "🐋", false, 56, 10, Skill.FIREBALL, 0xFFC8E8FF, 0xFF1A4A8A, boss = true, trait = BossTrait.REGENERATION),

    // Shadow Realm
    PUMPKIN_HEAD("Pumpkin Head", "🎃", false, 40, 7, Skill.WHIRLWIND, 0xFFFFE0B0, 0xFFD06010),
    SHADOW_CAT("Shadow Cat", "🐈", false, 24, 9, Skill.SNEAK_ATTACK, 0xFFE0D8F0, 0xFF3A2A5A),
    GOBLIN("Goblin", "👺", false, 28, 8, Skill.SNEAK_ATTACK, 0xFFFFD0D0, 0xFF8A1A1A),
    GRAVESTONE("Gravestone", "🪦", false, 50, 4, Skill.SHIELD_UP, 0xFFE0E0E0, 0xFF505060),
    PLAGUE_RAT("Plague Rat", "🐀", false, 22, 8, Skill.SOUL_LEECH, 0xFFE0E0D0, 0xFF5A5A40),
    WATCHER("Watcher", "👁️", false, 18, 8, Skill.AIMED_SHOT, 0xFFF0E8FF, 0xFF5A3A8A),
    WISP("Will-o'-Wisp", "🕯️", false, 20, 5, Skill.HEAL, 0xFFFFF8D0, 0xFF8A7A3A),
    DARK_ELF("Dark Elf", "🧝", false, 22, 8, Skill.AIMED_SHOT, 0xFFE0D0F0, 0xFF3A1A5A),
    VOID_SPAWN("Void Spawn", "👾", false, 20, 8, Skill.FIREBALL, 0xFFE8D0FF, 0xFF4A1A7A),
    LICH("Lich", "☠️", false, 50, 10, Skill.SOUL_LEECH, 0xFFE8E8F0, 0xFF2A2A3A, boss = true, trait = BossTrait.UNDYING),
    VOID_LORD("Void Lord", "👽", false, 54, 10, Skill.FIREBALL, 0xFFD0FFD8, 0xFF1A4A2A, boss = true, trait = BossTrait.SWARM),

    // Sky Citadel
    CLOCKWORK_KNIGHT("Clockwork Knight", "🤖", false, 50, 5, Skill.SHIELD_UP, 0xFFE0E8F0, 0xFF6A7A8A),
    SKY_KNIGHT("Sky Knight", "🦸", false, 40, 8, Skill.WHIRLWIND, 0xFFD8E8FF, 0xFF3A5AA0),
    PEGASUS("Pegasus", "🐎", false, 30, 9, Skill.SNEAK_ATTACK, 0xFFF8F0FF, 0xFF8A7AB0),
    STORM_CLOUD("Storm Cloud", "🌩️", false, 36, 7, Skill.WHIRLWIND, 0xFFE0E0F0, 0xFF4A4A6A),
    STORM_SPRITE("Storm Sprite", "⚡", false, 20, 8, Skill.FIREBALL, 0xFFFFF8C0, 0xFFC0A020),
    COMET("Comet", "☄️", false, 18, 9, Skill.AIMED_SHOT, 0xFFFFE8D0, 0xFFC05A20),
    THUNDERBIRD("Thunderbird", "🦜", false, 22, 8, Skill.AIMED_SHOT, 0xFFD0F8D8, 0xFF2A8A4A),
    CHERUB("Cherub", "👼", false, 22, 5, Skill.HEAL, 0xFFFFF0F0, 0xFFD0A060),
    STAR_SPIRIT("Star Spirit", "🌟", false, 24, 5, Skill.FLASH_HEAL, 0xFFFFFAD0, 0xFFD0B030),
    SKY_WHALE("Sky Whale", "🐳", false, 58, 9, Skill.WHIRLWIND, 0xFFD8F0FF, 0xFF3A7AC0, boss = true, trait = BossTrait.CRUSHER),
    ELDER_DRAGON("Elder Dragon", "🐉", false, 56, 11, Skill.FIREBALL, 0xFFFFE8B0, 0xFFB08A20, boss = true, trait = BossTrait.COMMANDER),

    // Candy Kingdom
    GUMMY_BEAR("Gummy Bear", "🧸", false, 42, 8, Skill.WHIRLWIND, 0xFFFFE0C8, 0xFFC06A3A),
    GINGERBREAD("Gingerbread Man", "🍪", false, 46, 6, Skill.SHIELD_UP, 0xFFF8E0C0, 0xFF9A5A2A),
    CHOCO_GOLEM("Chocolate Golem", "🍫", false, 52, 6, Skill.WHIRLWIND, 0xFFE8D0C0, 0xFF5A3020),
    CANDY_KNIGHT("Candy Knight", "🍬", false, 32, 9, Skill.SNEAK_ATTACK, 0xFFFFE0F0, 0xFFC04A8A),
    PUDDING("Pudding", "🍮", false, 34, 8, Skill.SOUL_LEECH, 0xFFFFF0C8, 0xFFB08030),
    DONUT("Donut", "🍩", false, 20, 8, Skill.AIMED_SHOT, 0xFFFFE8D8, 0xFFC07A50),
    LOLLIPOP("Lollipop", "🍭", false, 20, 9, Skill.FIREBALL, 0xFFFFE0FF, 0xFFB03AA0),
    ICE_CREAM("Ice Cream", "🍦", false, 22, 5, Skill.HEAL, 0xFFFFF8F0, 0xFFC0A080),
    CUPCAKE("Cupcake", "🧁", false, 24, 5, Skill.FLASH_HEAL, 0xFFFFE8F0, 0xFFD06A8A),
    STRAWBERRY("Strawberry", "🍓", false, 18, 9, Skill.AIMED_SHOT, 0xFFFFE0E0, 0xFFC02030),
    HONEY_MONSTER("Honey Monster", "🍯", false, 58, 10, Skill.SOUL_LEECH, 0xFFFFF0B0, 0xFFC09020, boss = true, trait = BossTrait.REGENERATION),
    SUGAR_KING("Sugar King", "🤴", false, 58, 11, Skill.FIREBALL, 0xFFFFE8F8, 0xFFA03A8A, boss = true, trait = BossTrait.UNDYING),

    // Cosmic Rift
    PLANET_TITAN("Planet Titan", "🪐", false, 54, 6, Skill.SHIELD_UP, 0xFFF0E0C8, 0xFF8A6A3A),
    ROCKET("Rocket", "🚀", false, 30, 10, Skill.SNEAK_ATTACK, 0xFFE8E8F8, 0xFF5A5A8A),
    MOON_BEAST("Moon Beast", "🌕", false, 46, 8, Skill.WHIRLWIND, 0xFFFFF8D8, 0xFF8A8050),
    SATELLITE("Satellite", "🛰️", false, 40, 7, Skill.SOUL_LEECH, 0xFFE0E8F0, 0xFF4A5A6A),
    METEOR("Meteor", "🌠", false, 36, 9, Skill.WHIRLWIND, 0xFFE8E0FF, 0xFF3A2A7A),
    UFO("UFO", "🛸", false, 20, 9, Skill.AIMED_SHOT, 0xFFD8F8E0, 0xFF2A7A4A),
    NEBULA_SPRITE("Nebula Sprite", "🌌", false, 20, 9, Skill.FIREBALL, 0xFFE0D8FF, 0xFF2A1A6A),
    MOON_RABBIT("Moon Rabbit", "🐇", false, 24, 5, Skill.FLASH_HEAL, 0xFFF8F8FF, 0xFF8A8AA0),
    CRESCENT_SPIRIT("Crescent Spirit", "🌙", false, 22, 5, Skill.HEAL, 0xFFFFF8D0, 0xFFA09040),
    BLACK_HOLE("Black Hole", "🕳️", false, 58, 10, Skill.SOUL_LEECH, 0xFFD0D0E0, 0xFF101020, boss = true, trait = BossTrait.AMBUSHER),
    SUN_TITAN("Sun Titan", "☀️", false, 60, 11, Skill.FIREBALL, 0xFFFFF0B0, 0xFFE07A10, boss = true, trait = BossTrait.FIRESTORM),

    // food (its skill is never used)
    FOOD("Yummy Cake", "🎂", false, 10, 1, Skill.HEAL, 0xFFFFE8F0, 0xFFD06A8A, art = "cake", food = true);

    /** Bosses get extra hp per occupied spot. */
    fun bossHpFactor(slots: Int) = if (boss) Companion.bossHpFactor(slots) else 1.0

    companion object {
        fun bossHpFactor(slots: Int) = 1.6 * slots
        val starters = listOf(WARRIOR, MAGE, ROGUE)
        val heroes get() = entries.filter { it.hero }
    }
}

object Balance {
    const val MAX_PARTY = 5
    /** Rounds to wait after using a skill (meditation can shorten it for hero cards, see [cooldownCutCost]). */
    const val SKILL_COOLDOWN = 4
    /** true = skills are ready again at the start of every wave; false = cooldowns carry over between waves. */
    const val RESET_COOLDOWN_EACH_WAVE = false
    const val MAX_SKILL_LEVEL = 5
    /** Skill power bonus per skill level above 1. */
    const val SKILL_LEVEL_BONUS = 0.15
    /** Max-level Fireball: burning deals this share of the fireball's damage every turn. */
    const val BURN_SHARE = 0.5
    /** Max-level Sneak Attack: poison deals this share of the attack's damage every turn. */
    const val POISON_SHARE = 0.4
    /** Chance that a fed card with the same skill raises the skill level, by rarity (common = 0). Chances add up. */
    val SKILL_UP_CHANCE = listOf(0.0, 0.10, 0.20, 0.30, 0.40) // was 10/30/50/70 %
    const val LEVELS_PER_RARITY = 5.0
    const val LEVELS_PER_EVO = 7.5
    const val STAT_GROWTH = 1.10
    const val CARD_XP_GROWTH = 1.15
    const val PLAYER_XP_GROWTH = 1.35
    const val HERO_XP_FACTOR = 2.0
    const val MAX_ROUNDS_PER_WAVE = 60

    fun effectiveLevel(level: Int, rarity: Rarity, evo: Int): Double =
        level + LEVELS_PER_RARITY * rarity.ordinal + LEVELS_PER_EVO * evo

    /**
     * Extra effective levels of a legendary hero card: without it a maxed legendary (45 + 20 = 65) would be weaker than the
     * maxed purple+++ it was evolved from (40 + 15 + 22.5 = 77.5). Monsters don't get it.
     */
    const val LEGENDARY_HERO_BONUS = 15.0

    /** [effectiveLevel] plus [LEGENDARY_HERO_BONUS] for legendary heroes: the level the card's stats are based on. */
    fun cardEffectiveLevel(type: CardType, level: Int, rarity: Rarity, evo: Int): Double =
        effectiveLevel(level, rarity, evo) + if (type.hero && rarity == Rarity.LEGENDARY) LEGENDARY_HERO_BONUS else 0.0

    fun statMultiplier(type: CardType, level: Int, rarity: Rarity, evo: Int): Double =
        STAT_GROWTH.pow(cardEffectiveLevel(type, level, rarity, evo) - 1)

    fun hp(type: CardType, level: Int, rarity: Rarity, evo: Int, slots: Int = 1): Int =
        (type.baseHp * statMultiplier(type, level, rarity, evo) * type.bossHpFactor(slots)).roundToInt()

    fun atk(type: CardType, level: Int, rarity: Rarity, evo: Int): Int =
        (type.baseAtk * statMultiplier(type, level, rarity, evo)).roundToInt()

    /** XP needed to go from [level] to level+1. Hero cards level up slower. */
    fun cardXpToNext(level: Int, hero: Boolean): Long =
        (100 * CARD_XP_GROWTH.pow(level - 1) * (if (hero) HERO_XP_FACTOR else 1.0)).roundToLong()

    /** Total XP needed to get from level 1 to [level]. */
    fun cardXpTotal(level: Int, hero: Boolean): Long = (1 until level).sumOf { cardXpToNext(it, hero) }

    fun playerXpToNext(level: Int): Long = (100 * PLAYER_XP_GROWTH.pow(level - 1)).roundToLong()

    /**
     * XP for eating a card of [level], [rarity] and [evo]: [SACRIFICE_XP_BASE] × [SACRIFICE_XP_GROWTH]^[effectiveLevel] up to the
     * rarity's max level; every level above it (a looted white level 45) adds only ×[SACRIFICE_XP_GROWTH_OVER_MAX].
     * No legendary hero bonus. Food cards: see [foodXp].
     */
    fun sacrificeValue(level: Int, rarity: Rarity, evo: Int): Long {
        val capped = level.coerceAtMost(rarity.maxLevel)
        return (SACRIFICE_XP_BASE * SACRIFICE_XP_GROWTH.pow(effectiveLevel(capped, rarity, evo)) *
            SACRIFICE_XP_GROWTH_OVER_MAX.pow(level - capped)).roundToLong()
    }
    const val SACRIFICE_XP_BASE = 500.0
    const val SACRIFICE_XP_GROWTH = 1.08
    const val SACRIFICE_XP_GROWTH_OVER_MAX = 1.06

    /**
     * XP per gold of everything that turns into XP: selling a card pays its [sacrificeValue] / this, a cake in the shop costs
     * its XP / this. 9.98 = the old white cake (4990 XP for 500 gold).
     */
    const val XP_PER_GOLD = 9.98

    // ---------------------------------------------------------------- food cards

    /** Feeding a food card gives this many times the XP a max-level card of the same rarity/evo would give. */
    const val FOOD_XP_FACTOR = 1.5
    /** Feeding a card with the same skill as the eater gives this much extra XP (0.2 = +20 %). */
    const val SAME_SKILL_XP_BONUS = 0.2
    /** Chance that a beaten boss drops a food card of its own rarity and evo stage (it still pays its gold). */
    const val FOOD_DROP_CHANCE = 0.2
    /** XP of a food card of [rarity] and [evo] (always level 1). */
    fun foodXp(rarity: Rarity, evo: Int = 0): Long = (sacrificeValue(rarity.maxLevel, rarity, evo) * FOOD_XP_FACTOR).roundToLong()

    /** Shop price of a food card (no evo): its XP / [XP_PER_GOLD], the same XP per gold as selling cards. */
    fun foodPrice(rarity: Rarity): Long = (foodXp(rarity) / XP_PER_GOLD).roundToLong()

    /**
     * Position of a rarity + evolution stage on the ladder white, green, green+, blue, blue+, blue++, purple, purple+, …,
     * legendary, …, legendary++++ (0..14). Dungeon d shows ladder steps d-1 (stages 1-5), d (6-15) and d+1 (16-20).
     */
    fun ladderStep(rarity: Rarity, evo: Int) = rarity.ordinal * (rarity.ordinal + 1) / 2 + evo

    /** Gold cost of evolving: grows ×2 per ladder step of the result (like the entry fees, which grow ×2 per 10 stages). */
    fun evolveGoldCost(rarity: Rarity, evo: Int): Long = (EVOLVE_GOLD_BASE * 2.0.pow(ladderStep(rarity, evo))).roundToLong()
    const val EVOLVE_GOLD_BASE = 50.0

    /** Number of same-type, same-rarity cards consumed by evolution = one per current evolution stage (+1). */
    fun evolveMaterials(evo: Int): Int = evo + 1

    /** Buying hero cards (Cards tab → "Buy cards"): the first costs [HERO_PRICE_START], each next one [HERO_PRICE_STEP] more, up to [HERO_PRICE_MAX]. */
    // Trial: flat 500 per card (was 100 + 50 per bought card, max 1000).
    const val HERO_PRICE_START = 500L
    const val HERO_PRICE_STEP = 0L
    const val HERO_PRICE_MAX = 500L
    /** Rarity of bought hero cards. */
    val HERO_BUY_RARITY = Rarity.UNCOMMON
    /**
     * Price of a level 1 hero card of a higher rarity. It shows up in the shop once the player owns that hero at that rarity
     * (i.e. evolved it that far, [GameState.heroShopRarities]).
     */
    val HERO_PRICE_BY_RARITY: Map<Rarity, Long> = mapOf(Rarity.RARE to 10_000L, Rarity.EPIC to 100_000L, Rarity.LEGENDARY to 1_000_000L)

    /** Card slots at the start; each upgrade adds [INVENTORY_STEP] more. */
    const val INVENTORY_START = 20
    const val INVENTORY_STEP = 10
    /** The first upgrade costs [INVENTORY_UPGRADE_BASE] gold, each next one twice as much as the one before. */
    const val INVENTORY_UPGRADE_BASE = 1000L

    fun inventorySize(upgrades: Int): Int = INVENTORY_START + INVENTORY_STEP * upgrades
    /** Price of the next inventory upgrade when [upgrades] were bought before (1000, 2000, 4000, …). */
    fun inventoryUpgradeCost(upgrades: Int): Long = INVENTORY_UPGRADE_BASE shl upgrades.coerceAtMost(40)

    /** Price of the next hero card when [bought] cards were bought before. */
    fun heroPrice(bought: Int): Long = minOf(HERO_PRICE_START + HERO_PRICE_STEP * bought, HERO_PRICE_MAX)

    /** Price of a hero card of [rarity]: the usual [heroPrice] for [HERO_BUY_RARITY], [HERO_PRICE_BY_RARITY] above it. */
    fun heroPrice(rarity: Rarity, bought: Int): Long =
        if (rarity == HERO_BUY_RARITY) heroPrice(bought) else HERO_PRICE_BY_RARITY[rarity] ?: error("${rarity.label} heroes aren't sold")

    /**
     * Gold for beating the monsters of a stage: all of them together pay [STAGE_GOLD_FEES]× the entry fee, split evenly
     * (a monster that drops its card pays nothing); a boss pays one more entry fee on top (and always pays, see [StageDef]).
     */
    const val STAGE_GOLD_FEES = 3.0

    /**
     * Level of a looted card: [LOOT_LEVEL_SHARE] of the monster's level (rounded down), at least 1 and not above the player's level.
     * It may be above the rarity's max level (mixed-in monsters, see MIXED_RARITIES in content/Dungeons.kt): such a card keeps its
     * level and stats, can't level up, feeds and sells at its real level (above the max growing slower, see [sacrificeValue]) and can evolve (back to level 1) like one.
     */
    fun lootLevel(monsterLevel: Int, rarity: Rarity, playerLevel: Int): Int =
        (monsterLevel * LOOT_LEVEL_SHARE).toInt().coerceAtMost(playerLevel).coerceAtLeast(1)

    /** Selling a card pays its [sacrificeValue] / [XP_PER_GOLD] (levels above the rarity's max count, but less, see [sacrificeValue]). */
    fun sellPrice(rarity: Rarity, evo: Int, level: Int): Long =
        (sacrificeValue(level, rarity, evo) / XP_PER_GOLD).roundToLong().coerceAtLeast(1)
    /** A dropped card joins at this share of the monster's level. */
    const val LOOT_LEVEL_SHARE = 1.0

    // ---------------------------------------------------------------- meditation

    /** Hero cards of this rarity or better meditate (and unlock the Meditation tab) and can get their cooldown shortened. */
    val MEDITATION_RARITY = Rarity.RARE

    /**
     * Clues a meditating hero finds every night (of its own class): rare 1/2/3, purple 7/10/13/16, legendary 25/30/35/40/45.
     * 0 below [MEDITATION_RARITY].
     */
    fun cluesPerDay(rarity: Rarity, evo: Int): Long = when (rarity) {
        Rarity.RARE -> 1L + evo
        Rarity.EPIC -> 7L + 3 * evo
        Rarity.LEGENDARY -> 25L + 5 * evo
        else -> 0L
    }

    /** Cooldown cuts a card can get: down to cooldown 0 (the skill can be used every turn). */
    const val MAX_COOLDOWN_CUTS = SKILL_COOLDOWN

    /** Clues of EACH hero class the next cooldown cut costs when the card has [cuts] already: 1, 10, 100, 1000. */
    fun cooldownCutCost(cuts: Int): Long = 10.0.pow(cuts).roundToLong()

    // ---------------------------------------------------------------- fight XP

    /** Player level the XP rewards aim at, by difficulty tier (piecewise linear between these points). */
    private val PLAYER_LEVEL_PLAN = listOf(0 to 1.0, 10 to 11.0, 20 to 22.0, 30 to 30.0, 40 to 35.0, 55 to 40.0, 70 to 45.0, 90 to 50.0)
    /** Roughly how many fights a player spends per tier (first clears plus some replays). */
    const val FIGHTS_PER_TIER = 2.5
    /** Card XP per fight = this share of a level at the planned player level. */
    const val CARD_XP_SHARE = 0.5

    fun plannedPlayerLevel(tier: Double): Double {
        val (t0, l0) = PLAYER_LEVEL_PLAN.last { it.first <= tier.coerceAtLeast(0.0) }
        val (t1, l1) = PLAYER_LEVEL_PLAN.firstOrNull { it.first > tier } ?: return l0
        return l0 + (l1 - l0) * (tier - t0) / (t1 - t0)
    }

    /**
     * The player levels this many times faster than [PLAYER_LEVEL_PLAN] (which roughly follows the monsters), so
     * the player is ~35 % above the monsters' level. It scales the target *level*, not the XP: with XP growing
     * ×[PLAYER_XP_GROWTH] per level, an XP bonus only buys a fixed number of levels (×1.5 XP ≈ +1.35 levels).
     */
    const val PLAYER_LEVEL_FACTOR = 1.35

    /** Player level the player XP rewards aim at: [PLAYER_LEVEL_FACTOR] × [plannedPlayerLevel]. */
    fun playerTargetLevel(tier: Double): Double = PLAYER_LEVEL_FACTOR * plannedPlayerLevel(tier)

    /** Player XP for a won fight: enough that ~[FIGHTS_PER_TIER] fights keep the player on [playerTargetLevel]. */
    fun playerXpReward(tier: Int): Long {
        val p = playerTargetLevel(tier.toDouble())
        val levelsPerTier = playerTargetLevel(tier + 1.0) - p
        return (100 * PLAYER_XP_GROWTH.pow(p - 1) * levelsPerTier / FIGHTS_PER_TIER).roundToLong()
    }

    /** Card XP for a won fight (every party card): [CARD_XP_SHARE] of a level at the planned player level. */
    fun cardXpReward(tier: Int): Long {
        val p = minOf(plannedPlayerLevel(tier.toDouble()), Rarity.LEGENDARY.maxLevel.toDouble())
        return (100 * CARD_XP_GROWTH.pow(p - 1) * CARD_XP_SHARE).roundToLong()
    }
}

@Serializable
data class OwnedCard(
    val id: Long,
    val type: CardType,
    val rarity: Rarity,
    val evo: Int = 0,
    val level: Int = 1,
    /** XP collected towards the next level. */
    val xp: Long = 0,
    val skillLevel: Int = 1,
    /** How many times meditation clues shortened the skill cooldown (0..[Balance.MAX_COOLDOWN_CUTS]); shown as lotus flowers. */
    val cooldownCuts: Int = 0,
    /** Level of the hero's passive ability (0 = none yet), bought with diamonds in the Contest tab. */
    val passiveLevel: Int = 0,
) {
    /** The hero's passive ability, if it has bought one. */
    val passive: Passive? get() = if (passiveLevel > 0) Passive.of(type) else null
    /** Rounds to wait after using the skill. */
    val skillCooldown get() = Balance.SKILL_COOLDOWN - cooldownCuts
    /** Rank among cards of the same type: rarity, then evolution stage, then level (the best one meditates). */
    val rank get() = (rarity.ordinal * 10 + evo) * 100 + level
    /** HP and attack, including the passive's bonus ([Passive.BLESSING], [Passive.ARCANE_POWER]). */
    val hp get() = (Balance.hp(type, level, rarity, evo) * (1 + (passive?.hpBonus(passiveLevel) ?: 0.0))).roundToInt()
    val atk get() = (Balance.atk(type, level, rarity, evo) * (1 + (passive?.atkBonus(passiveLevel) ?: 0.0))).roundToInt()
    val hasSkill get() = rarity != Rarity.COMMON && !type.food
    val skillPower get() = type.skill.power(rarity, skillLevel)
    val xpToNext get() = Balance.cardXpToNext(level, type.hero)
    val atRarityMaxLevel get() = level >= rarity.maxLevel
    val atMaxEvo get() = evo >= rarity.maxEvo
    /** True if the card is at the very top (no evolution or rarity-up possible any more). */
    val fullyUpgraded get() = type.food || (atRarityMaxLevel && atMaxEvo && !(type.hero && rarity.next != null))
    val power get() = hp.toDouble() * atk
    /** XP held at this rarity/evo stage: the XP of its level plus the XP towards the next one (used to show what feeding adds). */
    val totalXp: Long get() = Balance.cardXpTotal(level, type.hero) + xp
    /** XP for eating this card: depends only on its level, rarity and evo stage ([Balance.sacrificeValue]), not on how it got there. */
    val sacrificeValue: Long get() =
        if (type.food) Balance.foodXp(rarity, evo) else Balance.sacrificeValue(level, rarity, evo)
    /** Gold for selling it (0 for food cards, which can't be sold). */
    val sellPrice: Long get() = if (type.food) 0 else Balance.sellPrice(rarity, evo, level)
    val stageLabel get() = "${rarity.label}${"+".repeat(evo)}"
    val fullName get() = "$stageLabel ${type.title}"
}
