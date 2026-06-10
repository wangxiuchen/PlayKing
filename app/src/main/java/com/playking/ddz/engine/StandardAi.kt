package com.playking.ddz.engine

/**
 * 标准 AI（v2）：搜索式拆牌 + 记牌推断 + 强化配合。
 * 仅通过 AiView 访问信息（A-05 信息边界）。
 */
object StandardAi {

    // ================= 搜索式拆牌（2.1） =================

    /** 拆分模板：链类 rank 为链最大点，len 为链长。 */
    data class Part(val type: ComboType, val rank: Int, val len: Int)

    private val memo = HashMap<String, Pair<Int, List<Part>>>()

    /** 最少手数拆分（记忆化搜索）。 */
    fun bestParts(hand: List<Card>): List<Part> {
        val counts = IntArray(Rank.BIG_JOKER + 1)
        for (c in hand) counts[c.rank]++
        synchronized(memo) {
            if (memo.size > 300_000) memo.clear()
            return search(counts).second
        }
    }

    /** 基准测试用：清空记忆化缓存以测冷启动耗时。 */
    internal fun clearMemoForBenchmark() = synchronized(memo) { memo.clear() }

    private fun key(counts: IntArray): String {
        val sb = StringBuilder(15)
        for (r in Rank.THREE..Rank.BIG_JOKER) sb.append('a' + counts[r])
        return sb.toString()
    }

    private fun search(counts: IntArray): Pair<Int, List<Part>> {
        var r0 = -1
        for (r in Rank.THREE..Rank.BIG_JOKER) if (counts[r] > 0) { r0 = r; break }
        if (r0 == -1) return 0 to emptyList()
        val k = key(counts)
        memo[k]?.let { return it }

        var bestN = Int.MAX_VALUE
        var bestList: List<Part> = emptyList()

        fun tryOpt(part: Part, deltas: List<Pair<Int, Int>>) {
            for ((r, n) in deltas) counts[r] -= n
            val (n1, p1) = search(counts)
            if (n1 + 1 < bestN) { bestN = n1 + 1; bestList = p1 + part }
            for ((r, n) in deltas) counts[r] += n
        }

        val c0 = counts[r0]
        // 王炸
        if (r0 == Rank.SMALL_JOKER && counts[Rank.BIG_JOKER] > 0)
            tryOpt(Part(ComboType.ROCKET, Rank.BIG_JOKER, 1), listOf(Rank.SMALL_JOKER to 1, Rank.BIG_JOKER to 1))
        // 链类必经过最小非零点 r0
        if (r0 <= Rank.MAX_CHAIN) {
            if (c0 >= 3) {
                var end = r0
                while (end + 1 <= Rank.MAX_CHAIN && counts[end + 1] >= 3) end++
                for (e in (r0 + 1)..end) tryOpt(Part(ComboType.PLANE, e, e - r0 + 1), (r0..e).map { it to 3 })
            }
            if (c0 >= 2) {
                var end = r0
                while (end + 1 <= Rank.MAX_CHAIN && counts[end + 1] >= 2) end++
                for (e in (r0 + 2)..end) tryOpt(Part(ComboType.PAIR_CHAIN, e, e - r0 + 1), (r0..e).map { it to 2 })
            }
            run {
                var end = r0
                while (end + 1 <= Rank.MAX_CHAIN && counts[end + 1] >= 1) end++
                for (e in (r0 + 4)..end) tryOpt(Part(ComboType.STRAIGHT, e, e - r0 + 1), (r0..e).map { it to 1 })
            }
        }
        if (c0 == 4) tryOpt(Part(ComboType.BOMB, r0, 1), listOf(r0 to 4))
        if (c0 >= 3) tryOpt(Part(ComboType.TRIPLE, r0, 1), listOf(r0 to 3))
        if (c0 >= 2) tryOpt(Part(ComboType.PAIR, r0, 1), listOf(r0 to 2))
        tryOpt(Part(ComboType.SINGLE, r0, 1), listOf(r0 to 1))

        val res = bestN to bestList
        memo[k] = res
        return res
    }

