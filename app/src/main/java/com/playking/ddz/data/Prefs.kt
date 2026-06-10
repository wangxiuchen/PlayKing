package com.playking.ddz.data

import android.content.Context
import android.content.SharedPreferences

data class Stats(
    val total: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val landlordGames: Int = 0,
    val landlordWins: Int = 0,
    val bestStreak: Int = 0,
    val curStreak: Int = 0
) {
    val landlordWinRate: Int get() = if (landlordGames == 0) 0 else landlordWins * 100 / landlordGames
}

data class Settings(
    val soundEnabled: Boolean = true,
    val hintEnabled: Boolean = true
)

/** 本地战绩与设置持久化（SharedPreferences）。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("playking", Context.MODE_PRIVATE)

    fun loadStats() = Stats(
        total = sp.getInt("total", 0),
        wins = sp.getInt("wins", 0),
        losses = sp.getInt("losses", 0),
        landlordGames = sp.getInt("llGames", 0),
        landlordWins = sp.getInt("llWins", 0),
        bestStreak = sp.getInt("bestStreak", 0),
        curStreak = sp.getInt("curStreak", 0)
    )

    fun saveStats(s: Stats) {
        sp.edit()
            .putInt("total", s.total).putInt("wins", s.wins).putInt("losses", s.losses)
            .putInt("llGames", s.landlordGames).putInt("llWins", s.landlordWins)
            .putInt("bestStreak", s.bestStreak).putInt("curStreak", s.curStreak)
            .apply()
    }

    fun clearStats() = saveStats(Stats())

    fun loadSettings() = Settings(
        soundEnabled = sp.getBoolean("sound", true),
        hintEnabled = sp.getBoolean("hint", true)
    )

    fun saveSettings(s: Settings) {
        sp.edit().putBoolean("sound", s.soundEnabled).putBoolean("hint", s.hintEnabled).apply()
    }
}
