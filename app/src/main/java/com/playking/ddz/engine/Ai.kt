package com.playking.ddz.engine

/**
 * 规则型 AI：保证永不出非法牌。
 * 策略（v1）：
 * - 首出：优先出"散牌多"的小牌型（最小顺子/连对/三带/对子/单张里代价最小者）。
 * - 跟牌：用能压住的最小走法；炸弹仅在压地主关键牌或自己快走完时使用。
 * - 农民配合：地主对队友的牌不出时不压队友；地主剩 1~2 张时顶大牌。
 */
object RuleAi {

    /** 叫分策略：按手牌强度估值。 */
    fun decideBid(hand: List<Card>, minBid: Int): Int {
        var strength = 0.0
        val byRank = hand.groupBy { it.rank }
        for ((rank, cs) in byRank) {
            when (rank) {
                Rank.BIG_JOKER -> strength += 3.0
                Rank.SMALL_JOKER -> strength += 2.5
                Rank.TWO -> strength += 1.5 * cs.size
                Rank.ACE -> strength += 0.8 * cs.size
                Rank.KING -> strength += 0.4 * cs.size
            }
            if (cs.size == 4) strength += 3.0
        }
        if (byRank.containsKey(Rank.SMALL_JOKER) && byRank.containsKey(Rank.BIG_JOKER)) strength += 1.5
        val want = when {
            strength >= 9.0 -> 3
            strength >= 7.0 -> 2
            strength >= 5.0 -> 1
            else -> 0
        }
        return if (want >= minBid) want else 0
    }

    /**
     * 出牌决策。返回要出的牌；null 表示"不出"（仅跟牌时可能）。
     * @param seat        自己座位
     * @param game        当前对局（只读取状态）
     */
    fun decidePlay(seat: Int, game: DdzGame): List<Card>? {
        val hand = game.hands[seat]
        val target = game.currentTarget
        val landlord = game.landlordSeat
        val isLandlord = seat == landlord

        if (target == null) return lead(seat, game).cards

        // ---- 跟牌 ----
        val options = MoveGenerator.beats(hand, target)
        if (options.isEmpty()) return null

        val targetFromTeammate = !isLandlord && game.targetOwner != landlord && game.targetOwner != seat

        // 农民配合：队友的牌且地主已"不出"，不压队友（除非自己马上能走完）
        if (targetFromTeammate && landlordPassedOnCurrent(game)) {
            val finisher = options.firstOrNull { it.cards.size == hand.size }
            return finisher?.cards
        }

        val nonBombs = options.filter { !it.isBomb }
        val bombs = options.filter { it.isBomb }

        // 普通走法：选最小
        if (nonBombs.isNotEmpty()) {
            var pick = nonBombs.first()
            // 地主只剩 1~2 张时，农民顶大牌
            if (!isLandlord && game.hands[landlord].size <= 2 && game.targetOwner == landlord) {
                pick = nonBombs.last()
            }
            // 队友的牌用最小的压（不内卷），地主的牌正常最小压
            return pick.cards
        }

        // 只剩炸弹可压：关键时刻才出
        if (bombs.isNotEmpty() && shouldBomb(seat, game)) return bombs.first().cards
        return null
    }

    /** 地主对"当前目标"是否已经选择了不出（即目标是农民出的且地主在其后过牌）。 */
    private fun landlordPassedOnCurrent(game: DdzGame): Boolean {
        val landlord = game.landlordSeat
        // 自 targetOwner 出牌后的过牌记录
        var i = game.playHistory.size - 1
        // 回溯到最近一次实际出牌
        while (i >= 0 && game.playHistory[i].combo == null) {
            if (game.playHistory[i].seat == landlord) return true
            i--
        }
        return false
    }

    /** 是否值得动用炸弹。 */
    private fun shouldBomb(seat: Int, game: DdzGame): Boolean {
        val hand = game.hands[seat]
        val landlord = game.landlordSeat
        val isLandlord = seat == landlord
        val target = game.currentTarget ?: return false
        // 自己快走完：出完炸弹后剩 ≤2 手
        if (hand.size <= 6) return true
        if (isLandlord) {
            // 农民快走完时炸
            val farmers = (0..2).filter { it != landlord }
            if (farmers.any { game.hands[it].size <= 2 }) return true
        } else {
            if (game.targetOwner != landlord) return false   // 不炸队友
            // 地主关键牌：大牌或地主快走完
            if (game.hands[landlord].size <= 4) return true
            if (target.mainRank >= Rank.TWO) return true
            if (target.type == ComboType.BOMB) return false  // 跟炸再炸需谨慎：仅地主快走完时（上面已判）
        }
        return false
    }

