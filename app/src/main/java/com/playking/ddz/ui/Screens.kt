package com.playking.ddz.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.playking.ddz.GameViewModel
import com.playking.ddz.Screen
import com.playking.ddz.engine.Card
import com.playking.ddz.engine.Phase
import com.playking.ddz.engine.Rank

private val TableGreen = Color(0xFF1B5E20)
private val TableGreenDark = Color(0xFF0D3B11)
private val Gold = Color(0xFFFFC107)

@Composable
fun PlayKingApp(vm: GameViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(TableGreen, TableGreenDark)))
        ) {
            when (vm.screen) {
                Screen.MENU -> MenuScreen(vm)
                Screen.GAME -> GameScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.STATS -> StatsScreen(vm)
            }
        }
    }
}

// ---------------- 主界面 ----------------

@Composable
private fun MenuScreen(vm: GameViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("PlayKing", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Gold)
        Text("单机斗地主", fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuButton("开始游戏") { vm.startGame() }
            MenuButton("战绩") { vm.screen = Screen.STATS }
            MenuButton("设置") { vm.screen = Screen.SETTINGS }
        }
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.height(48.dp)
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

// ---------------- 战绩 ----------------

@Composable
private fun StatsScreen(vm: GameViewModel) {
    val s = vm.stats
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("战绩", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Gold)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            StatItem("总局数", "${s.total}")
            StatItem("胜", "${s.wins}")
            StatItem("负", "${s.losses}")
            StatItem("当地主胜率", "${s.landlordWinRate}%")
            StatItem("最高连胜", "${s.bestStreak}")
        }
        Spacer(Modifier.height(28.dp))
        MenuButton("返回") { vm.screen = Screen.MENU }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

// ---------------- 设置 ----------------

@Composable
private fun SettingsScreen(vm: GameViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Gold)
        Spacer(Modifier.height(16.dp))
        SettingRow("音效", vm.settings.soundEnabled) {
            vm.updateSettings(vm.settings.copy(soundEnabled = it))
        }
        SettingRow("出牌提示", vm.settings.hintEnabled) {
            vm.updateSettings(vm.settings.copy(hintEnabled = it))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { confirmClear = true }) { Text("清空战绩", color = Color.White) }
            MenuButton("返回") { vm.screen = Screen.MENU }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空战绩") },
            text = { Text("确定要清空全部本地战绩吗？该操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.clearStats(); confirmClear = false }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.width(280.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 16.sp, color = Color.White)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------- 对局界面 ----------------

@Composable
private fun GameScreen(vm: GameViewModel) {
    val ui = vm.ui
    val view = LocalView.current
    fun click() { if (vm.settings.soundEnabled) view.playSoundEffect(android.view.SoundEffectConstants.CLICK) }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {

        // 顶栏：退出 + 底牌 + 倍数
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { click(); vm.requestQuit() }) { Text("〈 退出", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ui.bottomRevealed) ui.bottom.forEach { CardFace(it, width = 30.dp) }
                else repeat(3) { CardBack(width = 30.dp) }
            }
            Spacer(Modifier.weight(1f))
            Text("倍数 ×${ui.multiplier}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.width(12.dp))
        }

        // 左上：上家 AI（座位 2）
        AiPanel(
            name = "电脑·上家",
            cardsLeft = ui.aiHandCounts[1],
            isLandlord = ui.landlordSeat == 2,
            isTurn = ui.currentSeat == 2 || ui.currentBidder == 2,
            bidLabel = ui.bidLabels[2],
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 48.dp)
        )
        // 右上：下家 AI（座位 1）
        AiPanel(
            name = "电脑·下家",
            cardsLeft = ui.aiHandCounts[0],
            isLandlord = ui.landlordSeat == 1,
            isTurn = ui.currentSeat == 1 || ui.currentBidder == 1,
            bidLabel = ui.bidLabels[1],
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 48.dp)
        )

        // 中央出牌区
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 90.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TablePlay(ui.table[2], Modifier.weight(1f), Alignment.CenterStart)
            TablePlay(ui.table[1], Modifier.weight(1f), Alignment.CenterEnd)
        }
        TablePlay(
            ui.table[0],
            Modifier.align(Alignment.Center).offset(y = 56.dp),
            Alignment.Center
        )

        // 我的状态（地主标识 / 叫分气泡）
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 110.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (ui.landlordSeat == 0) LandlordBadge()
            Text("我", color = Color.White, fontWeight = FontWeight.Bold)
            ui.bidLabels[0]?.let { BidBubble(it) }
        }

        // 操作区
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                ui.phase == Phase.BIDDING && ui.currentBidder == 0 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("不叫") { click(); vm.humanBid(0) }
                    for (b in ui.availableBids) ActionButton("${b}分") { click(); vm.humanBid(b) }
                }
                ui.phase == Phase.PLAYING && ui.currentSeat == 0 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vm.settings.hintEnabled) ActionButton("提示") { click(); vm.hint() }
                    ActionButton(
                        if (ui.noBeatAvailable) "要不起" else "不出",
                        enabled = ui.mustBeat,
                        highlight = ui.noBeatAvailable
                    ) { click(); vm.humanPass() }
                    ActionButton("出牌", enabled = vm.selectedIds.isNotEmpty()) { click(); vm.humanPlay() }
                }
                ui.phase == Phase.BIDDING -> Text("等待对方叫分…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                else -> {}
            }
            Spacer(Modifier.height(6.dp))
            // 手牌
            HandRow(vm)
            Spacer(Modifier.height(8.dp))
        }

        // 结算
        ui.result?.let { ResultDialog(vm) }

        // 退出确认
        if (vm.showQuitDialog) {
            AlertDialog(
                onDismissRequest = { vm.showQuitDialog = false },
                title = { Text("退出对局") },
                text = { Text("对局尚未结束，退出后本局作废且不计入战绩。确定退出？") },
                confirmButton = { TextButton(onClick = { vm.confirmQuit() }) { Text("退出") } },
                dismissButton = { TextButton(onClick = { vm.showQuitDialog = false }) { Text("继续游戏") } }
            )
        }
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean = true, highlight: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlight) Color(0xFFE53935) else Gold,
            contentColor = if (highlight) Color.White else Color.Black,
            disabledContainerColor = Color.Gray.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AiPanel(
    name: String, cardsLeft: Int, isLandlord: Boolean, isTurn: Boolean,
    bidLabel: String?, modifier: Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (isTurn) Gold else Color.White.copy(alpha = 0.25f))
                .border(2.dp, if (isTurn) Color.White else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("AI", fontWeight = FontWeight.Bold, color = if (isTurn) Color.Black else Color.White)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isLandlord) LandlordBadge()
            Text(name, fontSize = 12.sp, color = Color.White)
        }
        Text("剩 $cardsLeft 张", fontSize = 12.sp, color = Gold, fontWeight = FontWeight.Bold)
        bidLabel?.let { BidBubble(it) }
    }
}

