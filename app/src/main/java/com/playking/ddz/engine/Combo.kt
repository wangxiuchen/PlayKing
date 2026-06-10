package com.playking.ddz.engine

enum class ComboType {
    SINGLE,          // 单张
    PAIR,            // 对子
    TRIPLE,          // 三张
    TRIPLE_ONE,      // 三带一
    TRIPLE_PAIR,     // 三带对
    STRAIGHT,        // 顺子 ≥5
    PAIR_CHAIN,      // 连对 ≥3 对
    PLANE,           // 飞机不带 ≥2 组
    PLANE_SINGLE,    // 飞机带单
    PLANE_PAIR,      // 飞机带对
    FOUR_TWO_SINGLE, // 四带二单
    FOUR_TWO_PAIR,   // 四带两对
    BOMB,            // 炸弹
    ROCKET           // 王炸
}

/**
 * @param mainRank 比较用主点数：链类取链中最大点；三带/四带取三张/四张部分点数。
 * @param size     链长（顺子张数 / 连对对数 / 飞机组数），非链类为 1。
 */
data class Combo(
    val type: ComboType,
    val mainRank: Int,
    val size: Int,
    val cards: List<Card>
) {
    val isBomb: Boolean get() = type == ComboType.BOMB || type == ComboType.ROCKET
}

object ComboParser {

    /** 判定一组牌的牌型；非法返回 null。 */
    fun parse(cards: List<Card>): Combo? {
        if (cards.isEmpty()) return null
        val counts = HashMap<Int, Int>()
        for (c in cards) counts[c.rank] = (counts[c.rank] ?: 0) + 1
        val n = cards.size

        when (n) {
            1 -> return Combo(ComboType.SINGLE, cards[0].rank, 1, cards)
            2 -> {
                if (counts.size == 1) return Combo(ComboType.PAIR, cards[0].rank, 1, cards)
                if (counts.containsKey(Rank.SMALL_JOKER) && counts.containsKey(Rank.BIG_JOKER))
                    return Combo(ComboType.ROCKET, Rank.BIG_JOKER, 1, cards)
                return null
            }
            3 -> return if (counts.size == 1) Combo(ComboType.TRIPLE, cards[0].rank, 1, cards) else null
            4 -> {
                if (counts.size == 1) return Combo(ComboType.BOMB, cards[0].rank, 1, cards)
                val triple = counts.entries.firstOrNull { it.value == 3 }
                if (triple != null) return Combo(ComboType.TRIPLE_ONE, triple.key, 1, cards)
                return null
            }
        }

        // n >= 5
        val ranks = counts.keys.sorted()

        // 三带对（33344 必须判为三带对）
        if (n == 5) {
            val triple = counts.entries.firstOrNull { it.value == 3 }
            if (triple != null && counts.size == 2) {
                val other = counts.entries.first { it.key != triple.key }
                if (other.value == 2 && other.key < Rank.SMALL_JOKER)
                    return Combo(ComboType.TRIPLE_PAIR, triple.key, 1, cards)
                return null
            }
        }

        // 顺子：全单张、连续、范围 3~A、≥5 张
        if (n >= 5 && counts.values.all { it == 1 } && isConsecutive(ranks))
            return Combo(ComboType.STRAIGHT, ranks.last(), n, cards)

        // 连对：全对子、连续、范围 3~A、≥3 对
        if (n >= 6 && n % 2 == 0 && counts.values.all { it == 2 } && counts.size >= 3 && isConsecutive(ranks))
            return Combo(ComboType.PAIR_CHAIN, ranks.last(), counts.size, cards)

        // 飞机不带：全三张、连续、≥2 组（333444 一律按飞机不带，不存在三带三）
        if (n >= 6 && n % 3 == 0 && counts.values.all { it == 3 } && counts.size >= 2 && isConsecutive(ranks))
            return Combo(ComboType.PLANE, ranks.last(), counts.size, cards)

        // 飞机带单 / 飞机带对：按能成立的最大飞机解释
        parsePlaneWithWings(cards, counts, n)?.let { return it }

        // 四带二单（两张单牌点数相同也算）
        if (n == 6) {
            val quad = counts.entries.firstOrNull { it.value == 4 }
            if (quad != null) return Combo(ComboType.FOUR_TWO_SINGLE, quad.key, 1, cards)
        }

        // 四带两对（王不能作为对子；剩余 4 张须能拆成两个对子）
        if (n == 8) {
            val quad = counts.entries.firstOrNull { it.value == 4 }
            if (quad != null) {
                val rest = counts.filterKeys { it != quad.key }
                if (rest.values.all { it % 2 == 0 } && rest.values.sum() == 4 &&
                    rest.keys.all { it < Rank.SMALL_JOKER }
                ) return Combo(ComboType.FOUR_TWO_PAIR, quad.key, 1, cards)
            }
        }

        return null
    }

