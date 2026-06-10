package com.playking.ddz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.playking.ddz.R
import com.playking.ddz.engine.ComboType

/** 音效与语音播报。音效为内置合成资源；语音为预留接口（放入 res/raw/voice_*.wav 即生效）。 */
object SoundFx {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val voiceIds = HashMap<String, Int>()

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool = p
        ids["play"] = p.load(context, R.raw.fx_play, 1)
        ids["deal"] = p.load(context, R.raw.fx_deal, 1)
        ids["bomb"] = p.load(context, R.raw.fx_bomb, 1)
        ids["rocket"] = p.load(context, R.raw.fx_rocket, 1)
        ids["win"] = p.load(context, R.raw.fx_win, 1)
        ids["lose"] = p.load(context, R.raw.fx_lose, 1)
        loadVoices(context, p)
    }

    /** 牌型语音（P2）：按命名约定查找预录音频资源，缺失则静默跳过。 */
    @SuppressLint("DiscouragedApi")
    private fun loadVoices(context: Context, p: SoundPool) {
        for (name in VOICE_NAMES.values.distinct()) {
            val resId = context.resources.getIdentifier("voice_$name", "raw", context.packageName)
            if (resId != 0) voiceIds[name] = p.load(context, resId, 1)
        }
    }

    fun play(kind: String, enabled: Boolean) {
        if (!enabled) return
        ids[kind]?.let { pool?.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun announce(type: ComboType?, enabled: Boolean) {
        if (!enabled || type == null) return
        val name = VOICE_NAMES[type] ?: return
        voiceIds[name]?.let { pool?.play(it, 1f, 1f, 2, 0, 1f) }
    }

    private val VOICE_NAMES = mapOf(
        ComboType.SINGLE to "single",
        ComboType.PAIR to "pair",
        ComboType.TRIPLE to "triple",
        ComboType.TRIPLE_ONE to "triple_one",
        ComboType.TRIPLE_PAIR to "triple_pair",
        ComboType.STRAIGHT to "straight",
        ComboType.PAIR_CHAIN to "pair_chain",
        ComboType.PLANE to "plane",
        ComboType.PLANE_SINGLE to "plane",
        ComboType.PLANE_PAIR to "plane",
        ComboType.FOUR_TWO_SINGLE to "four_two",
        ComboType.FOUR_TWO_PAIR to "four_two",
        ComboType.BOMB to "bomb",
        ComboType.ROCKET to "rocket"
    )
}
