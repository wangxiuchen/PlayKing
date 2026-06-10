package com.playking.ddz.engine

import kotlin.random.Random

enum class Phase { DEALING, BIDDING, PLAYING, FINISHED }

/** 座位：0 = 真人玩家，1 = 下家 AI（顺时针），2 = 上家 AI。 */
data class PlayEvent(val seat: Int, val combo: Combo?) // combo == null 表示"不出"

data class RoundResult(
    val landlordSeat: Int,
    val landlordWin: Boolean,
    val bid: Int,
    val bombCount: Int,
    val spring: Boolean,      // 春天或反春天
    val multiplier: Int,
    /** 各座位得分：地主 ±2×倍数，农民各 ±1×倍数。 */
    val scores: List<Int>
)

/**
 * 单局斗地主状态机：发牌 → 叫分 → 出牌 → 结算。
 * 纯逻辑，不依赖任何 Android API；由外层（ViewModel / 测试）驱动。
 */
class DdzGame(private val random: Random = Random.Default) {

    var phase: Phase = Phase.DEALING; private set
    val hands: List<MutableList<Card>> = listOf(mutableListOf(), mutableListOf(), mutableListOf())
    var bottom: List<Card> = emptyList(); private set

    // 叫分阶段
    var firstBidder: Int = 0; private set
    var currentBidder: Int = -1; private set
    var highestBid: Int = 0; private set
    var highestBidder: Int = -1; private set
    private var bidTurns = 0
    val bidHistory = ArrayList<Pair<Int, Int>>() // (seat, score) score=0 表示不叫
    var redealCount = 0; private set

    // 出牌阶段
    var landlordSeat: Int = -1; private set
    var currentSeat: Int = -1; private set
    var currentTarget: Combo? = null; private set   // 本轮需要压过的牌
    var targetOwner: Int = -1; private set          // 出 currentTarget 的座位
    var passCountInRow = 0; private set
    var bombCount = 0; private set
    val playHistory = ArrayList<PlayEvent>()
    private val playsBySeat = intArrayOf(0, 0, 0)

    var result: RoundResult? = null; private set

    /** 开始新的一局：洗牌发牌并确定先叫分者（1~10 随机点数，从真人玩家起顺时针）。 */
    fun startRound() {
        phase = Phase.DEALING
        val deal = Deck.deal(random)
        for (i in 0..2) { hands[i].clear(); hands[i].addAll(deal.hands[i].sortedForHand()) }
        bottom = deal.bottom
        val r = random.nextInt(1, 11) // 1..10
        firstBidder = (r - 1) % 3
        currentBidder = firstBidder
        highestBid = 0; highestBidder = -1; bidTurns = 0
        bidHistory.clear()
        landlordSeat = -1; currentSeat = -1
        currentTarget = null; targetOwner = -1
        passCountInRow = 0; bombCount = 0
        playHistory.clear()
        playsBySeat[0] = 0; playsBySeat[1] = 0; playsBySeat[2] = 0
        result = null
        phase = Phase.BIDDING
    }

    /** 当前叫分者可选分数（必须高于当前最高分；0 表示不叫）。 */
    fun availableBids(): List<Int> = (highestBid + 1..3).toList()

    /**
     * 叫分。score=0 不叫；返回 true 表示叫分阶段结束。
     * 三人都不叫 → 自动重新发牌（phase 回到 BIDDING，redealCount+1）。
     */
    fun bid(seat: Int, score: Int): Boolean {
        check(phase == Phase.BIDDING) { "not in bidding phase" }
        check(seat == currentBidder) { "not $seat's turn to bid" }
        require(score == 0 || score in (highestBid + 1)..3) { "illegal bid $score" }
        bidHistory.add(seat to score)
        bidTurns++
        if (score > highestBid) { highestBid = score; highestBidder = seat }

        if (score == 3) { finishBidding(); return true }
        if (bidTurns == 3) {
            if (highestBidder == -1) {
                // 流局：重新洗牌发牌
                redealCount++
                startRound()
                return false
            }
            finishBidding(); return true
        }
        currentBidder = (currentBidder + 1) % 3
        return false
    }

    private fun finishBidding() {
        landlordSeat = highestBidder
        hands[landlordSeat].addAll(bottom)
        val sorted = hands[landlordSeat].sortedForHand()
        hands[landlordSeat].clear(); hands[landlordSeat].addAll(sorted)
        currentSeat = landlordSeat
        phase = Phase.PLAYING
    }

