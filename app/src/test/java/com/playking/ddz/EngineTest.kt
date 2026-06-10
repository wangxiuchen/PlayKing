package com.playking.ddz

import com.playking.ddz.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** 验收用例：需求文档第 9 章 R 系列与 A-01。 */
class EngineTest {

    private fun cards(spec: String): List<Card> {
        var id = 1000
        val suitUse = HashMap<Int, Int>()
        return spec.split(",").map { tok ->
            val t = tok.trim()
            val rank = when (t) {
                "J" -> 11; "Q" -> 12; "K" -> 13; "A" -> 14; "2" -> 15; "SJ" -> 16; "BJ" -> 17
                else -> t.toInt()
            }
            val suit = if (rank >= 16) -1 else (suitUse[rank] ?: 0).also { suitUse[rank] = it + 1 }
            Card(id++, rank, suit)
        }
    }

    private fun type(spec: String) = ComboParser.parse(cards(spec))?.type
    private fun combo(spec: String) = ComboParser.parse(cards(spec))!!

    @Test fun r01_straight() = assertEquals(ComboType.STRAIGHT, type("3,4,5,6,7"))
    @Test fun r02_straightWithTwoIllegal() = assertNull(type("J,Q,K,A,2"))
    @Test fun r03_triplePair() = assertEquals(ComboType.TRIPLE_PAIR, type("3,3,3,4,4"))
    @Test fun r04_rocket() = assertEquals(ComboType.ROCKET, type("SJ,BJ"))
    @Test fun r05_bombBeatsAny() = assertTrue(Rules.canBeat(combo("K,K,K,K"), combo("3,3,4,4,5,5")))
    @Test fun r06_biggerStraight() = assertTrue(Rules.canBeat(combo("4,5,6,7,8"), combo("3,4,5,6,7")))
    @Test fun r07_lengthMismatch() = assertFalse(Rules.canBeat(combo("4,5,6,7,8,9"), combo("3,4,5,6,7")))

    @Test fun planeNotTripleTriple() = assertEquals(ComboType.PLANE, type("3,3,3,4,4,4"))
    @Test fun planeWingsMayContainJokerAndTwo() = assertEquals(ComboType.PLANE_SINGLE, type("3,3,3,4,4,4,SJ,2"))
    @Test fun planeWithSingles() = assertEquals(ComboType.PLANE_SINGLE, type("3,3,3,4,4,4,7,9"))
    @Test fun planeWrongWingCount() = assertNull(type("3,3,3,4,4,4,7"))
    @Test fun planeWithPairs() = assertEquals(ComboType.PLANE_PAIR, type("3,3,3,4,4,4,7,7,9,9"))
    @Test fun planeMixedWingsIllegal() = assertNull(type("3,3,3,4,4,4,7,7,9"))
    @Test fun fourTwoSingle() = assertEquals(ComboType.FOUR_TWO_SINGLE, type("9,9,9,9,3,7"))
    @Test fun fourTwoSingleFromPair() = assertEquals(ComboType.FOUR_TWO_SINGLE, type("9,9,9,9,7,7"))
    @Test fun fourTwoPair() = assertEquals(ComboType.FOUR_TWO_PAIR, type("9,9,9,9,3,3,7,7"))
    @Test fun triplePairWrong() = assertNull(type("Q,Q,Q,7,5"))
    @Test fun pairChainTooShort() = assertNull(type("3,3,4,4"))
    @Test fun pairChainWithTwoIllegal() = assertNull(type("A,A,2,2"))
    @Test fun straightTooShort() = assertNull(type("3,4,5,6"))
    @Test fun rocketBeatsBomb() = assertTrue(Rules.canBeat(combo("SJ,BJ"), combo("2,2,2,2")))
    @Test fun bombCompareRank() {
        assertTrue(Rules.canBeat(combo("A,A,A,A"), combo("K,K,K,K")))
        assertFalse(Rules.canBeat(combo("3,3,3,3"), combo("K,K,K,K")))
    }

    @Test fun r10_hintEmptyWhenUnbeatable() {
        assertTrue(MoveGenerator.beats(cards("3,4,5"), combo("2")).isEmpty())
    }

    @Test fun hintSortedAscending() {
        val opts = MoveGenerator.beats(cards("3,4,2,SJ"), combo("A"))
        assertTrue(opts.isNotEmpty())
        assertEquals(Rank.TWO, opts.first().mainRank)
    }

    @Test fun r08_redealWhenNobodyBids() {
        val g = DdzGame(Random(42))
        g.startRound()
        assertTrue(g.hands.all { it.size == 17 })
        assertEquals(3, g.bottom.size)
        val first = g.currentBidder
        g.bid(first, 0); g.bid((first + 1) % 3, 0)
        val before = g.redealCount
        g.bid((first + 2) % 3, 0)
        assertEquals(before + 1, g.redealCount)
        assertEquals(Phase.BIDDING, g.phase)
        assertTrue(g.hands.all { it.size == 17 })
    }

    @Test fun biddingThreeEndsImmediately() {
        val g = DdzGame(Random(7))
        g.startRound()
        val s = g.currentBidder
        assertTrue(g.bid(s, 3))
        assertEquals(s, g.landlordSeat)
        assertEquals(20, g.hands[s].size)
        assertEquals(s, g.currentSeat)
    }

    /** A-01：1000 局 AI 自动对战，无非法出牌、无死循环，得分守恒。 */
    @Test fun a01_thousandAiGames() {
        var finished = 0
        for (round in 0 until 1000) {
            val g = DdzGame(Random(round.toLong()))
            g.startRound()
            var guard = 0
            while (g.phase == Phase.BIDDING) {
                val seat = g.currentBidder
                val minBid = g.highestBid + 1
                var b = RuleAi.decideBid(g.hands[seat], minBid)
                if (g.redealCount >= 3 && g.bidHistory.size == 2 && g.highestBid == 0) b = minBid
                g.bid(seat, if (b in minBid..3) b else 0)
                assertTrue("bidding loop round=$round", ++guard <= 600)
            }
            var steps = 0
            while (g.phase == Phase.PLAYING) {
                val seat = g.currentSeat
                val decision = RuleAi.decidePlay(seat, g)
                if (decision == null) assertTrue("illegal pass round=$round", g.pass(seat))
                else {
                    assertNotNull("AI illegal play round=$round", g.validatePlay(seat, decision))
                    g.play(seat, decision)
                }
                assertTrue("play loop round=$round", ++steps <= 1000)
            }
            val res = g.result!!
            var m = res.bid; repeat(res.bombCount) { m *= 2 }; if (res.spring) m *= 2
            assertEquals(m, res.multiplier)
            assertEquals(0, res.scores.sum())
            assertEquals(if (res.landlordWin) 2 * m else -2 * m, res.scores[res.landlordSeat])
            finished++
        }
        assertEquals(1000, finished)
    }
}
