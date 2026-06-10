package com.playking.ddz.engine

/**
 * AI 信息边界（v2 需求 2.2 / A-05）：
 * AI 只能通过本类访问对局信息——自己的手牌 + 公开信息（出牌历史、各家剩牌数、
 * 公开底牌、叫分等）。**不暴露对手手牌**，从类型层面杜绝偷看。
 */
class AiView private constructor(
    val mySeat: Int,
    val myHand: List<Card>,
    val phase: Phase,
    val landlordSeat: Int,
    /** 各座位剩余张数（公开信息）。 */
    val handCounts: List<Int>,
    val currentTarget: Combo?,
    val targetOwner: Int,
    val playHistory: List<PlayEvent>,
    /** 叫分结束后公开的底牌；叫分阶段为空。 */
    val bottom: List<Card>,
    val highestBid: Int
) {
    companion object {
        fun of(game: DdzGame, seat: Int): AiView = AiView(
            mySeat = seat,
            myHand = game.hands[seat].toList(),
            phase = game.phase,
            landlordSeat = game.landlordSeat,
            handCounts = game.hands.map { it.size },
            currentTarget = game.currentTarget,
            targetOwner = game.targetOwner,
            playHistory = game.playHistory.toList(),
            bottom = if (game.phase == Phase.PLAYING || game.phase == Phase.FINISHED) game.bottom else emptyList(),
            highestBid = game.highestBid
        )
    }

    val isLandlord: Boolean get() = mySeat == landlordSeat
    val teammateSeat: Int get() = (0..2).first { it != mySeat && it != landlordSeat }

    /** 各点数已打出的张数。 */
    val playedCounts: IntArray by lazy {
        val a = IntArray(Rank.BIG_JOKER + 1)
        for (e in playHistory) e.combo?.cards?.forEach { a[it.rank]++ }
        a
    }

    /** 对手两家手中合计仍持有的某点数张数（记牌推断的基础）。 */
    fun unseen(rank: Int): Int {
        val total = if (rank >= Rank.SMALL_JOKER) 1 else 4
        return (total - playedCounts[rank] - myHand.count { it.rank == rank }).coerceAtLeast(0)
    }

    fun higherSingleUnseen(rank: Int): Boolean {
        for (r in rank + 1..Rank.BIG_JOKER) if (unseen(r) > 0) return true
        return false
    }

    fun higherPairUnseen(rank: Int): Boolean {
        for (r in rank + 1..Rank.TWO) if (unseen(r) >= 2) return true
        return false
    }

    fun higherTripleUnseen(rank: Int): Boolean {
        for (r in rank + 1..Rank.TWO) if (unseen(r) >= 3) return true
        return false
    }

    /** 对手是否还可能持有炸弹（保守估计：王炸按可能拼成计）。 */
    fun bombUnseenPossible(): Boolean {
        for (r in Rank.THREE..Rank.TWO) if (unseen(r) >= 4) return true
        return unseen(Rank.SMALL_JOKER) > 0 && unseen(Rank.BIG_JOKER) > 0
    }

    /** 对手是否可能持有能压过同长度链（unit=1/2/3）的更大链。 */
    fun higherChainUnseen(maxRank: Int, length: Int, unit: Int): Boolean {
        var end = maxRank + 1
        while (end <= Rank.MAX_CHAIN) {
            val start = end - length + 1
            if (start >= Rank.THREE) {
                var ok = true
                for (r in start..end) if (unseen(r) < unit) { ok = false; break }
                if (ok) return true
            }
            end++
        }
        return false
    }

    /** 首出此牌型时对手是否完全压不住（"安全牌"判定）。 */
    fun isSafeLead(combo: Combo): Boolean {
        if (combo.type == ComboType.ROCKET) return true
        if (combo.type == ComboType.BOMB) {
            for (r in combo.mainRank + 1..Rank.TWO) if (unseen(r) >= 4) return false
            return !(unseen(Rank.SMALL_JOKER) > 0 && unseen(Rank.BIG_JOKER) > 0)
        }
        if (bombUnseenPossible()) return false
        return when (combo.type) {
            ComboType.SINGLE -> !higherSingleUnseen(combo.mainRank)
            ComboType.PAIR -> !higherPairUnseen(combo.mainRank)
            ComboType.TRIPLE, ComboType.TRIPLE_ONE, ComboType.TRIPLE_PAIR -> !higherTripleUnseen(combo.mainRank)
            ComboType.STRAIGHT -> !higherChainUnseen(combo.mainRank, combo.size, 1)
            ComboType.PAIR_CHAIN -> !higherChainUnseen(combo.mainRank, combo.size, 2)
            ComboType.PLANE, ComboType.PLANE_SINGLE, ComboType.PLANE_PAIR ->
                !higherChainUnseen(combo.mainRank, combo.size, 3)
            ComboType.FOUR_TWO_SINGLE, ComboType.FOUR_TWO_PAIR -> {
                for (r in combo.mainRank + 1..Rank.TWO) if (unseen(r) >= 4) return false
                true
            }
            else -> false
        }
    }
}
