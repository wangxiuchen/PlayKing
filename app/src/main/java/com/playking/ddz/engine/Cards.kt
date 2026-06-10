package com.playking.ddz.engine

import kotlin.random.Random

/**
 * 点数编码：3..10=3..10, J=11, Q=12, K=13, A=14, 2=15, 小王=16, 大王=17。
 * 顺子/连对/飞机的连续范围为 3..14（3~A），2 与王不参与连续牌型。
 */
object Rank {
    const val THREE = 3
    const val TEN = 10
    const val JACK = 11
    const val QUEEN = 12
    const val KING = 13
    const val ACE = 14
    const val TWO = 15
    const val SMALL_JOKER = 16
    const val BIG_JOKER = 17

    /** 连续牌型允许的最大点数（A）。 */
    const val MAX_CHAIN = ACE

    fun label(rank: Int): String = when (rank) {
        in 3..10 -> rank.toString()
        JACK -> "J"
        QUEEN -> "Q"
        KING -> "K"
        ACE -> "A"
        TWO -> "2"
        SMALL_JOKER -> "小王"
        BIG_JOKER -> "大王"
        else -> "?"
    }
}

/** 花色仅用于展示，不参与大小比较。王的 suit = -1。 */
data class Card(val id: Int, val rank: Int, val suit: Int) : Comparable<Card> {
    override fun compareTo(other: Card): Int = rank.compareTo(other.rank)

    val suitLabel: String
        get() = when (suit) {
            0 -> "♠"; 1 -> "♥"; 2 -> "♣"; 3 -> "♦"; else -> ""
        }

    val isRed: Boolean get() = suit == 1 || suit == 3 || rank == Rank.BIG_JOKER

    override fun toString(): String = suitLabel + Rank.label(rank)
}

object Deck {
    /** 生成一副 54 张牌。 */
    fun fullDeck(): List<Card> {
        val cards = ArrayList<Card>(54)
        var id = 0
        for (rank in 3..Rank.TWO) {
            for (suit in 0..3) cards.add(Card(id++, rank, suit))
        }
        cards.add(Card(id++, Rank.SMALL_JOKER, -1))
        cards.add(Card(id, Rank.BIG_JOKER, -1))
        return cards
    }

    /** Fisher–Yates 均匀洗牌。 */
    fun shuffled(random: Random): List<Card> {
        val cards = fullDeck().toMutableList()
        for (i in cards.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = cards[i]; cards[i] = cards[j]; cards[j] = t
        }
        return cards
    }

    /** 发牌：三手各 17 张 + 3 张底牌。 */
    fun deal(random: Random): DealResult {
        val cards = shuffled(random)
        return DealResult(
            hands = listOf(
                cards.subList(0, 17).toList(),
                cards.subList(17, 34).toList(),
                cards.subList(34, 51).toList()
            ),
            bottom = cards.subList(51, 54).toList()
        )
    }
}

data class DealResult(val hands: List<List<Card>>, val bottom: List<Card>)

/** 手牌展示排序：从大到小。 */
fun List<Card>.sortedForHand(): List<Card> = sortedWith(compareByDescending<Card> { it.rank }.thenBy { it.suit })
