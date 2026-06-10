# PlayKing · 单机斗地主（原生 Android / Kotlin）

按《斗地主单机版需求文档 v0.2》与《v2 需求文档 v0.1》实现的经典三人斗地主：玩家 1 人 + AI 2 人，叫分制，横屏。

## 工程结构

```
app/src/main/java/com/playking/ddz/
├── engine/                纯 Kotlin 规则引擎与 AI（零 Android 依赖，可独立测试）
│   ├── Cards.kt           牌、点数编码、Fisher–Yates 洗牌、发牌
│   ├── Combo.kt           14 种牌型判定（ComboParser）+ 压牌比较（Rules.canBeat）
│   ├── Moves.kt           走法生成（提示与 AI 共用；普通解在前、炸弹/王炸在后）
│   ├── Game.kt            流程状态机 + 对局快照 snapshot/fromSnapshot（v2 恢复）
│   ├── AiView.kt          AI 信息边界（A-05）：只暴露公开信息 + 自己手牌，含记牌推断工具
│   ├── Ai.kt              v1 规则型 AI（"简单"难度，保留）
│   └── StandardAi.kt      v2 标准 AI：记忆化搜索拆牌 + 记牌 + 必胜路径 + 配合强化
├── data/Prefs.kt          战绩(按难度分账+v1迁移)、设置、最近50局历史、对局存档
├── GameViewModel.kt       对局驱动：难度、发牌动画、倒计时、提示轮换、恢复、音效事件
├── MainActivity.kt        入口（横屏沉浸式、刘海屏适配）
└── ui/
    ├── Screens.kt         Compose UI：主界面/对局/结算/设置/战绩+历史、记牌器、动画
    └── SoundFx.kt         SoundPool 音效 + 语音播报接口（语音资源按约定命名放入 res/raw）

app/src/main/res/raw/      合成音效（出牌/发牌/炸弹/王炸/胜/负）
app/src/test/java/         EngineTest.kt（v1 回归）+ EngineTestV2.kt（v2 验收）
```

## 构建

1. 用 Android Studio（Koala+）打开本目录，等待 Gradle 同步（如提示缺 wrapper 按提示生成）。
2. 运行 `app`（minSdk 24，横屏）；或推送到 GitHub 由 Actions 构建 APK（见 `.github/workflows/build-apk.yml`）。
3. 单元测试：`./gradlew test`。

## v2 验收对照（v2 需求文档第 9 章，引擎级已在开发环境实测）

| 编号 | 结果 |
|---|---|
| A-02 标准 AI vs v1×2 千局 | ✅ 实测 60.5%（对照基线 50.6%；反向 v1 对标准×2 仅 36.8%） |
| A-03 标准 AI 千局 | ✅ 零非法、零死循环、得分守恒 |
| A-04 地主胜率护栏 | ✅ 实测 57%（40%~70% 区间） |
| A-05 信息边界 | ✅ AI 仅能通过 AiView 访问公开信息+自己手牌（类型层约束） |
| C-01/C-01b/C-03 快照 | ✅ 中局/叫分阶段恢复一致并可打完；版本不符/损坏/重复手牌静默拒绝 |
| H-01 首出多候选 | ✅ 拆分计划多手轮换，计划覆盖全部手牌且每手合法 |
| H-02 普通解先于炸弹 | ✅ MoveGenerator 排序保证 |
| T-01 倒计时 | UI 层实现（15s/30s/关，超时自动不出/出最小手），需真机自验 |
| P-01 拆牌耗时 | ✅ 20 张冷缓存最坏 <1ms（JVM） |
| R-回归 | ✅ v1 全部 41 项断言原样通过，统计逐位一致 |

## v2 功能说明

- **难度**：设置中切换 简单（v1 贪心 AI）/ 标准（默认）；战绩按难度分账，v1 旧数据自动迁移到"简单"。
- **对局恢复**：每步自动存档；杀进程后启动弹窗"继续上局/放弃"；放弃或正常结算即清档，战绩不重复计。
- **记牌器**：设置开启后对局顶部显示对手手中各点数剩余张数（与 AI 同源的公开信息推算）。
- **倒计时**：默认关；超时跟牌自动"不出"、首出自动打出提示的最小一手。
- **动画**：发牌逐张（可点击跳过）、出牌区 Crossfade、结算弹性入场。
- **音效**：内置合成音效；**语音播报为预留接口**——将真人录音按 `voice_single.wav`、`voice_pair.wav`、`voice_triple.wav`、`voice_triple_one.wav`、`voice_triple_pair.wav`、`voice_straight.wav`、`voice_pair_chain.wav`、`voice_plane.wav`、`voice_four_two.wav`、`voice_bomb.wav`、`voice_rocket.wav` 命名放入 `res/raw/` 即自动生效（当前未附带录音素材，开关存在但无声）。

## 关键规则裁定的实现位置（v1，未变更）

- `33344` → 三带对；`333444` → 飞机不带（无三带三）；飞机翅膀可含 2/王、按最大飞机解释；
  四带二单允许两单同点；炸弹拆牌不限制；先叫分者 = 1~10 随机数 `(r-1) mod 3`。
