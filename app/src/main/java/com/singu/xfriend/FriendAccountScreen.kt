package com.singu.xfriend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
internal fun FriendAccountPage(
    store: Store, account: AccountViewModel?, enabled: Boolean,
    route: (String) -> Unit, onImport: () -> Unit, onLogout: () -> Unit,
) {
    val colors = xf
    val user = account?.account?.session?.optJSONObject("user")
    val username = user?.optString("name")?.takeIf { it.isNotBlank() }
        ?: user?.optString("username")?.takeIf { it.isNotBlank() } ?: "我的工作空间"
    val agents = store.experts.count { it.id == "xfriend" || it.id.startsWith("agent-") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfAvatar(text = username, size = 56.dp, tone = XfTone.Accent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(username, style = XfType.h2, color = colors.ink, maxLines = 2)
                        Text("个人工作空间", style = XfType.meta, color = colors.ink3)
                    }
                    XfStarMark(size = 34.dp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "$agents 个助手 · ${(store.experts.size - agents).coerceAtLeast(0)} 位专家 · ${store.teams.size} 个团队",
                    style = XfType.small,
                    color = colors.ink2,
                )
            }
        }
        item { XfSectionLabel("账户") }
        item {
            SettingsCard {
                AccountRow("账号与同步", "管理资料、同步范围与导出", XfIcons.Shield) { route("sync") }
                Hr(Modifier.padding(horizontal = 14.dp))
                AccountRow("模型与用量", if (store.apiKey.isBlank()) "尚未配置模型" else "自己的模型 · ${store.model}", XfIcons.Cpu, enabled) { route("models") }
                Hr(Modifier.padding(horizontal = 14.dp))
                AccountRow("连接电脑", "配对设备与使用电脑工作台", XfIcons.Laptop) { route("connections") }
                Hr(Modifier.padding(horizontal = 14.dp))
                AccountRow("云协作", "群聊、外部邀请与跨设备消息", XfIcons.Globe) { route("cloud") }
            }
        }
        item { XfSectionLabel("应用与数据") }
        item { ApplicationUpdateCard() }
        item {
            SettingsCard {
                AccountRow("任务与诊断", "状态、恢复与错误详情", XfIcons.Tasks) { route("tasks") }
                Hr(Modifier.padding(horizontal = 14.dp))
                AccountRow("外观", "主题模式、主色与通知设置", XfIcons.Palette) { route("appearance") }
                Hr(Modifier.padding(horizontal = 14.dp))
                AccountRow("数据迁移", "将旧版销售资料迁移至 XSalers", XfIcons.Export) { route("migration") }
            }
        }
        item {
            XfButton(
                text = "导入电脑版专家与配置",
                onClick = onImport,
                enabled = enabled,
                kind = XfButtonKind.Secondary,
                icon = XfIcons.Export,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (account != null) {
            item {
                XfButton(
                    text = "退出登录",
                    onClick = onLogout,
                    enabled = !account.busy,
                    kind = XfButtonKind.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text(
                "XFAMILY · XFriend ${BuildConfig.VERSION_NAME}",
                style = XfType.meta,
                color = colors.ink4,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )
        }
    }
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    XfCard(padding = 4.dp) {
        Column(content = content)
    }
}

@Composable private fun AccountRow(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = xf
    XfListRow(
        title = title,
        subtitle = description,
        leading = { XfIconTile(icon, size = 36.dp, tone = XfTone.Accent) },
        trailing = {
            Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink4)
        },
        onClick = if (enabled) onClick else null,
        minHeight = 64.dp,
        modifier = Modifier.padding(horizontal = 10.dp),
    )
}
