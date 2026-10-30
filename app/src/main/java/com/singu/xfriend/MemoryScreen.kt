package com.singu.xfriend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Readable/removable long-term memory. The agent never silently edits its own prompt or this list. */
@Composable
internal fun MemoryScreen(s: Store, vm: FriendViewModel) {
    val colors = xf
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { vm.refreshMemory(); delay(2000) } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = "长期记忆",
                subtitle = "只保存你明确交代的偏好、个人标签、长期约定和重要事项；当前记忆会注入星助理的系统提示词。",
            )
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("后台整理", style = XfType.h3, color = colors.ink)
                        Text("任务完成后，星助理会调用当前模型整理最近对话；整理失败不会改动旧记忆。", style = XfType.meta, color = colors.ink3)
                    }
                    XfButton("立即整理", { vm.consolidateMemoryNow() }, kind = XfButtonKind.Secondary, small = true)
                }
                if (s.memoryStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.memoryStatus, style = XfType.small, color = colors.ink2)
                }
                Spacer(Modifier.height(4.dp))
                Text("共 ${s.memoryFacts.size}/${MemoryPolicy.MAX_FACTS} 条 · 仅当前账户可读；不会同步到其它账户或电脑。", style = XfType.meta, color = colors.ink3)
            }
        }
        if (s.memoryFacts.isEmpty()) item {
            EmptyHint("还没有长期记忆", "可以说“记住我喜欢…”“我的称呼是…”或“以后都先给我结论”。")
        }
        items(s.memoryFacts, key = { it.id }) { fact ->
            XfCard {
                Row(verticalAlignment = Alignment.Top) {
                    XfStatusChip(label = MemoryPolicy.categoryLabel(fact.category), tone = XfTone.Accent)
                    Spacer(Modifier.width(4.dp))
                    Text(fact.text, style = XfType.body, color = colors.ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    XfIconButton(XfIcons.X, "删除这条记忆", {
                        vm.change { store ->
                            store.memoryFacts = store.memoryFacts.filterNot { it.id == fact.id }
                            store.memoryStatus = "已删除一条长期记忆"
                        }
                    }, size = 30.dp)
                }
            }
        }
        if (s.memoryFacts.isNotEmpty()) item {
            XfButton(if (confirmClear) "确认清空全部记忆" else "清空长期记忆", {
                if (!confirmClear) confirmClear = true
                else {
                    confirmClear = false
                    vm.change { store -> store.memoryFacts = emptyList(); store.memoryStatus = "长期记忆已清空" }
                }
            }, kind = XfButtonKind.Danger, small = true)
        }
        item {
            XfCard {
                Text("范围说明", style = XfType.h3, color = colors.ink)
                Text("记忆不包含密码、密钥、验证码等敏感凭据；也不会复制附件、屏幕内容或工具输出。卸载应用、退出账户或换设备后是否保留取决于当前账户的本机数据，不会自动跨设备同步。", style = XfType.meta, color = colors.ink3)
            }
        }
    }
}