    /** 模板映射回实际手牌。 */
    private fun partsToCombos(hand: List<Card>, parts: List<Part>): MutableList<Combo> {
        val pool = HashMap<Int, MutableList<Card>>()
        for (c in hand) pool.getOrPut(c.rank) { mutableListOf() }.add(c)
        fun take(rank: Int, n: Int): List<Card> {
            val l = pool.getValue(rank)
            val t = l.take(n)
            repeat(n) { l.removeAt(0) }
            return t
        }
        return parts.map { p ->
            val cards = when (p.type) {
                ComboType.SINGLE -> take(p.rank, 1)
                ComboType.PAIR -> take(p.rank, 2)
                ComboType.TRIPLE -> take(p.rank, 3)
                ComboType.BOMB -> take(p.rank, 4)
                ComboType.ROCKET -> take(Rank.SMALL_JOKER, 1) + take(Rank.BIG_JOKER, 1)
                ComboType.STRAIGHT -> (p.rank - p.len + 1..p.rank).flatMap { take(it, 1) }
                ComboType.PAIR_CHAIN -> (p.rank - p.len + 1..p.rank).flatMap { take(it, 2) }
                ComboType.PLANE -> (p.rank - p.len + 1..p.rank).flatMap { take(it, 3) }
                else -> error("unexpected part ${p.type}")
            }
            Combo(p.type, p.rank, p.len, cards)
        }.toMutableList()
    }

    /** 出牌计划：拆分 + 三张/飞机吸收小翅膀（只吸收 2 以下的散牌，不浪费控制牌）。 */
    fun playPlan(hand: List<Card>): List<Combo> {
        val combos = partsToCombos(hand, bestParts(hand))
        val singles = combos.filter { it.type == ComboType.SINGLE && it.mainRank < Rank.TWO }
            .sortedBy { it.mainRank }.toMutableList()
        val pairs = combos.filter { it.type == ComboType.PAIR && it.mainRank < Rank.TWO }
            .sortedBy { it.mainRank }.toMutableList()
        val out = combos.toMutableList()

        for (t in combos.filter { it.type == ComboType.TRIPLE }.sortedBy { it.mainRank }) {
            if (singles.isNotEmpty()) {
                val s = singles.removeAt(0)
                out.remove(t); out.remove(s)
                out.add(Combo(ComboType.TRIPLE_ONE, t.mainRank, 1, t.cards + s.cards))
            } else if (pairs.isNotEmpty()) {
                val p = pairs.removeAt(0)
                out.remove(t); out.remove(p)
                out.add(Combo(ComboType.TRIPLE_PAIR, t.mainRank, 1, t.cards + p.cards))
            }
        }
        for (pl in combos.filter { it.type == ComboType.PLANE }.sortedBy { it.mainRank }) {
            val m = pl.size
            if (singles.size >= m) {
                val ws = (0 until m).map { singles.removeAt(0) }
                out.remove(pl); ws.forEach { out.remove(it) }
                out.add(Combo(ComboType.PLANE_SINGLE, pl.mainRank, m, pl.cards + ws.flatMap { it.cards }))
            } else if (pairs.size >= m) {
                val ws = (0 until m).map { pairs.removeAt(0) }
                out.remove(pl); ws.forEach { out.remove(it) }
                out.add(Combo(ComboType.PLANE_PAIR, pl.mainRank, m, pl.cards + ws.flatMap { it.cards }))
            }
        }
        return out
    }

    /** 走完手牌还需几手（含翅膀合并）。 */
    fun effectiveHands(hand: List<Card>): Int = if (hand.isEmpty()) 0 else playPlan(hand).size