    /** 点数列表是否在 3~A 内严格连续。 */
    private fun isConsecutive(ranks: List<Int>): Boolean {
        if (ranks.isEmpty() || ranks.last() > Rank.MAX_CHAIN) return false
        for (i in 1 until ranks.size) if (ranks[i] != ranks[i - 1] + 1) return false
        return true
    }

    /**
     * 飞机带翅膀。翅膀可含 2 和王；炸弹可拆作翅膀。
     * 优先取最大的飞机组数 m，再取最高的链位置。
     */
    private fun parsePlaneWithWings(cards: List<Card>, counts: Map<Int, Int>, n: Int): Combo? {
        val tripleRanks = counts.entries
            .filter { it.value >= 3 && it.key <= Rank.MAX_CHAIN }
            .map { it.key }
            .sorted()
        if (tripleRanks.size < 2) return null

        // 枚举连续三张窗口，m 从大到小
        val maxM = tripleRanks.size
        for (m in maxM downTo 2) {
            if (n != 4 * m && n != 5 * m) continue
            // 窗口右端从大到小
            for (end in tripleRanks.indices.reversed()) {
                var ok = true
                for (k in 0 until m) {
                    val idx = end - k
                    if (idx < 0 || tripleRanks[idx] != tripleRanks[end] - k) { ok = false; break }
                }
                if (!ok) continue
                val window = (tripleRanks[end] - m + 1)..tripleRanks[end]
                // 移除窗口中每点 3 张后的剩余
                val rest = HashMap<Int, Int>()
                var restTotal = 0
                for ((r, c) in counts) {
                    val left = if (r in window) c - 3 else c
                    if (left < 0) { ok = false; break }
                    if (left > 0) { rest[r] = left; restTotal += left }
                }
                if (!ok) continue
                if (n == 4 * m && restTotal == m)
                    return Combo(ComboType.PLANE_SINGLE, window.last, m, cards)
                if (n == 5 * m && restTotal == 2 * m &&
                    rest.values.all { it % 2 == 0 } &&
                    rest.keys.all { it < Rank.SMALL_JOKER }
                ) return Combo(ComboType.PLANE_PAIR, window.last, m, cards)
            }
        }
        return null
    }
}

object Rules {
    /**
     * candidate 能否压过 target。
     * target 为 null 表示首出，任何合法牌型均可。
     */
    fun canBeat(candidate: Combo, target: Combo?): Boolean {
        if (target == null) return true
        if (candidate.type == ComboType.ROCKET) return true
        if (target.type == ComboType.ROCKET) return false
        if (candidate.type == ComboType.BOMB) {
            return if (target.type == ComboType.BOMB) candidate.mainRank > target.mainRank else true
        }
        if (target.type == ComboType.BOMB) return false
        // 同牌型同张数（链类还须同长度）
        if (candidate.type != target.type) return false
        if (candidate.size != target.size) return false
        if (candidate.cards.size != target.cards.size) return false
        return candidate.mainRank > target.mainRank
    }
}
