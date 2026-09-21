package karasu.domain.recommendation

/**
 * One name per genre, whatever a source called it.
 *
 * Sources tag in their own language: a library read across Portuguese and English sources
 * splits "Ação" and "Action" into two half-weight tags, and a Portuguese profile tag never
 * matches an English source's genre filter. Everything the recommender compares goes through
 * here first. A tag the table does not know keeps its own spelling, so nothing is lost.
 *
 * ponytail: a hand list of the genres that actually recur, in en/pt/es. Grow it when a tag shows
 * up split on the tags screen.
 */
fun canonicalTag(tag: String): String {
    val trimmed = tag.trim()
    var name = SYNONYMS[trimmed.fold()] ?: trimmed
    // A group merged into another group: follow the chain, but never forever.
    repeat(MAX_MERGE_HOPS) { name = TagMerges.user[name.lowercase()] ?: return name }
    return name
}

private const val MAX_MERGE_HOPS = 4

/**
 * The reader's own merges — "Reincarnation", "Regression" and "Isekai" are one thing to one
 * reader and three to another — applied after the table, so a merge is spelled in canonical
 * names. Keyed by lowercase canonical name; the value is the name the group goes by.
 *
 * ponytail: a global, set by the app from its preference. Threading a map through every call
 * that normalises a tag would touch the profile, the ranker and the filter matcher for a value
 * that changes a few times a year.
 */
object TagMerges {
    @Volatile
    var user: Map<String, String> = emptyMap()
}

/**
 * A tag worth learning from, or null for the things sources stuff into the genre list that are
 * not tags: "Classificação: Sugestivo", "Serialização: Shonen Jump", the scanlation group's name,
 * a year. A `key: value` pair is kept only when the key says it holds a genre or a demographic,
 * and then only the value survives.
 */
fun cleanTag(tag: String): String? {
    val trimmed = tag.trim()
    if (trimmed.isBlank() || trimmed.length > MAX_TAG_LENGTH) return null
    val colon = trimmed.indexOfFirst { it == ':' || it == '：' }
    if (colon >= 0) {
        val key = trimmed.substring(0, colon).fold()
        val value = trimmed.substring(colon + 1).trim()
        return if (key in TAG_KEYS && value.isNotBlank()) cleanTag(value) else null
    }
    if (trimmed.all { !it.isLetter() }) return null
    if (JUNK.containsMatchIn(trimmed)) return null
    return trimmed
}

/** `cleanTag` then `canonicalTag`: what every tag goes through before it is compared. */
fun normalizeTag(tag: String): String? = cleanTag(tag)?.let(::canonicalTag)

private const val MAX_TAG_LENGTH = 40

/** `key: value` keys whose value is a genre or demographic rather than metadata. */
private val TAG_KEYS = setOf(
    "genero", "generos", "genre", "genres", "gender",
    "tema", "temas", "theme", "themes",
    "tag", "tags", "categoria", "categorias", "category", "categories",
    "demografia", "demographic", "demographics", "publico",
)

/** Words that mark a scanlation credit or a release note rather than a genre. */
private val JUNK = Regex("""(?i)\b(scans?|scanlat\w*|fansub\w*|tradu[cç][aã]o|traducoes|traduções|translat\w*|subs?|team|projeto|project|release|lan[cç]amento|capitulo|cap[ií]tulos?|chapter|vol\.?|volume|hentai\s*id|id)\b""")

/** Lowercase, no accents, single spaces: the key the table is looked up by. */
private fun String.fold(): String = lowercase()
    .map { ACCENTS[it] ?: it }
    .joinToString("")
    .replace(Regex("[\\s_]+"), " ")
    .trim()

private val ACCENTS = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a',
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
    'ç' to 'c', 'ñ' to 'n',
)

