package com.playking.ddz

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playking.ddz.data.Prefs
import com.playking.ddz.data.Settings
import com.playking.ddz.data.Stats
import com.playking.ddz.engine.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Screen { MENU, GAME, SETTINGS, STATS }

/** 渲染用快照：每次引擎状态变化后重建。 */
data class TableUi(
    val phase: Phase = Phase.DEALING,
    val myHand: List<Card> = emptyList(),
    val aiHandCounts: List<Int> = listOf(17, 17),          // seat1(右上/下家), seat2(左上/上家)
    val bottom: List<Card> = emptyList(),
    val bottomRevealed: Boolean = false,
    val landlordSeat: Int = -1,
    val currentSeat: Int = -1,
    val currentBidder: Int = -1,
    val availableBids: List<Int> = emptyList(),
    val highestBid: Int = 0,
    val multiplier: Int = 1,
    /** 中央出牌区：每座位最近动作。null=本轮未行动；空列表=不出。 */
    val table: List<List<Card>?> = listOf(null, null, null),
    val canLeadFreely: Boolean = false,                    // 我是首出
    val mustBeat: Boolean = false,                          // 我在跟牌
    val noBeatAvailable: Boolean = false,                   // 检测到要不起
    val result: RoundResult? = null,
    val bidLabels: List<String?> = listOf(null, null, null) // 叫分气泡
)