    /** 校验 seat 出 cards 是否合法（牌型合法、持有这些牌、能压过目标）。 */
    fun validatePlay(seat: Int, cards: List<Card>): Combo? {
        if (phase != Phase.PLAYING || seat != currentSeat) return null
        val handIds = hands[seat].map { it.id }.toHashSet()
        if (cards.any { it.id !in handIds }) return null
        if (cards.map { it.id }.toHashSet().size != cards.size) return null
        val combo = ComboParser.parse(cards) ?: return null
        return if (Rules.canBeat(combo, currentTarget)) combo else null
    }

    /** 出牌。返回 false 表示非法。 */
    fun play(seat: Int, cards: List<Card>): Boolean {
        val combo = validatePlay(seat, cards) ?: return false
        val ids = cards.map { it.id }.toHashSet()
        hands[seat].removeAll { it.id in ids }
        playHistory.add(PlayEvent(seat, combo))
        playsBySeat[seat]++
        if (combo.isBomb) bombCount++
        currentTarget = combo
        targetOwner = seat
        passCountInRow = 0
        if (hands[seat].isEmpty()) { finishRound(seat); return true }
        currentSeat = (currentSeat + 1) % 3
        return true
    }

    /** 不出。首出者不能不出。 */
    fun pass(seat: Int): Boolean {
        if (phase != Phase.PLAYING || seat != currentSeat) return false
        if (currentTarget == null) return false
        playHistory.add(PlayEvent(seat, null))
        passCountInRow++
        if (passCountInRow == 2) {
            // 一轮结束，最后出牌者获得首出权
            currentSeat = targetOwner
            currentTarget = null
            targetOwner = -1
            passCountInRow = 0
        } else {
            currentSeat = (currentSeat + 1) % 3
        }
        return true
    }

    private fun finishRound(winnerSeat: Int) {
        phase = Phase.FINISHED
        val landlordWin = winnerSeat == landlordSeat
        val farmers = (0..2).filter { it != landlordSeat }
        val spring = if (landlordWin) {
            farmers.all { playsBySeat[it] == 0 }                  // 春天
        } else {
            playsBySeat[landlordSeat] == 1                         // 反春天
        }
        var multiplier = highestBid
        repeat(bombCount) { multiplier *= 2 }
        if (spring) multiplier *= 2
        val scores = IntArray(3)
        if (landlordWin) {
            scores[landlordSeat] = 2 * multiplier
            farmers.forEach { scores[it] = -multiplier }
        } else {
            scores[landlordSeat] = -2 * multiplier
            farmers.forEach { scores[it] = multiplier }
        }
        result = RoundResult(
            landlordSeat = landlordSeat,
            landlordWin = landlordWin,
            bid = highestBid,
            bombCount = bombCount,
            spring = spring,
            multiplier = multiplier,
            scores = scores.toList()
        )
    }

    /** 当前倍数（对局中实时展示）。 */
    fun currentMultiplier(): Int {
        if (highestBid <= 0) return 1
        var m = highestBid
        repeat(bombCount) { m *= 2 }
        return m
    }

    // ================= 对局快照（v2：对局恢复） =================

    /** 序列化当前对局（仅 BIDDING/PLAYING 有意义；FINISHED 不应存档）。 */
    fun snapshot(): String = buildString {
        appendLine("v=$SNAPSHOT_VERSION")
        appendLine("phase=$phase")
        for (i in 0..2) appendLine("hand$i=" + hands[i].joinToString(",") { it.id.toString() })
        appendLine("bottom=" + bottom.joinToString(",") { it.id.toString() })
        appendLine("firstBidder=$firstBidder")
        appendLine("currentBidder=$currentBidder")
        appendLine("highestBid=$highestBid")
        appendLine("highestBidder=$highestBidder")
        appendLine("bidTurns=$bidTurns")
        appendLine("bids=" + bidHistory.joinToString(";") { "${it.first}:${it.second}" })
        appendLine("redeal=$redealCount")
        appendLine("landlord=$landlordSeat")
        appendLine("current=$currentSeat")
        appendLine("targetOwner=$targetOwner")
        appendLine("passes=$passCountInRow")
        appendLine("bombs=$bombCount")
        appendLine("target=" + (currentTarget?.cards?.joinToString(",") { it.id.toString() } ?: ""))
        appendLine("plays=" + playHistory.joinToString(";") { e ->
            "${e.seat}:" + (e.combo?.cards?.joinToString(",") { c -> c.id.toString() } ?: "P")
        })
        appendLine("playsBySeat=" + playsBySeat.joinToString(","))
    }

