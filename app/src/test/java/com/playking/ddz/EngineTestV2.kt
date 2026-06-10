package com.playking.ddz

import com.playking.ddz.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** v2 验收：A-02/A-03/A-04（AI 基准）、C 系列（快照）、H 系列（提示）、P-01（性能）。 */
class EngineTestV2 {

    private fun runGame(seed: Long, std: BooleanArray): RoundResult {
        val g = DdzGame(Random(seed))
        g.startRound()
        var guard = 0
        while (g.phase == Phase.BIDDING) {
            val seat = g.currentBidder
            val minBid = g.highestBid + 1
            var b = if (std[seat]) StandardAi.decideBid(g.hands[seat], minBid)
            else RuleAi.decideBid(g.hands[seat], minBid)
            if (g.redealCount >= 3 && g.bidHistory.size == 2 && g.highestBid == 0) b = minBid
            g.bid(seat, if (b in minBid..3) b else 0)
            assertTrue("bid loop seed=$seed", ++guard <= 600)
        }
        var steps = 0
        while (g.phase == Phase.PLAYING) {
            val seat = g.currentSeat
            val d = if (std[seat]) StandardAi.decidePlay(AiView.of(g, seat)) else RuleAi.decidePlay(seat, g)
            if (d == null) assertTrue("illegal pass seed=$seed", g.pass(seat))
            else {
                assertNotNull("illegal play seed=$seed seat=$seat", g.validatePlay(seat, d))
                g.play(seat, d)
            }
            assertTrue("play loop seed=$seed", ++steps <= 1000)
        }
        val res = g.result!!
        var m = res.bid; repeat(res.bombCount) { m *= 2 }; if (res.spring) m *= 2
        assertEquals(m, res.multiplier)
        assertEquals(0, res.scores.sum())
        return res
    }

    /** A-03：标准 AI 千局零非法、零死循环、得分守恒。 */
    @Test fun a03_standardAiThousandGames() {
        repeat(1000) { runGame(it.toLong(), booleanArrayOf(true, true, true)) }
    }

    /** A-04：三家标准 AI，地主胜率落在 40%~70%。 */
    @Test fun a04_landlordWinRateGuard() {
        var ll = 0
        repeat(1000) { if (runGame(it.toLong(), booleanArrayOf(true, true, true)).landlordWin) ll++ }
        val rate = ll / 10
        assertTrue("landlord win rate $rate% out of 40..70", rate in 40..70)
    }

    /** A-02：标准 AI（座位0）对 v1 AI×2，千局胜率 ≥55%。 */
    @Test fun a02_standardBeatsV1() {
        var wins = 0
        repeat(1000) {
            if (runGame(10000L + it, booleanArrayOf(true, false, false)).scores[0] > 0) wins++
        }
        assertTrue("standard AI win rate ${wins / 10.0}% < 55%", wins >= 550)
    }

    /** P-01：20 张手牌冷缓存拆牌耗时 < 200ms。 */
    @Test fun p01_decompositionSpeed() {
        val rng = Random(99)
        var worst = 0L
        repeat(100) {
            val deal = Deck.deal(rng)
            val hand = deal.hands[0] + deal.bottom
            StandardAi.clearMemoForBenchmark()
            val t = measureNanoTime { StandardAi.playPlan(hand) }
            if (t > worst) worst = t
        }
        assertTrue("worst ${worst / 1_000_000}ms >= 200ms", worst < 200_000_000)
    }

    // ---------- C 系列：对局快照 ----------

    private fun playSomeMoves(g: DdzGame, moves: Int) {
        var done = 0
        while (g.phase == Phase.BIDDING) {
            val seat = g.currentBidder
            val minBid = g.highestBid + 1
            var b = StandardAi.decideBid(g.hands[seat], minBid)
            if (g.redealCount >= 3 && g.bidHistory.size == 2 && g.highestBid == 0) b = minBid
            g.bid(seat, if (b in minBid..3) b else 0)
        }
        while (g.phase == Phase.PLAYING && done < moves) {
            val seat = g.currentSeat
            val d = StandardAi.decidePlay(AiView.of(g, seat))
            if (d == null) g.pass(seat) else g.play(seat, d)
            done++
        }
    }

