# PlayKing · 单机斗地主（原生 Android / Kotlin）

按《斗地主单机版需求文档 v0.2》实现的经典三人斗地主：玩家 1 人 + 规则型 AI 2 人，叫分制，横屏。

## 工程结构

```
app/src/main/java/com/playking/ddz/
├── engine/              纯 Kotlin 规则引擎（零 Android 依赖，可独立测试）
│   ├── Cards.kt         牌、点数编码、Fisher–Yates 洗牌、发牌
│   ├── Combo.kt         14 种牌型判定（ComboParser）+ 压牌比较（Rules.canBeat）
│   ├── Moves.kt         走法生成（提示功能与 AI 共用）
│   ├── Game.kt          流程状态机：发牌→叫分(1~10点数定先叫/流局重发)→出牌→结算
│   └── Ai.kt            规则型 AI：叫分估值、手牌拆分、最小跟牌、炸弹时机、农民配合
├── data/Prefs.kt        本地战绩 + 设置（SharedPreferences）
├── GameViewModel.kt     对局驱动（AI 0.5~1.5s 思考延迟、提示轮换、战绩入库）
├── MainActivity.kt      入口（横屏沉浸式、刘海屏适配）
└── ui/Screens.kt        Compose UI：主界面 / 对局 / 结算 / 设置 / 战绩

app/src/test/java/com/playking/ddz/EngineTest.kt   验收测试（R 系列 + A-01 千局对战）
```

## 构建

1. 用 Android Studio（Koala 及以上）打开本目录，等待 Gradle 同步
   （首次打开如提示缺少 Gradle Wrapper，按提示生成，或使用本地 Gradle 8.7+：`gradle wrapper`）。
2. 运行 `app` 到真机/模拟器（minSdk 24，横屏）。
3. 运行单元测试：`./gradlew test`。

## 验收对照（需求文档第 9 章）

| 编号 | 结果 |
|---|---|
| R-01~R-07 | ✅ EngineTest 逐条覆盖 |
| R-08 三人不叫重发 | ✅ `r08_redealWhenNobodyBids` |
| R-09 春天 ×2 | ✅ A-01 仿真内校验倍数公式 |
| R-10 无解提示要不起 | ✅ `r10_hintEmptyWhenUnbeatable`（UI 中"不出"按钮自动高亮为"要不起"） |
| A-01 1000 局 AI 对战 | ✅ `a01_thousandAiGames`：零非法出牌、零死循环、得分守恒（实测地主胜率约 58%） |

引擎层已在开发环境用 kotlinc 2.4 实际编译运行过全部 41 项断言（全部通过）。

## 关键规则裁定的实现位置

- `33344` → 三带对：`Combo.kt` n==5 分支优先判定
- `333444` → 飞机不带（无三带三）：连续三张优先于其他解释
- 飞机翅膀可含 2/王、按最大飞机解释：`parsePlaneWithWings`（m 从大到小枚举）
- 四带二单允许两单同点：n==6 含四张即判 FOUR_TWO_SINGLE
- 炸弹拆牌不限制：走法生成/拆分均不做特殊校验
- 先叫分者 = 1~10 随机数 `(r-1) mod 3`（保留文档指定的非均匀概率）

## 已知简化（v1 范围内）

- 无发牌动画/音效资源（仅系统按键音 + 开关）；无对局恢复（符合需求第 8 章）。
- AI 为规则型，不记牌、不推断，满足"永不出非法牌"验收标准。