    companion object {
        /** 存档格式版本；不匹配的存档静默丢弃（C-03）。 */
        const val SNAPSHOT_VERSION = 2

        private fun idsToCards(s: String, deck: List<Card>): List<Card>? {
            if (s.isEmpty()) return emptyList()
            val out = ArrayList<Card>()
            for (tok in s.split(",")) {
                val id = tok.toIntOrNull() ?: return null
                if (id !in deck.indices) return null
                out.add(deck[id])
            }
            return out
        }

        /** 从快照恢复对局；格式/版本不符返回 null。 */
        fun fromSnapshot(text: String, random: kotlin.random.Random = kotlin.random.Random.Default): DdzGame? {
            try {
                val map = HashMap<String, String>()
                for (line in text.lines()) {
                    val i = line.indexOf('=')
                    if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
                }
                if (map["v"]?.toIntOrNull() != SNAPSHOT_VERSION) return null
                val phase = Phase.valueOf(map["phase"] ?: return null)
                if (phase != Phase.BIDDING && phase != Phase.PLAYING) return null
                val deck = Deck.fullDeck()
                val g = DdzGame(random)
                g.phase = phase
                val seen = HashSet<Int>()
                for (i in 0..2) {
                    val h = idsToCards(map["hand$i"] ?: return null, deck) ?: return null
                    if (!h.all { seen.add(it.id) }) return null
                    g.hands[i].clear(); g.hands[i].addAll(h)
                }
                g.bottom = idsToCards(map["bottom"] ?: return null, deck) ?: return null
                g.firstBidder = map["firstBidder"]?.toIntOrNull() ?: return null
                g.currentBidder = map["currentBidder"]?.toIntOrNull() ?: return null
                g.highestBid = map["highestBid"]?.toIntOrNull() ?: return null
                g.highestBidder = map["highestBidder"]?.toIntOrNull() ?: return null
                g.bidTurns = map["bidTurns"]?.toIntOrNull() ?: return null
                g.redealCount = map["redeal"]?.toIntOrNull() ?: 0
                g.landlordSeat = map["landlord"]?.toIntOrNull() ?: return null
                g.currentSeat = map["current"]?.toIntOrNull() ?: return null
                g.targetOwner = map["targetOwner"]?.toIntOrNull() ?: return null
                g.passCountInRow = map["passes"]?.toIntOrNull() ?: return null
                g.bombCount = map["bombs"]?.toIntOrNull() ?: return null
                (map["bids"] ?: "").takeIf { it.isNotEmpty() }?.split(";")?.forEach { tok ->
                    val p = tok.split(":")
                    if (p.size != 2) return null
                    g.bidHistory.add((p[0].toIntOrNull() ?: return null) to (p[1].toIntOrNull() ?: return null))
                }
                val ct = map["target"] ?: ""
                g.currentTarget = if (ct.isEmpty()) null
                else ComboParser.parse(idsToCards(ct, deck) ?: return null) ?: return null
                (map["plays"] ?: "").takeIf { it.isNotEmpty() }?.split(";")?.forEach { tok ->
                    val i = tok.indexOf(':')
                    if (i <= 0) return null
                    val seat = tok.substring(0, i).toIntOrNull() ?: return null
                    if (seat !in 0..2) return null
                    val body = tok.substring(i + 1)
                    if (body == "P") g.playHistory.add(PlayEvent(seat, null))
                    else {
                        val cards = idsToCards(body, deck) ?: return null
                        val combo = ComboParser.parse(cards) ?: return null
                        g.playHistory.add(PlayEvent(seat, combo))
                    }
                }
                map["playsBySeat"]?.split(",")?.map { it.toIntOrNull() ?: return null }?.let {
                    if (it.size != 3) return null
                    for (i in 0..2) g.playsBySeat[i] = it[i]
                } ?: return null
                // 基本一致性校验
                if (phase == Phase.PLAYING && (g.landlordSeat !in 0..2 || g.currentSeat !in 0..2)) return null
                if (phase == Phase.BIDDING && g.currentBidder !in 0..2) return null
                return g
            } catch (e: Exception) {
                return null
            }
        }
    }
}