    /** C-01（逻辑层）：出牌中途快照→恢复，状态一致且可继续到正常结束。 */
    @Test fun c01_snapshotRoundtripAndContinue() {
        val g = DdzGame(Random(7))
        g.startRound()
        playSomeMoves(g, 10)
        assertEquals(Phase.PLAYING, g.phase)
        val snap = g.snapshot()
        val r = DdzGame.fromSnapshot(snap)
        assertNotNull(r)
        r!!
        assertEquals(g.phase, r.phase)
        assertEquals(g.landlordSeat, r.landlordSeat)
        assertEquals(g.currentSeat, r.currentSeat)
        assertEquals(g.bombCount, r.bombCount)
        assertEquals(g.currentTarget?.type, r.currentTarget?.type)
        assertEquals(g.currentTarget?.mainRank, r.currentTarget?.mainRank)
        for (i in 0..2) assertEquals(g.hands[i].map { it.id }.sorted(), r.hands[i].map { it.id }.sorted())
        assertEquals(g.playHistory.size, r.playHistory.size)
        // 恢复后继续打完
        var steps = 0
        while (r.phase == Phase.PLAYING) {
            val seat = r.currentSeat
            val d = StandardAi.decidePlay(AiView.of(r, seat))
            if (d == null) assertTrue(r.pass(seat)) else {
                assertNotNull(r.validatePlay(seat, d)); r.play(seat, d)
            }
            assertTrue(++steps <= 1000)
        }
        assertNotNull(r.result)
        assertEquals(0, r.result!!.scores.sum())
    }

    /** 叫分阶段同样可快照恢复。 */
    @Test fun c01b_snapshotDuringBidding() {
        val g = DdzGame(Random(11))
        g.startRound()
        assertEquals(Phase.BIDDING, g.phase)
        val r = DdzGame.fromSnapshot(g.snapshot())
        assertNotNull(r)
        assertEquals(Phase.BIDDING, r!!.phase)
        assertEquals(g.currentBidder, r.currentBidder)
    }

    /** C-03：版本不匹配/损坏的存档静默丢弃（返回 null）。 */
    @Test fun c03_invalidSnapshotsRejected() {
        val g = DdzGame(Random(7))
        g.startRound()
        val snap = g.snapshot()
        assertNull(DdzGame.fromSnapshot(snap.replace("v=${DdzGame.SNAPSHOT_VERSION}", "v=99")))
        assertNull(DdzGame.fromSnapshot("garbage"))
        assertNull(DdzGame.fromSnapshot(""))
        assertNull(DdzGame.fromSnapshot(snap.lines().filterNot { it.startsWith("hand1=") }.joinToString("\n")))
        // 手牌 id 重复（被另一家持有）应被拒绝
        val dup = snap.lines().joinToString("\n") { line ->
            if (line.startsWith("hand1=")) {
                val h0 = snap.lines().first { it.startsWith("hand0=") }.substringAfter("=")
                "hand1=" + h0
            } else line
        }
        assertNull(DdzGame.fromSnapshot(dup))
    }

    // ---------- H 系列：提示 ----------

    /** H-01：首出提示存在多个候选（拆分计划多手牌）。 */
    @Test fun h01_leadHintMultipleCandidates() {
        val deal = Deck.deal(Random(3))
        val plan = StandardAi.playPlan(deal.hands[0])
        assertTrue("plan should have multiple hands", plan.size >= 2)
        // 计划覆盖全部手牌且互不重叠
        val ids = plan.flatMap { c -> c.cards.map { it.id } }
        assertEquals(deal.hands[0].size, ids.size)
        assertEquals(deal.hands[0].map { it.id }.sorted(), ids.sorted())
        // 每手都是合法牌型
        for (c in plan) assertNotNull("illegal combo in plan", ComboParser.parse(c.cards))
    }

    /** H-02：跟牌提示普通解在前、炸弹/王炸在后。 */
    @Test fun h02_bombsAfterNormalSolutions() {
        fun cards(spec: String): List<Card> {
            var id = 2000
            val suitUse = HashMap<Int, Int>()
            return spec.split(",").map { tok ->
                val rank = when (val t = tok.trim()) {
                    "J" -> 11; "Q" -> 12; "K" -> 13; "A" -> 14; "2" -> 15; "SJ" -> 16; "BJ" -> 17
                    else -> t.toInt()
                }
                val suit = if (rank >= 16) -1 else (suitUse[rank] ?: 0).also { suitUse[rank] = it + 1 }
                Card(id++, rank, suit)
            }
        }
        val hand = cards("9,K,K,K,K,SJ,BJ,10")
        val target = ComboParser.parse(cards("8"))!!
        val opts = MoveGenerator.beats(hand, target)
        val firstBomb = opts.indexOfFirst { it.isBomb }
        val lastNormal = opts.indexOfLast { !it.isBomb }
        assertTrue(firstBomb > lastNormal)
        assertEquals(ComboType.ROCKET, opts.last().type)
    }

    /** A-05（结构性）：AiView 不暴露对手手牌，仅公开信息。编译期由类型保证，此处冒烟验证。 */
    @Test fun a05_aiViewExposesOnlyPublicInfo() {
        val g = DdzGame(Random(5))
        g.startRound()
        val v = AiView.of(g, 0)
        assertEquals(g.hands[0].map { it.id }, v.myHand.map { it.id })
        assertEquals(listOf(17, 17, 17), v.handCounts)
        // 叫分阶段底牌不可见
        assertTrue(v.bottom.isEmpty())
    }
}