class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val game = DdzGame(Random.Default)

    var screen by mutableStateOf(Screen.MENU)
    var settings by mutableStateOf(prefs.loadSettings()); private set
    var stats by mutableStateOf(prefs.loadStats()); private set
    var ui by mutableStateOf(TableUi()); private set
    var selectedIds by mutableStateOf(setOf<Int>()); private set
    var showQuitDialog by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    private var aiJob: Job? = null
    private val tableCards = arrayOfNulls<List<Card>>(3)
    private val bidLabels = arrayOfNulls<String>(3)
    private var hintList: List<Combo> = emptyList()
    private var hintIndex = -1
    private var statsCounted = false

    // ---------- 导航 ----------

    fun startGame() {
        screen = Screen.GAME
        newRound()
    }

    fun requestQuit() {
        if (ui.phase == Phase.PLAYING || ui.phase == Phase.BIDDING) showQuitDialog = true
        else backToMenu()
    }

    fun confirmQuit() { // 本局作废，不计战绩
        showQuitDialog = false
        backToMenu()
    }

    fun backToMenu() {
        aiJob?.cancel()
        screen = Screen.MENU
    }

    fun updateSettings(s: Settings) {
        settings = s
        prefs.saveSettings(s)
    }

    fun clearStats() {
        stats = Stats()
        prefs.clearStats()
    }

    // ---------- 对局驱动 ----------

    fun newRound() {
        aiJob?.cancel()
        for (i in 0..2) { tableCards[i] = null; bidLabels[i] = null }
        selectedIds = emptySet()
        hintIndex = -1
        statsCounted = false
        game.startRound()
        refresh()
        driveAi()
    }

    private fun refresh() {
        val phase = game.phase
        val target = game.currentTarget
        val myTurn = phase == Phase.PLAYING && game.currentSeat == 0
        val beats = if (myTurn && target != null) MoveGenerator.beats(game.hands[0], target) else emptyList()
        ui = TableUi(
            phase = phase,
            myHand = game.hands[0].toList(),
            aiHandCounts = listOf(game.hands[1].size, game.hands[2].size),
            bottom = game.bottom,
            bottomRevealed = phase == Phase.PLAYING || phase == Phase.FINISHED,
            landlordSeat = game.landlordSeat,
            currentSeat = game.currentSeat,
            currentBidder = game.currentBidder,
            availableBids = if (phase == Phase.BIDDING) game.availableBids() else emptyList(),
            highestBid = game.highestBid,
            multiplier = game.currentMultiplier(),
            table = listOf(tableCards[0], tableCards[1], tableCards[2]),
            canLeadFreely = myTurn && target == null,
            mustBeat = myTurn && target != null,
            noBeatAvailable = myTurn && target != null && beats.isEmpty(),
            result = game.result,
            bidLabels = listOf(bidLabels[0], bidLabels[1], bidLabels[2])
        )
    }

    /** AI 协程：处理所有非真人回合（含叫分），0.5~1.5s 随机思考延迟。 */
    private fun driveAi() {
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            while (true) {
                when (game.phase) {
                    Phase.BIDDING -> {
                        if (game.currentBidder == 0) return@launch
                        delay(Random.nextLong(500, 1500))
                        val seat = game.currentBidder
                        val minBid = game.highestBid + 1
                        val b = RuleAi.decideBid(game.hands[seat], minBid)
                        val score = if (b in minBid..3) b else 0
                        val redealBefore = game.redealCount
                        game.bid(seat, score)
                        if (game.redealCount > redealBefore) {
                            // 流局重发
                            for (i in 0..2) bidLabels[i] = null
                            toast = "三家不叫，重新发牌"
                        } else {
                            bidLabels[seat] = if (score == 0) "不叫" else "${score}分"
                        }
                        refresh()
                    }
                    Phase.PLAYING -> {
                        if (game.currentSeat == 0) return@launch
                        delay(Random.nextLong(500, 1500))
                        val seat = game.currentSeat
                        val decision = RuleAi.decidePlay(seat, game)
                        if (decision == null) {
                            applyPass(seat)
                        } else {
                            applyPlay(seat, decision)
                        }
                        refresh()
                    }
                    else -> return@launch
                }
            }
        }
    }

    private fun applyPlay(seat: Int, cards: List<Card>) {
        val leading = game.currentTarget == null
        if (game.play(seat, cards)) {
            if (leading) { for (i in 0..2) tableCards[i] = null }
            tableCards[seat] = cards
            if (game.phase == Phase.FINISHED) onFinished()
        }
    }

    private fun applyPass(seat: Int) {
        val ownerBefore = game.targetOwner
        if (game.pass(seat)) {
            tableCards[seat] = emptyList()
            // 一轮结束：保留赢家的牌、清掉过牌标记
            if (game.currentTarget == null) {
                for (i in 0..2) if (i != ownerBefore) tableCards[i] = null
            }
        }
    }

    private fun onFinished() {
        val res = game.result ?: return
        if (statsCounted) return
        statsCounted = true
        val iWon = res.scores[0] > 0
        val wasLandlord = res.landlordSeat == 0
        val newStreak = if (iWon) stats.curStreak + 1 else 0
        stats = stats.copy(
            total = stats.total + 1,
            wins = stats.wins + if (iWon) 1 else 0,
            losses = stats.losses + if (iWon) 0 else 1,
            landlordGames = stats.landlordGames + if (wasLandlord) 1 else 0,
            landlordWins = stats.landlordWins + if (wasLandlord && iWon) 1 else 0,
            curStreak = newStreak,
            bestStreak = maxOf(stats.bestStreak, newStreak)
        )
        prefs.saveStats(stats)
    }

    // ---------- 真人操作 ----------

    fun toggleCard(id: Int) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        hintIndex = -1
    }

    fun humanBid(score: Int) {
        if (game.phase != Phase.BIDDING || game.currentBidder != 0) return
        val redealBefore = game.redealCount
        game.bid(0, score)
        if (game.redealCount > redealBefore) {
            for (i in 0..2) bidLabels[i] = null
            toast = "三家不叫，重新发牌"
        } else {
            bidLabels[0] = if (score == 0) "不叫" else "${score}分"
        }
        refresh()
        driveAi()
    }

    fun humanPlay() {
        if (game.phase != Phase.PLAYING || game.currentSeat != 0) return
        val cards = game.hands[0].filter { it.id in selectedIds }
        if (cards.isEmpty()) { toast = "请选择要出的牌"; return }
        val combo = game.validatePlay(0, cards)
        if (combo == null) {
            toast = if (ComboParser.parse(cards) == null) "不是合法牌型" else "压不过上家"
            return
        }
        applyPlay(0, cards)
        selectedIds = emptySet()
        hintIndex = -1
        refresh()
        driveAi()
    }

    fun humanPass() {
        if (game.phase != Phase.PLAYING || game.currentSeat != 0) return
        if (game.currentTarget == null) { toast = "首出不能不出"; return }
        applyPass(0)
        selectedIds = emptySet()
        hintIndex = -1
        refresh()
        driveAi()
    }

    /** 提示：自动选中能压过上家的最小一手；连续点击轮换；无解提示要不起。 */
    fun hint() {
        if (game.phase != Phase.PLAYING || game.currentSeat != 0) return
        val target = game.currentTarget
        if (hintIndex == -1) {
            hintList = if (target == null) {
                // 首出提示：按 AI 首出策略给一手
                RuleAi.decidePlay(0, game)?.let { cards -> ComboParser.parse(cards)?.let { listOf(it) } } ?: emptyList()
            } else MoveGenerator.beats(game.hands[0], target)
        }
        if (hintList.isEmpty()) { toast = "要不起"; return }
        hintIndex = (hintIndex + 1) % hintList.size
        selectedIds = hintList[hintIndex].cards.map { it.id }.toSet()
    }

    fun consumeToast() { toast = null }
}