    // ================= 叫分 =================

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
        // 手数越少越敢叫
        strength += (8 - effectiveHands(hand)) * 0.5
        val want = when {
            strength >= 9.5 -> 3
            strength >= 7.5 -> 2
            strength >= 5.5 -> 1
            else -> 0
        }
        return if (want >= minBid) want else 0
    }

    // ================= 出牌 =================

    fun decidePlay(view: AiView): List<Card>? {
        val target = view.currentTarget
        return if (target == null) lead(view) else follow(view, target)
    }

    private fun remove(hand: List<Card>, combo: Combo): List<Card> {
        val ids = combo.cards.map { it.id }.toHashSet()
        return hand.filter { it.id !in ids }
    }

    private fun landlordPassedOnCurrent(view: AiView): Boolean {
        var i = view.playHistory.size - 1
        while (i >= 0 && view.playHistory[i].combo == null) {
            if (view.playHistory[i].seat == view.landlordSeat) return true
            i--
        }
        return false
    }

    private fun farmersMinLeft(view: AiView): Int =
        (0..2).filter { it != view.landlordSeat }.minOf { view.handCounts[it] }

    // ---------- 跟牌 ----------

    private fun follow(view: AiView, target: Combo): List<Card>? {
        val hand = view.myHand
        val options = MoveGenerator.beats(hand, target)
        if (options.isEmpty()) return null

        // 一手走完：直接赢
        options.firstOrNull { it.cards.size == hand.size }?.let { return it.cards }

        val landlord = view.landlordSeat
        val isLandlord = view.isLandlord
        val nonBombs = options.filter { !it.isBomb }
        val bombs = options.filter { it.isBomb }
        val fromTeammate = !isLandlord && view.targetOwner == view.teammateSeat

        // ----- 农民配合：队友的牌 -----
        if (fromTeammate) {
            // 地主已不出 → 让队友走
            if (landlordPassedOnCurrent(view)) return null
            // 地主在我之后行动：队友的牌已够大就让；小牌可廉价增援（不拆结构、不用大牌）
            if (target.mainRank >= Rank.JACK) return null
            val now = effectiveHands(hand)
            val cheap = nonBombs.firstOrNull {
                it.mainRank <= Rank.ACE && effectiveHands(remove(hand, it)) < now
            }
            return cheap?.cards
        }

        if (nonBombs.isNotEmpty()) {
            val scored = nonBombs.map { o -> Triple(o, effectiveHands(remove(hand, o)), o.mainRank) }

            // 必胜路径：压住后只剩一手 → 下轮首出即胜。优先安全压牌锁定本轮。
            val finishing = scored.filter { it.second <= 1 }
            if (finishing.isNotEmpty()) {
                val safe = finishing.filter { view.isSafeLead(it.first) }.minByOrNull { it.third }
                return (safe ?: finishing.maxByOrNull { it.third }!!).first.cards
            }

            // 对手报牌：顶最大
            val enemyPressing = if (isLandlord) farmersMinLeft(view) <= 2
            else view.targetOwner == landlord && view.handCounts[landlord] <= 2
            if (enemyPressing) {
                val safe = scored.filter { view.isSafeLead(it.first) }.minByOrNull { it.third }
                if (safe != null) return safe.first.cards
                return nonBombs.last().cards
            }

            // 默认：优先不破坏牌型结构，再选最小点
            val minHands = scored.minOf { it.second }
            var pick = scored.filter { it.second == minHands }.minByOrNull { it.third }!!.first
            // 残局控制：剩 ≤2 手且存在安全压牌 → 用安全牌拿回出牌权
            scored.filter { it.second <= 2 && view.isSafeLead(it.first) }
                .minByOrNull { it.third }?.let { pick = it.first }
            return pick.cards
        }

        // 只剩炸弹
        if (bombs.isNotEmpty() && bombWorth(view, target, hand, bombs.first())) return bombs.first().cards
        return null
    }

    private fun bombWorth(view: AiView, target: Combo, hand: List<Card>, bomb: Combo): Boolean {
        val isLandlord = view.isLandlord
        // 炸完快走完
        if (effectiveHands(remove(hand, bomb)) <= 2) return true
        if (isLandlord) {
            if (farmersMinLeft(view) <= 3) return true
            if (target.mainRank >= Rank.TWO) return true
        } else {
            if (view.targetOwner != view.landlordSeat) return false   // 不炸队友
            if (view.handCounts[view.landlordSeat] <= 4) return true
            if (target.mainRank >= Rank.ACE) return true
        }
        return false
    }

    // ---------- 首出 ----------

    private fun lead(view: AiView): List<Card> {
        val hand = view.myHand
        val plan = playPlan(hand)
        val isLandlord = view.isLandlord
        val landlord = view.landlordSeat

        if (plan.size == 1) return plan[0].cards

        // 必胜路径：剩两手且其中一手是安全牌 → 先出安全牌锁轮次，再出最后一手
        if (plan.size == 2) {
            plan.firstOrNull { view.isSafeLead(it) }?.let { return it.cards }
        }

        val nonBomb = plan.filter { !it.isBomb }

        // 农民：给报牌队友送牌（地主未报牌时）
        if (!isLandlord && view.handCounts[view.teammateSeat] <= 2 && view.handCounts[landlord] > 2) {
            val tLeft = view.handCounts[view.teammateSeat]
            if (tLeft == 1) {
                nonBomb.filter { it.type == ComboType.SINGLE }.minByOrNull { it.mainRank }?.let { return it.cards }
                // 没有现成单张：拆最小牌送单
                hand.filter { !inBombOrRocket(plan, it) }.minByOrNull { it.rank }?.let { return listOf(it) }
            } else {
                nonBomb.filter { it.type == ComboType.PAIR }.minByOrNull { it.mainRank }?.let { return it.cards }
                nonBomb.filter { it.type == ComboType.SINGLE }.minByOrNull { it.mainRank }?.let { return it.cards }
            }
        }

        // 农民：地主报牌时避免送单/送对（沿用 v1 思路，位置意识）
        if (!isLandlord && view.handCounts[landlord] <= 2) {
            val nextIsLandlord = (view.mySeat + 1) % 3 == landlord
            if (nextIsLandlord) {
                if (view.handCounts[landlord] == 1) {
                    val nonSingle = nonBomb.filter { it.type != ComboType.SINGLE }
                    if (nonSingle.isNotEmpty()) return nonSingle.minByOrNull { it.mainRank }!!.cards
                    val singles = nonBomb.filter { it.type == ComboType.SINGLE }
                    val safe = singles.filter { view.isSafeLead(it) }.minByOrNull { it.mainRank }
                    if (safe != null) return safe.cards
                    if (singles.isNotEmpty()) return singles.maxByOrNull { it.mainRank }!!.cards
                } else {
                    val good = nonBomb.filter { it.type != ComboType.PAIR && it.type != ComboType.SINGLE }
                    if (good.isNotEmpty()) return good.minByOrNull { it.mainRank }!!.cards
                }
            }
        }

        // 地主：有农民报牌时避免送终（对称逻辑）
        if (isLandlord) {
            val fl = farmersMinLeft(view)
            if (fl == 1) {
                val nonSingle = nonBomb.filter { it.type != ComboType.SINGLE }
                if (nonSingle.isNotEmpty()) return nonSingle.minByOrNull { it.mainRank }!!.cards
                val singles = nonBomb.filter { it.type == ComboType.SINGLE }
                val safe = singles.filter { view.isSafeLead(it) }.minByOrNull { it.mainRank }
                if (safe != null) return safe.cards
                if (singles.isNotEmpty()) return singles.maxByOrNull { it.mainRank }!!.cards
            } else if (fl == 2) {
                val good = nonBomb.filter { it.type != ComboType.PAIR && it.type != ComboType.SINGLE }
                if (good.isNotEmpty()) return good.minByOrNull { it.mainRank }!!.cards
            }
        }

        // 默认：从"最零散"的小牌型出起；安全大牌留作控制
        if (nonBomb.isNotEmpty()) {
            val unsafe = nonBomb.filter { !view.isSafeLead(it) }
            val cand = if (unsafe.isNotEmpty()) unsafe else nonBomb
            return cand.minWithOrNull(compareBy({ leadOrder(it.type) }, { it.mainRank }))!!.cards
        }
        return plan.minByOrNull { it.mainRank }!!.cards
    }

    private fun inBombOrRocket(plan: List<Combo>, card: Card): Boolean =
        plan.any { it.isBomb && it.cards.any { c -> c.id == card.id } }

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
}