    /** 首出：把手牌做一次贪心拆分，从"最零散"的小牌型出起。 */
    private fun lead(seat: Int, game: DdzGame): Combo {
        val hand = game.hands[seat]
        val decomp = decompose(hand)
        val landlord = game.landlordSeat
        val isLandlord = seat == landlord

        // 只剩最后一手：直接出完
        if (decomp.size == 1) return decomp[0]

        // 农民：地主只剩 1~2 张时，避免送单/送对，先出大单牌顶
        if (!isLandlord && game.hands[landlord].size <= 2) {
            val nextIsLandlord = (seat + 1) % 3 == landlord
            if (nextIsLandlord) {
                if (game.hands[landlord].size == 1) {
                    // 出大单张或非单张牌型
                    val nonSingle = decomp.filter { it.type != ComboType.SINGLE && !it.isBomb }
                    if (nonSingle.isNotEmpty()) return nonSingle.minByOrNull { it.mainRank }!!
                    val singles = decomp.filter { it.type == ComboType.SINGLE }
                    if (singles.isNotEmpty()) return singles.maxByOrNull { it.mainRank }!!
                } else {
                    val nonPairSingle = decomp.filter { it.type != ComboType.PAIR && it.type != ComboType.SINGLE && !it.isBomb }
                    if (nonPairSingle.isNotEmpty()) return nonPairSingle.minByOrNull { it.mainRank }!!
                }
            }
        }

        // 默认：非炸弹里挑"优先级最低"的小牌型先出
        val normal = decomp.filter { !it.isBomb }
        if (normal.isNotEmpty()) {
            return normal.minWithOrNull(compareBy({ leadOrder(it.type) }, { it.mainRank }))!!
        }
        return decomp.minByOrNull { it.mainRank }!!
    }

    private fun leadOrder(type: ComboType): Int = when (type) {
        ComboType.STRAIGHT -> 0
        ComboType.PAIR_CHAIN -> 1
        ComboType.PLANE, ComboType.PLANE_SINGLE, ComboType.PLANE_PAIR -> 2
        ComboType.TRIPLE_ONE, ComboType.TRIPLE_PAIR, ComboType.TRIPLE -> 3
        ComboType.SINGLE -> 4
        ComboType.PAIR -> 5
        ComboType.FOUR_TWO_SINGLE, ComboType.FOUR_TWO_PAIR -> 6
        ComboType.BOMB, ComboType.ROCKET -> 7
    }

    /**
     * 手牌贪心拆分为合法牌型组合（王炸 → 炸弹 → 最长顺子 → 连对 → 飞机/三张 → 对子 → 单张）。
     * 保证覆盖全部手牌。
     */
    fun decompose(hand: List<Card>): List<Combo> {
        val remaining = hand.toMutableList()
        val result = ArrayList<Combo>()

        fun byRank() = remaining.groupBy { it.rank }

        // 王炸
        run {
            val m = byRank()
            val sj = m[Rank.SMALL_JOKER]; val bj = m[Rank.BIG_JOKER]
            if (sj != null && bj != null) {
                val cs = listOf(sj[0], bj[0])
                result.add(Combo(ComboType.ROCKET, Rank.BIG_JOKER, 1, cs))
                remaining.removeAll { it.id == sj[0].id || it.id == bj[0].id }
            }
        }
        // 炸弹（保留，不拆）
        for ((_, cs) in byRank()) if (cs.size == 4) {
            result.add(Combo(ComboType.BOMB, cs[0].rank, 1, cs))
            val ids = cs.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        // 最长顺子（≥5），反复提取
        while (true) {
            val m = byRank()
            var best: IntRange? = null
            var start = Rank.THREE
            while (start <= Rank.MAX_CHAIN - 4) {
                if ((m[start]?.size ?: 0) >= 1) {
                    var end = start
                    while (end + 1 <= Rank.MAX_CHAIN && (m[end + 1]?.size ?: 0) >= 1) end++
                    if (end - start + 1 >= 5 && (best == null || end - start > best.last - best.first)) best = start..end
                    start = end + 1
                } else start++
            }
            if (best == null) break
            val cards = ArrayList<Card>()
            for (r in best) cards.add(m.getValue(r)[0])
            result.add(Combo(ComboType.STRAIGHT, best.last, best.last - best.first + 1, cards))
            val ids = cards.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        // 连对（≥3 对）
        while (true) {
            val m = byRank()
            var best: IntRange? = null
            var start = Rank.THREE
            while (start <= Rank.MAX_CHAIN - 2) {
                if ((m[start]?.size ?: 0) >= 2) {
                    var end = start
                    while (end + 1 <= Rank.MAX_CHAIN && (m[end + 1]?.size ?: 0) >= 2) end++
                    if (end - start + 1 >= 3 && (best == null || end - start > best.last - best.first)) best = start..end
                    start = end + 1
                } else start++
            }
            if (best == null) break
            val cards = ArrayList<Card>()
            for (r in best) cards.addAll(m.getValue(r).take(2))
            result.add(Combo(ComboType.PAIR_CHAIN, best.last, best.last - best.first + 1, cards))
            val ids = cards.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        // 三张（暂不强行配翅膀，出牌时以 TRIPLE 或由 MoveGenerator 配带）
        for ((_, cs) in byRank()) if (cs.size == 3) {
            result.add(Combo(ComboType.TRIPLE, cs[0].rank, 1, cs))
            val ids = cs.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        // 对子
        for ((_, cs) in byRank()) if (cs.size == 2) {
            result.add(Combo(ComboType.PAIR, cs[0].rank, 1, cs))
            val ids = cs.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        // 单张
        for (c in remaining.sortedBy { it.rank }) {
            result.add(Combo(ComboType.SINGLE, c.rank, 1, listOf(c)))
        }
        return result
    }
}
