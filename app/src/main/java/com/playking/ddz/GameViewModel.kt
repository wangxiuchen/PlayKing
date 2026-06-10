package com.playking.ddz

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playking.ddz.data.DIFF_EASY
import com.playking.ddz.data.DIFF_STANDARD
import com.playking.ddz.data.HistoryEntry
import com.playking.ddz.data.Prefs
import com.playking.ddz.data.Settings
import com.playking.ddz.data.Stats
import com.playking.ddz.engine.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Screen { MENU, GAME, SETTINGS, STATS }

/** 音效/语音事件（UI 层消费）。 */
data class FxEvent(val id: Long, val kind: String, val comboType: ComboType? = null)

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
    val table: List<List<Card>?> = listOf(null, null, null),
    val canLeadFreely: Boolean = false,
    val mustBeat: Boolean = false,
    val noBeatAvailable: Boolean = false,
    val result: RoundResult? = null,
    val bidLabels: List<String?> = listOf(null, null, null),
    /** 记牌器：点数 3..大王 在对手手中的剩余张数；null=未开启。 */
    val counter: List<Int>? = null
)

class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private var game = DdzGame(Random.Default)

    var screen by mutableStateOf(Screen.MENU)
    var settings by mutableStateOf(prefs.loadSettings()); private set
    var statsEasy by mutableStateOf(prefs.loadStats(DIFF_EASY)); private set
    var statsStd by mutableStateOf(prefs.loadStats(DIFF_STANDARD)); private set
    var history by mutableStateOf(prefs.loadHistory()); private set
    var ui by mutableStateOf(TableUi()); private set
    var selectedIds by mutableStateOf(setOf<Int>()); private set
    var showQuitDialog by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var fx by mutableStateOf<FxEvent?>(null); private set

    /** 发牌动画：当前已发到第几张（17 为完成）。 */
    var dealt by mutableStateOf(17); private set
    val dealing: Boolean get() = dealt < 17

    /** 倒计时剩余秒数；null=无。 */
    var timeLeft by mutableStateOf<Int?>(null); private set

    /** 启动时检测到的未完成对局存档。 */
    var hasSavedGame by mutableStateOf(false); private set

    private var aiJob: Job? = null
    private var dealJob: Job? = null
    private var countdownJob: Job? = null
    private val tableCards = arrayOfNulls<List<Card>>(3)
    private val bidLabels = arrayOfNulls<String>(3)
    private var hintList: List<Combo> = emptyList()
    private var hintIndex = -1
    private var statsCounted = false
    private var roundDiff = settings.difficulty

    init {
        // C-01：检测存档（能成功解析才提示恢复）
        hasSavedGame = prefs.loadGame()?.let { DdzGame.fromSnapshot(it.first) != null } ?: false
    }

    // ---------- 导航 ----------

    fun startGame() {
        screen = Screen.GAME
        newRound()
    }

    /** 恢复存档对局（C-01）。 */
    fun resumeSavedGame() {
        val (snap, diff) = prefs.loadGame() ?: run { hasSavedGame = false; return }
        val g = DdzGame.fromSnapshot(snap) ?: run { hasSavedGame = false; prefs.clearGame(); return }
        hasSavedGame = false
        game = g
        roundDiff = diff
        resetRoundUiState()
        dealt = 17
        // 重建中央出牌区与叫分气泡
        rebuildTableFromHistory()
        screen = Screen.GAME
        refresh()
        driveAi()
    }

    /** 放弃存档（C-02：作废不计战绩）。 */
    fun discardSavedGame() {
        prefs.clearGame()
        hasSavedGame = false
    }

    fun requestQuit() {
        if (ui.phase == Phase.PLAYING || ui.phase == Phase.BIDDING) showQuitDialog = true
        else backToMenu()
    }

    fun confirmQuit() { // 本局作废，不计战绩，清除存档
        showQuitDialog = false
        prefs.clearGame()
        backToMenu()
    }

    fun backToMenu() {
        aiJob?.cancel(); dealJob?.cancel(); countdownJob?.cancel()
        timeLeft = null
        screen = Screen.MENU
    }

    fun updateSettings(s: Settings) {
        settings = s
        prefs.saveSettings(s)
    }

    fun clearStats() {
        prefs.clearStats()
        statsEasy = Stats(); statsStd = Stats()
        history = emptyList()
    }

    // ---------- 对局驱动 ----------

    private fun resetRoundUiState() {
        aiJob?.cancel(); dealJob?.cancel(); countdownJob?.cancel()
        timeLeft = null
        for (i in 0..2) { tableCards[i] = null; bidLabels[i] = null }
        selectedIds = emptySet()
        hintIndex = -1
        statsCounted = false
    }

    fun newRound() {
        resetRoundUiState()
        roundDiff = settings.difficulty
        game.startRound()
        persist()
        // 发牌动画（可跳过）
        dealt = 0
        refresh()
        dealJob = viewModelScope.launch {
            for (i in 1..17) { delay(55); dealt = i }
            refresh()
            driveAi()
        }
    }

    fun skipDeal() {
        if (!dealing) return
        dealJob?.cancel()
        dealt = 17
        refresh()
        driveAi()
    }

    /** 恢复时根据历史重建桌面显示（当前一轮的最近动作）。 */
    private fun rebuildTableFromHistory() {
        val h = game.playHistory
        // 从尾部回放本轮：到上一轮赢家首出为止
        var i = h.size - 1
        val seen = HashSet<Int>()
        while (i >= 0 && seen.size < 3) {
            val e = h[i]
            if (e.seat !in seen) {
                seen.add(e.seat)
                tableCards[e.seat] = e.combo?.cards ?: emptyList()
            }
            if (e.combo != null && e.seat == game.targetOwner) break
            i--
        }
        if (game.currentTarget == null) { for (s in 0..2) tableCards[s] = null }
        if (game.phase == Phase.BIDDING) {
            for ((seat, score) in game.bidHistory) bidLabels[seat] = if (score == 0) "不叫" else "${score}分"
        }
    }

    private fun persist() {
        if (game.phase == Phase.BIDDING || game.phase == Phase.PLAYING) {
            prefs.saveGame(game.snapshot(), roundDiff)
        } else {
            prefs.clearGame()
        }
    }

    private fun counterCounts(): List<Int>? {
        if (!settings.counterEnabled || game.phase != Phase.PLAYING) return null
        val played = IntArray(Rank.BIG_JOKER + 1)
        for (e in game.playHistory) e.combo?.cards?.forEach { played[it.rank]++ }
        val mine = IntArray(Rank.BIG_JOKER + 1)
        for (c in game.hands[0]) mine[c.rank]++
        return (Rank.THREE..Rank.BIG_JOKER).map { r ->
            val total = if (r >= Rank.SMALL_JOKER) 1 else 4
            (total - played[r] - mine[r]).coerceAtLeast(0)
        }
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
            bidLabels = listOf(bidLabels[0], bidLabels[1], bidLabels[2]),
            counter = counterCounts()
        )
        restartCountdown(myTurn)
    }

    // ---------- 倒计时（T-01） ----------

    private fun restartCountdown(myPlayTurn: Boolean) {
        countdownJob?.cancel()
        timeLeft = null
        val secs = settings.countdownSec
        if (secs <= 0 || !myPlayTurn || dealing || game.result != null) return
        countdownJob = viewModelScope.launch {
            var t = secs
            while (t > 0) {
                timeLeft = t
                delay(1000)
                t--
            }
            timeLeft = null
            onTimeout()
        }
    }

    private fun onTimeout() {
        if (game.phase != Phase.PLAYING || game.currentSeat != 0) return
        if (game.currentTarget != null) {
            humanPass()
        } else {
            // 首出超时：自动出提示的最小一手
            val cards = leadHintCandidates().firstOrNull()?.cards
                ?: game.hands[0].minByOrNull { it.rank }?.let { listOf(it) } ?: return
            selectedIds = cards.map { it.id }.toSet()
            humanPlay()
        }
    }

    // ---------- AI 协程 ----------

    private fun aiBid(seat: Int): Int {
        val minBid = game.highestBid + 1
        val b = if (roundDiff == DIFF_STANDARD) StandardAi.decideBid(game.hands[seat], minBid)
        else RuleAi.decideBid(game.hands[seat], minBid)
        return if (b in minBid..3) b else 0
    }

    private fun aiPlay(seat: Int): List<Card>? =
        if (roundDiff == DIFF_STANDARD) StandardAi.decidePlay(AiView.of(game, seat))
        else RuleAi.decidePlay(AiView.of(game, seat))

    private fun driveAi() {
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            while (true) {
                when (game.phase) {
                    Phase.BIDDING -> {
                        if (game.currentBidder == 0 || dealing) return@launch
                        delay(Random.nextLong(500, 1500))
                        val seat = game.currentBidder
                        val score = aiBid(seat)
                        val redealBefore = game.redealCount
                        game.bid(seat, score)
                        if (game.redealCount > redealBefore) {
                            for (i in 0..2) bidLabels[i] = null
                            toast = "三家不叫，重新发牌"
                        } else {
                            bidLabels[seat] = if (score == 0) "不叫" else "${score}分"
                        }
                        persist()
                        refresh()
                    }
                    Phase.PLAYING -> {
                        if (game.currentSeat == 0) return@launch
                        delay(Random.nextLong(500, 1500))
                        val seat = game.currentSeat
                        val decision = aiPlay(seat)
                        if (decision == null) applyPass(seat) else applyPlay(seat, decision)
                        persist()
                        refresh()
                    }
                    else -> return@launch
                }
            }
        }
    }

    private fun applyPlay(seat: Int, cards: List<Card>) {
        val leading = game.currentTarget == null
        val combo = game.validatePlay(seat, cards) ?: return
        if (game.play(seat, cards)) {
            if (leading) { for (i in 0..2) tableCards[i] = null }
            tableCards[seat] = cards
            emitFx(
                when (combo.type) {
                    ComboType.ROCKET -> "rocket"
                    ComboType.BOMB -> "bomb"
                    else -> "play"
                }, combo.type
            )
            if (game.phase == Phase.FINISHED) onFinished()
        }
    }

    private fun applyPass(seat: Int) {
        val ownerBefore = game.targetOwner
        if (game.pass(seat)) {
            tableCards[seat] = emptyList()
            if (game.currentTarget == null) {
                for (i in 0..2) if (i != ownerBefore) tableCards[i] = null
            }
        }
    }

    private fun emitFx(kind: String, type: ComboType? = null) {
        fx = FxEvent(System.nanoTime(), kind, type)
    }

    private fun onFinished() {
        val res = game.result ?: return
        if (statsCounted) return
        statsCounted = true
        prefs.clearGame()
        val iWon = res.scores[0] > 0
        val wasLandlord = res.landlordSeat == 0
        val old = if (roundDiff == DIFF_STANDARD) statsStd else statsEasy
        val newStreak = if (iWon) old.curStreak + 1 else 0
        val updated = old.copy(
            total = old.total + 1,
            wins = old.wins + if (iWon) 1 else 0,
            losses = old.losses + if (iWon) 0 else 1,
            landlordGames = old.landlordGames + if (wasLandlord) 1 else 0,
            landlordWins = old.landlordWins + if (wasLandlord && iWon) 1 else 0,
            curStreak = newStreak,
            bestStreak = maxOf(old.bestStreak, newStreak),
            totalScore = old.totalScore + res.scores[0],
            bombs = old.bombs + res.bombCount,
            springs = old.springs + if (res.spring) 1 else 0
        )
        if (roundDiff == DIFF_STANDARD) statsStd = updated else statsEasy = updated
        prefs.saveStats(roundDiff, updated)
        prefs.addHistory(
            HistoryEntry(
                time = System.currentTimeMillis(),
                wasLandlord = wasLandlord,
                difficulty = roundDiff,
                multiplier = res.multiplier,
                score = res.scores[0],
                win = iWon
            )
        )
        history = prefs.loadHistory()
        emitFx(if (iWon) "win" else "lose")
    }

    // ---------- 真人操作 ----------

    fun toggleCard(id: Int) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        hintIndex = -1
    }

    fun humanBid(score: Int) {
        if (game.phase != Phase.BIDDING || game.currentBidder != 0 || dealing) return
        val redealBefore = game.redealCount
        game.bid(0, score)
        if (game.redealCount > redealBefore) {
            for (i in 0..2) bidLabels[i] = null
            toast = "三家不叫，重新发牌"
        } else {
            bidLabels[0] = if (score == 0) "不叫" else "${score}分"
        }
        persist()
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
        countdownJob?.cancel(); timeLeft = null
        applyPlay(0, cards)
        selectedIds = emptySet()
        hintIndex = -1
        persist()
        refresh()
        driveAi()
    }

    fun humanPass() {
        if (game.phase != Phase.PLAYING || game.currentSeat != 0) return
        if (game.currentTarget == null) { toast = "首出不能不出"; return }
        countdownJob?.cancel(); timeLeft = null
        applyPass(0)
        selectedIds = emptySet()
        hintIndex = -1
        persist()
        refresh()
        driveAi()
    }

    /** 首出提示候选（H-01）：标准拆分计划，小牌型在前，炸弹最后。 */
    private fun leadHintCandidates(): List<Combo> {
        val plan = StandardAi.playPlan(game.hands[0])
        val order: (Combo) -> Int = {
            when (it.type) {
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
        return plan.sortedWith(compareBy({ if (it.isBomb) 1 else 0 }, order, { it.mainRank }))
    }

    /**
     * 提示（H-01/H-02）：跟牌时从小到大轮换、普通解优先炸弹最后（MoveGenerator 已排序）；
     * 首出时轮换拆分计划的多个候选；无解提示"要不起"。
     */
    fun hint() {
        if (game.phase != Phase.PLAYING || game.currentSeat != 0) return
        val target = game.currentTarget
        if (hintIndex == -1) {
            hintList = if (target == null) leadHintCandidates()
            else MoveGenerator.beats(game.hands[0], target)
        }
        if (hintList.isEmpty()) { toast = "要不起"; return }
        hintIndex = (hintIndex + 1) % hintList.size
        selectedIds = hintList[hintIndex].cards.map { it.id }.toSet()
    }

    fun consumeToast() { toast = null }
    fun consumeFx() { fx = null }
}
