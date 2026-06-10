package com.playking.ddz.engine

/**
 * 走法生成：给定手牌与需要压过的目标牌，枚举所有可压走法（含炸弹/王炸）。
 * 同时供"提示"功能与 AI 使用。结果按"代价"从小到大排序：普通走法在前、炸弹靠后、王炸最后。
 */
object MoveGenerator {

    /** 找出 hand 中所有能压过 target 的走法；target 为 null 时返回首出候选。 */
    fun beats(hand: List<Card>, target: Combo?): List<Combo> {
        if (target == null) return leads(hand)
        val result = ArrayList<Combo>()
        val byRank = hand.groupBy { it.rank }

        when (target.type) {
            ComboType.SINGLE -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank)
                    result.add(Combo(ComboType.SINGLE, rank, 1, listOf(cs[0])))
            }
            ComboType.PAIR -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank && cs.size >= 2)
                    result.add(Combo(ComboType.PAIR, rank, 1, cs.take(2)))
            }
            ComboType.TRIPLE -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank && cs.size >= 3)
                    result.add(Combo(ComboType.TRIPLE, rank, 1, cs.take(3)))
            }
            ComboType.TRIPLE_ONE -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank && cs.size >= 3) {
                    val wing = pickWingSingles(byRank, setOf(rank), 1)
                    if (wing != null)
                        result.add(Combo(ComboType.TRIPLE_ONE, rank, 1, cs.take(3) + wing))
                }
            }
            ComboType.TRIPLE_PAIR -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank && cs.size >= 3) {
                    val wing = pickWingPairs(byRank, setOf(rank), 1)
                    if (wing != null)
                        result.add(Combo(ComboType.TRIPLE_PAIR, rank, 1, cs.take(3) + wing))
                }
            }
            ComboType.STRAIGHT -> result.addAll(chainBeats(byRank, target, 1, target.cards.size))
            ComboType.PAIR_CHAIN -> result.addAll(chainBeats(byRank, target, 2, target.size))
            ComboType.PLANE -> result.addAll(chainBeats(byRank, target, 3, target.size))
            ComboType.PLANE_SINGLE -> {
                for (base in chainBeats(byRank, target, 3, target.size)) {
                    val used = base.cards.map { it.rank }.toSet()
                    val wing = pickWingSingles(byRank, used, target.size)
                    if (wing != null)
                        result.add(Combo(ComboType.PLANE_SINGLE, base.mainRank, target.size, base.cards + wing))
                }
            }
            ComboType.PLANE_PAIR -> {
                for (base in chainBeats(byRank, target, 3, target.size)) {
                    val used = base.cards.map { it.rank }.toSet()
                    val wing = pickWingPairs(byRank, used, target.size)
                    if (wing != null)
                        result.add(Combo(ComboType.PLANE_PAIR, base.mainRank, target.size, base.cards + wing))
                }
            }
            ComboType.FOUR_TWO_SINGLE -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank && cs.size >= 4) {
                    val wing = pickWingSingles(byRank, setOf(rank), 2)
                    if (wing != null)
                        result.add(Combo(ComboType.FOUR_TWO_SINGLE, rank, 1, cs.take(4) + wing))
                }
            }
            ComboType.FOUR_TWO_PAIR -> {
                for ((rank, cs) in byRank) if (rank > target.mainRank && cs.size >= 4) {
                    val wing = pickWingPairs(byRank, setOf(rank), 2)
                    if (wing != null)
                        result.add(Combo(ComboType.FOUR_TWO_PAIR, rank, 1, cs.take(4) + wing))
                }
            }
            ComboType.BOMB, ComboType.ROCKET -> { /* 仅炸弹/王炸可压，下方统一追加 */ }
        }

        // 炸弹与王炸
        if (target.type != ComboType.ROCKET) {
            for ((rank, cs) in byRank) {
                if (cs.size == 4 && (target.type != ComboType.BOMB || rank > target.mainRank))
                    result.add(Combo(ComboType.BOMB, rank, 1, cs))
            }
            if (byRank.containsKey(Rank.SMALL_JOKER) && byRank.containsKey(Rank.BIG_JOKER)) {
                result.add(
                    Combo(
                        ComboType.ROCKET, Rank.BIG_JOKER, 1,
                        listOf(byRank.getValue(Rank.SMALL_JOKER)[0], byRank.getValue(Rank.BIG_JOKER)[0])
                    )
                )
            }
        }

        return result.sortedWith(compareBy({ if (it.type == ComboType.ROCKET) 2 else if (it.type == ComboType.BOMB && !targetIsBomb(target)) 1 else 0 }, { it.mainRank }))
    }

    private fun targetIsBomb(target: Combo?) = target?.type == ComboType.BOMB

    /** 链类（顺子/连对/飞机不带主体）压牌：同长度、更大。unit=1/2/3。 */
    private fun chainBeats(byRank: Map<Int, List<Card>>, target: Combo, unit: Int, length: Int): List<Combo> {
        val result = ArrayList<Combo>()
        val type = when (unit) { 1 -> ComboType.STRAIGHT; 2 -> ComboType.PAIR_CHAIN; else -> ComboType.PLANE }
        var end = target.mainRank + 1
        while (end <= Rank.MAX_CHAIN) {
            val start = end - length + 1
            if (start < Rank.THREE) { end++; continue }
            var ok = true
            for (r in start..end) if ((byRank[r]?.size ?: 0) < unit) { ok = false; break }
            if (ok) {
                val cards = ArrayList<Card>(unit * length)
                for (r in start..end) cards.addAll(byRank.getValue(r).take(unit))
                result.add(Combo(type, end, length, cards))
            }
            end++
        }
        return result
    }

    /**
     * 挑 count 张单牌作翅膀/附牌，excluded 为主体点数。
     * 代价优先：散张数量少的点先拆，避免拆炸弹；可含 2 与王。
     */
    private fun pickWingSingles(byRank: Map<Int, List<Card>>, excluded: Set<Int>, count: Int): List<Card>? {
        val pool = byRank.entries
            .filter { it.key !in excluded }
            .sortedWith(compareBy({ if (it.value.size >= 4) 100 else it.value.size }, { it.key }))
        val picked = ArrayList<Card>(count)
        for (e in pool) {
            for (c in e.value) {
                picked.add(c)
                if (picked.size == count) return picked
            }
        }
        return null
    }

    /** 挑 count 个对子作翅膀（王不可作对子）。 */
    private fun pickWingPairs(byRank: Map<Int, List<Card>>, excluded: Set<Int>, count: Int): List<Card>? {
        val pool = byRank.entries
            .filter { it.key !in excluded && it.value.size >= 2 && it.key < Rank.SMALL_JOKER }
            .sortedWith(compareBy({ if (it.value.size >= 4) 100 else it.value.size }, { it.key }))
        val picked = ArrayList<Card>(count * 2)
        var pairs = 0
        for (e in pool) {
            var avail = e.value.size / 2
            var i = 0
            while (avail > 0 && pairs < count) {
                picked.add(e.value[i]); picked.add(e.value[i + 1])
                i += 2; avail--; pairs++
            }
            if (pairs == count) return picked
        }
        return null
    }

    /** 首出候选：枚举手牌中所有"自然"牌型（供 AI 首出与玩家提示参考）。 */
    fun leads(hand: List<Card>): List<Combo> {
        val result = ArrayList<Combo>()
        val byRank = hand.groupBy { it.rank }

        for ((rank, cs) in byRank) {
            result.add(Combo(ComboType.SINGLE, rank, 1, listOf(cs[0])))
            if (cs.size >= 2) result.add(Combo(ComboType.PAIR, rank, 1, cs.take(2)))
            if (cs.size >= 3) result.add(Combo(ComboType.TRIPLE, rank, 1, cs.take(3)))
            if (cs.size >= 3) {
                pickWingSingles(byRank, setOf(rank), 1)?.let {
                    result.add(Combo(ComboType.TRIPLE_ONE, rank, 1, cs.take(3) + it))
                }
                pickWingPairs(byRank, setOf(rank), 1)?.let {
                    result.add(Combo(ComboType.TRIPLE_PAIR, rank, 1, cs.take(3) + it))
                }
            }
            if (cs.size == 4) result.add(Combo(ComboType.BOMB, rank, 1, cs))
        }
        // 顺子（所有长度≥5 的窗口）
        for (len in 5..12) result.addAll(allChains(byRank, 1, len, ComboType.STRAIGHT))
        // 连对
        for (len in 3..10) result.addAll(allChains(byRank, 2, len, ComboType.PAIR_CHAIN))
        // 飞机（不带）
        for (len in 2..6) result.addAll(allChains(byRank, 3, len, ComboType.PLANE))
        if (byRank.containsKey(Rank.SMALL_JOKER) && byRank.containsKey(Rank.BIG_JOKER)) {
            result.add(
                Combo(
                    ComboType.ROCKET, Rank.BIG_JOKER, 1,
                    listOf(byRank.getValue(Rank.SMALL_JOKER)[0], byRank.getValue(Rank.BIG_JOKER)[0])
                )
            )
        }
        return result
    }

    private fun allChains(byRank: Map<Int, List<Card>>, unit: Int, length: Int, type: ComboType): List<Combo> {
        val result = ArrayList<Combo>()
        for (start in Rank.THREE..(Rank.MAX_CHAIN - length + 1)) {
            val end = start + length - 1
            var ok = true
            for (r in start..end) if ((byRank[r]?.size ?: 0) < unit) { ok = false; break }
            if (ok) {
                val cards = ArrayList<Card>(unit * length)
                for (r in start..end) cards.addAll(byRank.getValue(r).take(unit))
                result.add(Combo(type, end, length, cards))
            }
        }
        return result
    }
}