@Composable
private fun LandlordBadge() {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE53935)).padding(horizontal = 4.dp, vertical = 1.dp)
    ) { Text("地主", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold) }
}

@Composable
private fun BidBubble(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
}

/** 中央出牌区：null=未行动，空=不出。 */
@Composable
private fun TablePlay(cards: List<Card>?, modifier: Modifier, align: Alignment) {
    Box(modifier, contentAlignment = align) {
        when {
            cards == null -> {}
            cards.isEmpty() -> Text("不出", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            else -> OverlapCards(cards.sortedByDescending { it.rank }, width = 34.dp, overlap = 22.dp)
        }
    }
}

@Composable
private fun OverlapCards(cards: List<Card>, width: androidx.compose.ui.unit.Dp, overlap: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(-overlap)) {
        cards.forEach { CardFace(it, width = width) }
    }
}

// ---------------- 手牌 ----------------

@Composable
private fun HandRow(vm: GameViewModel) {
    val hand = vm.ui.myHand
    val scroll = rememberScrollState()
    Row(
        Modifier.horizontalScroll(scroll).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy((-26).dp)
    ) {
        hand.forEach { card ->
            val selected = card.id in vm.selectedIds
            Box(Modifier.offset(y = if (selected) (-14).dp else 0.dp)) {
                CardFace(
                    card, width = 46.dp,
                    modifier = Modifier.clickable { vm.toggleCard(card.id) },
                    selected = selected
                )
            }
        }
    }
}

// ---------------- 扑克牌 ----------------

@Composable
fun CardFace(
    card: Card,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val height = width * 1.4f
    val color = if (card.isRed) Color(0xFFD32F2F) else Color(0xFF212121)
    val label = when (card.rank) {
        Rank.SMALL_JOKER -> "王"
        Rank.BIG_JOKER -> "王"
        else -> Rank.label(card.rank)
    }
    Column(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Gold else Color(0xFFBDBDBD),
                RoundedCornerShape(5.dp)
            )
            .padding(horizontal = 3.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            fontSize = (width.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
            color = if (card.rank == Rank.SMALL_JOKER) Color(0xFF212121) else color,
            lineHeight = (width.value * 0.46f).sp
        )
        Text(
            when (card.rank) {
                Rank.SMALL_JOKER -> "小"
                Rank.BIG_JOKER -> "大"
                else -> card.suitLabel
            },
            fontSize = (width.value * 0.34f).sp,
            color = color,
            lineHeight = (width.value * 0.38f).sp
        )
    }
}

@Composable
fun CardBack(width: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(width)
            .height(width * 1.4f)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1565C0), Color(0xFF0D47A1))))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
    )
}

// ---------------- 结算 ----------------

@Composable
private fun ResultDialog(vm: GameViewModel) {
    val res = vm.ui.result ?: return
    val iWon = res.scores[0] > 0
    Dialog(onDismissRequest = {}) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF263238))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (iWon) "胜利！" else "失败",
                fontSize = 30.sp, fontWeight = FontWeight.Black,
                color = if (iWon) Gold else Color(0xFF90A4AE)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                (if (res.landlordWin) "地主获胜" else "农民获胜") + if (res.spring) "（春天 ×2）" else "",
                color = Color.White, fontSize = 14.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "叫分 ${res.bid} × 炸弹 ${res.bombCount} 个 → 倍数 ×${res.multiplier}",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            val names = listOf("我", "电脑·下家", "电脑·上家")
            for (i in 0..2) {
                Row(Modifier.width(220.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(names[i], color = Color.White, fontSize = 14.sp)
                        if (res.landlordSeat == i) LandlordBadge()
                    }
                    Text(
                        (if (res.scores[i] > 0) "+" else "") + res.scores[i],
                        color = if (res.scores[i] > 0) Gold else Color(0xFFEF9A9A),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.backToMenu() }) { Text("返回主界面", color = Color.White) }
                MenuButton("再来一局") { vm.newRound() }
            }
        }
    }
}
