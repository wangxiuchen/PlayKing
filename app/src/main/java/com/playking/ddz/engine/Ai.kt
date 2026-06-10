package com.playking.ddz.engine

/**
 * 规则型 AI（v1，"简单"难度）：保证永不出非法牌。
 * v2 起通过 AiView 访问信息（只见公开信息 + 自己手牌），决策逻辑与 v1 等价。
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

    /** 兼容入口（v1 测试使用）。 */
    fun decidePlay(seat: Int, game: DdzGame): List<Card>? = decidePlay(AiView.of(game, seat))

    /** 出牌决策。返回要出的牌；null 表示"不出"（仅跟牌时可能）。 */
    fun decidePlay(view: AiView): List<Card>? {
        val hand = view.myHand
        val target = view.currentTarget
        val landlord = view.landlordSeat
        val isLandlord = view.isLandlord

        if (target == null) return lead(view).cards

        // ---- 跟牌 ----
        val options = MoveGenerator.beats(hand, target)
        if (options.isEmpty()) return null

        val targetFromTeammate = !isLandlord && view.targetOwner != landlord && view.targetOwner != view.mySeat

        // 农民配合：队友的牌且地主已"不出"，不压队友（除非自己马上能走完）
        if (targetFromTeammate && landlordPassedOnCurrent(view)) {
            val finisher = options.firstOrNull { it.cards.size == hand.size }
            return finisher?.cards
        }

        val nonBombs = options.filter { !it.isBomb }
        val bombs = options.filter { it.isBomb }

        if (nonBombs.isNotEmpty()) {
            var pick = nonBombs.first()
            // 地主只剩 1~2 张时，农民顶大牌
            if (!isLandlord && view.handCounts[landlord] <= 2 && view.targetOwner == landlord) {
                pick = nonBombs.last()
            }
            return pick.cards
        }

        if (bombs.isNotEmpty() && shouldBomb(view)) return bombs.first().cards
        return null
    }

    /** 地主对"当前目标"是否已经选择了不出。 */
    private fun landlordPassedOnCurrent(view: AiView): Boolean {
        val landlord = view.landlordSeat
        var i = view.playHistory.size - 1
        while (i >= 0 && view.playHistory[i].combo == null) {
            if (view.playHistory[i].seat == landlord) return true
            i--
        }
        return false
    }

    /** 是否值得动用炸弹。 */
    private fun shouldBomb(view: AiView): Boolean {
        val hand = view.myHand
        val landlord = view.landlordSeat
        val isLandlord = view.isLandlord
        val target = view.currentTarget ?: return false
        if (hand.size <= 6) return true
        if (isLandlord) {
            val farmers = (0..2).filter { it != landlord }
            if (farmers.any { view.handCounts[it] <= 2 }) return true
        } else {
            if (view.targetOwner != landlord) return false
            if (view.handCounts[landlord] <= 4) return true
            if (target.mainRank >= Rank.TWO) return true
            if (target.type == ComboType.BOMB) return false
        }
        return false
    }

    /** 首出：把手牌做一次贪心拆分，从"最零散"的小牌型出起。 */
    private fun lead(view: AiView): Combo {
        val hand = view.myHand
        val decomp = decompose(hand)
        val landlord = view.landlordSeat
        val isLandlord = view.isLandlord

        if (decomp.size == 1) return decomp[0]

        if (!isLandlord && view.handCounts[landlord] <= 2) {
            val nextIsLandlord = (view.mySeat + 1) % 3 == landlord
            if (nextIsLandlord) {
                if (view.handCounts[landlord] == 1) {
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
     * 手牌贪心拆分（v1 实现，保留作为"简单"难度与回退）。
     */
    fun decompose(hand: List<Card>): List<Combo> {
        val remaining = hand.toMutableList()
        val result = ArrayList<Combo>()

        fun byRank() = remaining.groupBy { it.rank }

        run {
            val m = byRank()
            val sj = m[Rank.SMALL_JOKER]; val bj = m[Rank.BIG_JOKER]
            if (sj != null && bj != null) {
                val cs = listOf(sj[0], bj[0])
                result.add(Combo(ComboType.ROCKET, Rank.BIG_JOKER, 1, cs))
                remaining.removeAll { it.id == sj[0].id || it.id == bj[0].id }
            }
        }
        for ((_, cs) in byRank()) if (cs.size == 4) {
            result.add(Combo(ComboType.BOMB, cs[0].rank, 1, cs))
            val ids = cs.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
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
        for ((_, cs) in byRank()) if (cs.size == 3) {
            result.add(Combo(ComboType.TRIPLE, cs[0].rank, 1, cs))
            val ids = cs.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        for ((_, cs) in byRank()) if (cs.size == 2) {
            result.add(Combo(ComboType.PAIR, cs[0].rank, 1, cs))
            val ids = cs.map { it.id }.toHashSet()
            remaining.removeAll { it.id in ids }
        }
        for (c in remaining.sortedBy { it.rank }) {
            result.add(Combo(ComboType.SINGLE, c.rank, 1, listOf(c)))
        }
        return result
    }
}
