package com.playking.ddz.data

import android.content.Context
import android.content.SharedPreferences

const val DIFF_EASY = "easy"
const val DIFF_STANDARD = "std"

data class Stats(
    val total: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val landlordGames: Int = 0,
    val landlordWins: Int = 0,
    val bestStreak: Int = 0,
    val curStreak: Int = 0,
    // v2 扩展
    val totalScore: Int = 0,
    val bombs: Int = 0,
    val springs: Int = 0
) {
    val landlordWinRate: Int get() = if (landlordGames == 0) 0 else landlordWins * 100 / landlordGames
}

data class Settings(
    val soundEnabled: Boolean = true,
    val hintEnabled: Boolean = true,
    // v2 扩展
    val voiceEnabled: Boolean = true,
    val countdownSec: Int = 0,           // 0=关 / 15 / 30
    val counterEnabled: Boolean = false, // 记牌器
    val difficulty: String = DIFF_STANDARD
)

/** 对局历史（最近 50 局）。 */
data class HistoryEntry(
    val time: Long,
    val wasLandlord: Boolean,
    val difficulty: String,
    val multiplier: Int,
    val score: Int,
    val win: Boolean
)

/** 本地战绩、设置、对局历史与存档（SharedPreferences）。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("playking", Context.MODE_PRIVATE)

    init { migrateV1() }

    /** v1 无前缀数据迁移至"简单"难度（v2 需求第 7 章）。 */
    private fun migrateV1() {
        if (sp.contains("total") && !sp.contains("${DIFF_EASY}_total")) {
            val e = sp.edit()
            for (k in listOf("total", "wins", "losses", "llGames", "llWins", "bestStreak", "curStreak")) {
                e.putInt("${DIFF_EASY}_$k", sp.getInt(k, 0)).remove(k)
            }
            e.apply()
        }
    }

    // ---------- 战绩（按难度分账） ----------

    fun loadStats(diff: String) = Stats(
        total = sp.getInt("${diff}_total", 0),
        wins = sp.getInt("${diff}_wins", 0),
        losses = sp.getInt("${diff}_losses", 0),
        landlordGames = sp.getInt("${diff}_llGames", 0),
        landlordWins = sp.getInt("${diff}_llWins", 0),
        bestStreak = sp.getInt("${diff}_bestStreak", 0),
        curStreak = sp.getInt("${diff}_curStreak", 0),
        totalScore = sp.getInt("${diff}_score", 0),
        bombs = sp.getInt("${diff}_bombs", 0),
        springs = sp.getInt("${diff}_springs", 0)
    )

    fun saveStats(diff: String, s: Stats) {
        sp.edit()
            .putInt("${diff}_total", s.total).putInt("${diff}_wins", s.wins).putInt("${diff}_losses", s.losses)
            .putInt("${diff}_llGames", s.landlordGames).putInt("${diff}_llWins", s.landlordWins)
            .putInt("${diff}_bestStreak", s.bestStreak).putInt("${diff}_curStreak", s.curStreak)
            .putInt("${diff}_score", s.totalScore).putInt("${diff}_bombs", s.bombs)
            .putInt("${diff}_springs", s.springs)
            .apply()
    }

    fun clearStats() {
        saveStats(DIFF_EASY, Stats())
        saveStats(DIFF_STANDARD, Stats())
        sp.edit().remove("history").apply()
    }

    // ---------- 设置 ----------

    fun loadSettings() = Settings(
        soundEnabled = sp.getBoolean("sound", true),
        hintEnabled = sp.getBoolean("hint", true),
        voiceEnabled = sp.getBoolean("voice", true),
        countdownSec = sp.getInt("countdown", 0),
        counterEnabled = sp.getBoolean("counter", false),
        difficulty = sp.getString("difficulty", DIFF_STANDARD) ?: DIFF_STANDARD
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putBoolean("sound", s.soundEnabled).putBoolean("hint", s.hintEnabled)
            .putBoolean("voice", s.voiceEnabled).putInt("countdown", s.countdownSec)
            .putBoolean("counter", s.counterEnabled).putString("difficulty", s.difficulty)
            .apply()
    }

    // ---------- 对局历史（最近 50 局，新在前） ----------

    fun loadHistory(): List<HistoryEntry> {
        val raw = sp.getString("history", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val f = line.split("|")
            if (f.size != 6) return@mapNotNull null
            try {
                HistoryEntry(f[0].toLong(), f[1] == "1", f[2], f[3].toInt(), f[4].toInt(), f[5] == "1")
            } catch (e: Exception) { null }
        }
    }

    fun addHistory(e: HistoryEntry) {
        val list = (listOf(e) + loadHistory()).take(50)
        val raw = list.joinToString("\n") {
            "${it.time}|${if (it.wasLandlord) 1 else 0}|${it.difficulty}|${it.multiplier}|${it.score}|${if (it.win) 1 else 0}"
        }
        sp.edit().putString("history", raw).apply()
    }

    // ---------- 对局存档（单份覆盖式） ----------

    fun saveGame(snapshot: String, difficulty: String) {
        sp.edit().putString("save_game", snapshot).putString("save_diff", difficulty).apply()
    }

    fun loadGame(): Pair<String, String>? {
        val s = sp.getString("save_game", null) ?: return null
        return s to (sp.getString("save_diff", DIFF_STANDARD) ?: DIFF_STANDARD)
    }

    fun clearGame() {
        sp.edit().remove("save_game").remove("save_diff").apply()
    }
}