private val SYNONYMS: Map<String, String> = listOf(
    "Action" to listOf("acao", "accion"),
    "Adventure" to listOf("aventura"),
    "Comedy" to listOf("comedia"),
    "Drama" to listOf(),
    "Fantasy" to listOf("fantasia"),
    "Horror" to listOf("terror"),
    "Mystery" to listOf("misterio"),
    "Romance" to listOf("romantico", "romantica"),
    "Sci-Fi" to listOf("scifi", "science fiction", "ficcao cientifica", "ciencia ficcion"),
    "Slice of Life" to listOf("cotidiano", "vida cotidiana", "recuentos de la vida", "sliceoflife"),
    "Sports" to listOf("sport", "esporte", "esportes", "deportes", "deporte"),
    "Supernatural" to listOf("sobrenatural"),
    "Thriller" to listOf("suspense", "suspenso"),
    "Psychological" to listOf("psicologico", "psicologica"),
    "Historical" to listOf("historico", "historica", "history", "historia"),
    "School Life" to listOf("school", "escolar", "vida escolar", "escola"),
    "Martial Arts" to listOf("artes marciais", "artes marciales"),
    "Mecha" to listOf("robos", "robots"),
    "Music" to listOf("musica"),
    "Isekai" to listOf("outro mundo"),
    "Harem" to listOf("harem"),
    "Reverse Harem" to listOf("harem reverso", "harem inverso"),
    "Ecchi" to listOf(),
    "Shounen" to listOf("shonen"),
    "Shoujo" to listOf("shojo"),
    "Seinen" to listOf(),
    "Josei" to listOf(),
    "Yaoi" to listOf("boys love", "bl"),
    "Yuri" to listOf("girls love", "gl"),
    "Shounen Ai" to listOf("shonen ai"),
    "Shoujo Ai" to listOf("shojo ai"),
    "Tragedy" to listOf("tragedia"),
    "Gore" to listOf(),
    "Magic" to listOf("magia"),
    "Military" to listOf("militar"),
    "Cooking" to listOf("culinaria", "cocina", "gourmet", "comida", "food"),
    "Medical" to listOf("medico", "medicina"),
    "Crime" to listOf("policial", "crimen"),
    "Reincarnation" to listOf("reencarnacao", "reencarnacion"),
    "Monsters" to listOf("monstros", "monstruos", "monster"),
    "Demons" to listOf("demonios", "demon"),
    "Vampires" to listOf("vampiros", "vampire", "vampiro"),
    "Zombies" to listOf("zumbis", "zombie"),
    "Survival" to listOf("sobrevivencia", "supervivencia"),
    "Game" to listOf("jogo", "jogos", "juego", "juegos", "games"),
    "Video Games" to listOf("videogame", "videogames", "videojuegos"),
    "Superhero" to listOf("super-heroi", "super heroi", "superheroe", "superheroes"),
    "Adult" to listOf("adulto", "adultos"),
    "Mature" to listOf("maduro"),
    "Smut" to listOf(),
    "Gender Bender" to listOf("troca de genero", "genderswap"),
    "Doujinshi" to listOf(),
    "One Shot" to listOf("one-shot", "oneshot"),
    "Cultivation" to listOf("cultivo"),
    "Murim" to listOf("murim", "wuxia"),
    "Regression" to listOf("regressao", "regresion"),
    "Revenge" to listOf("vinganca", "venganza"),
    "Villainess" to listOf("vila", "villana"),
    "Time Travel" to listOf("viagem no tempo", "viaje en el tiempo"),
    "Post-Apocalyptic" to listOf("pos-apocaliptico", "pos apocaliptico", "postapocaliptico", "apocalypse", "apocalipse"),
    "Dungeons" to listOf("dungeon", "masmorras", "mazmorras"),
    "Office Workers" to listOf("office", "escritorio", "oficina"),
    "Adaptation" to listOf("adaptacao", "adaptacion", "adaptado", "adaptada"),
    "Superpowers" to listOf("super poderes", "superpoderes", "super-poderes", "super powers", "super-powers", "poderes"),
    "Mafia" to listOf("mafia", "yakuza"),
    "Aliens" to listOf("alienigenas", "alien", "extraterrestres"),
    "Found Family" to listOf("familia encontrada", "familia adotiva"),
    "Medieval" to listOf("medieval", "idade media", "edad media"),
    "Virtual Reality" to listOf("realidade virtual", "realidad virtual", "vr", "vrmmo"),
    "System" to listOf("sistema", "leveling", "level up", "level system", "sistema de niveis", "sistema de niveles"),
    "Pornographic" to listOf("pornografico", "pornografia", "porn", "18+", "+18", "nsfw", "erotico", "erotica", "erotic"),
    "Overpowered" to listOf("op", "overpowered", "protagonista op", "protagonista forte", "mc op", "apelao", "apelão"),
    "Weak to Strong" to listOf("fraco para forte", "de fraco a forte", "weak to strong"),
    "Academy" to listOf("academia", "academy"),
    "Tower" to listOf("torre", "tower"),
    "Hunters" to listOf("cacadores", "hunter", "hunters", "cazadores"),
    "Monster Girls" to listOf("monster girl", "garotas monstro"),
    "Gyaru" to listOf("gal"),
    "Tsundere" to listOf(),
    "Childhood Friends" to listOf("amigos de infancia", "amiga de infancia", "amigo de infancia"),
    "Love Triangle" to listOf("triangulo amoroso"),
    "Age Gap" to listOf("diferenca de idade"),
    "Netorare" to listOf("ntr"),
    "Villain" to listOf("vilao", "villano", "antihero", "anti-heroi", "anti-hero"),
    "Politics" to listOf("politica"),
    "War" to listOf("guerra"),
    "Royalty" to listOf("realeza", "nobreza", "nobleza"),
    "Ghosts" to listOf("fantasmas", "fantasma", "ghost"),
    "Shapeshifting" to listOf("transformacao", "metamorfose"),
    "Dragons" to listOf("dragoes", "dragon", "dragones"),
    "Magic Academy" to listOf("academia de magia", "escola de magia"),
    "Cyberpunk" to listOf(),
    "Steampunk" to listOf(),
    "Mythology" to listOf("mitologia"),
    "Philosophy" to listOf("filosofia"),
    "Detective" to listOf("detetive", "investigacao", "investigation"),
    "Amnesia" to listOf(),
    "Idol" to listOf("idols", "ídolo"),
    "Boys' School" to listOf("escola masculina"),
    "Girls' School" to listOf("escola feminina"),
    "Otaku Culture" to listOf("otaku"),
    "Crossdressing" to listOf("travesti", "crossdress"),
    "Delinquents" to listOf("delinquentes", "delinquente", "delinquent"),
    "Bullying" to listOf(),
    "Depression" to listOf("depressao"),
    "Suicide" to listOf("suicidio"),
    "Body Swap" to listOf("troca de corpos"),
    "Parody" to listOf("parodia"),
    "Satire" to listOf("satira"),
    "Slapstick" to listOf("pastelao"),
    "Gambling" to listOf("apostas", "jogo de azar"),
    "Card Battle" to listOf("cartas", "card game"),
    "Ninja" to listOf("ninjas"),
    "Samurai" to listOf(),
    "Pirates" to listOf("piratas", "pirata"),
    "Space" to listOf("espaco", "espacial", "espacio"),
    "Robots" to listOf("robo", "robos", "robot"),
    "Animals" to listOf("animais", "animales", "animal"),
    "Cats" to listOf("gatos", "gato", "cat"),
    "Dogs" to listOf("cachorros", "cachorro", "dog"),
    "Iyashikei" to listOf("healing"),
    "Workplace" to listOf("trabalho", "ambiente de trabalho"),
    "Female Protagonist" to listOf("protagonista feminina", "protagonista mulher"),
    "Male Protagonist" to listOf("protagonista masculino", "protagonista homem"),
    "Ensemble Cast" to listOf("elenco coral"),
    "Heterosexual" to listOf("hetero"),
    "Full Color" to listOf("colorido", "full colour", "a cores", "color"),
    "4-koma" to listOf("4koma", "yonkoma"),
    "Anthology" to listOf("antologia"),
    "Award Winning" to listOf("premiado"),
).flatMap { (canonical, others) -> (others + canonical).map { it.fold() to canonical } }
    .toMap()
