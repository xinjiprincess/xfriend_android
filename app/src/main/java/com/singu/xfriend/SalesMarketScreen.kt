package com.singu.xfriend

import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 资源市场：卡片列表 + 搜索/分段筛选 + 详情与导入。
 * 加载、分页、错误重试和导入写入逻辑保持原样，只换视觉组件。
 */
@Composable internal fun SalesMarketScreen(friend: FriendViewModel, fetch: suspend (String, String, Int) -> JSONObject, back: () -> Unit) {
    val colors = xf
    var kind by remember { mutableStateOf("experts") }
    var query by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var notice by remember { mutableStateOf("") }
    BackHandler(onBack = back)
    LaunchedEffect(kind, requested, generation, page) {
        loading = true; error = ""; if (page == 1) entries = emptyList()
        try {
            val response = fetch(kind, requested, page)
            val batch = response.optJSONArray("items")?.objects().orEmpty()
            entries = (if (page == 1) batch else entries + batch).distinctBy { it.optString("id") }
            hasMore = response.optBoolean("has_more")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "暂时无法加载商店，请检查网络后重试。" }
        finally { loading = false }
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(XfDimens.pagePadding, 8.dp, XfDimens.pagePadding, 40.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            Column {
                SalesBackButton("返回专家", back)
                Spacer(Modifier.height(4.dp))
                XfPageHeader(title = "发现好帮手", subtitle = "与电脑版连接同一资源市场")
            }
        }
        item {
            XfSegmented(
                options = listOf("专家商店", "技能市场"),
                selectedIndex = if (kind == "experts") 0 else 1,
                onSelect = { kind = if (it == 0) "experts" else "skills"; page = 1 },
            )
        }
        item {
            XfSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索名称或用途",
                trailing = {
                    XfIconButton(
                        icon = XfIcons.Search,
                        contentDescription = "搜索",
                        onClick = { requested = query.trim(); page = 1; generation++ },
                        size = 32.dp,
                    )
                },
            )
        }
        if (notice.isNotBlank()) {
            item { XfBanner(text = notice, tone = XfTone.Success, icon = XfIcons.CheckCircle) }
        }
        if (loading) item { XfProgressBar(progress = null) }
        if (error.isNotBlank()) {
            item {
                XfBanner(
                    text = error,
                    tone = XfTone.Danger,
                    icon = XfIcons.Bell,
                    action = "重试",
                    onAction = { generation++ },
                )
            }
        }
        if (!loading && error.isBlank() && entries.isEmpty()) {
            item {
                XfEmptyState(
                    icon = XfIcons.Search,
                    title = "没有找到相关资源",
                    body = "试试客户分析、谈判或沟通等关键词。",
                )
            }
        }
        if (hasMore && !loading && error.isBlank()) {
            item {
                XfButton(
                    text = "加载更多",
                    onClick = { page++ },
                    kind = XfButtonKind.Secondary,
                    small = true,
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
        }
        items(entries, key = { kind + ":" + it.optString("id") }) { entry ->
            val isExpertShop = kind == "experts"
            XfCard(onClick = { selected = entry }, padding = XfDimens.cardPaddingCompact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfIconTile(if (isExpertShop) XfIcons.Sparkles else XfIcons.Book, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.optString("name"),
                            style = XfType.h3,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            entry.optString("description"),
                            style = XfType.small,
                            color = colors.ink2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.ink4)
                }
            }
        }
    }
    selected?.let { entry ->
        val id = "market-" + MessageDigest.getInstance("SHA-256").digest((kind + ":" + entry.optString("id")).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val content = entry.optString("content")
        val dependencies = entry.optJSONArray("dependencies")?.strings()?.takeIf { it.isNotEmpty() }
        XfDialog(
            title = entry.optString("name"),
            onDismiss = { selected = null },
            confirmLabel = "导入我的资源",
            onConfirm = {
                val name = entry.optString("name")
                friend.change { store ->
                    if (kind == "experts") store.experts = store.experts.filterNot { it.id == id } + Expert(id, name, content)
                    else store.skills = store.skills.filterNot { it.id == id } + Skill(id, name, content)
                }
                notice = "已导入 $name"; selected = null
            },
            confirmEnabled = content.isNotBlank() && !friend.busy,
            dismissLabel = "取消",
        ) {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(entry.optString("description"), style = XfType.small, color = colors.ink2) }
                item { XfKeyValueRow("来源", entry.optString("source_url", "桌面同源市场")) }
                item {
                    XfKeyValueRow(
                        "版本",
                        entry.optString("version").ifBlank { "未标注" } + " · 许可：" +
                            (entry.optString("license").takeUnless { it.isBlank() || it == "null" } ?: "请查看来源说明"),
                    )
                }
                if (dependencies != null) {
                    item {
                        Text(
                            "依赖技能：${dependencies.joinToString("、")}；请到技能市场导入后绑定。",
                            style = XfType.small,
                            color = colors.ink2,
                        )
                    }
                }
                item { MarketMarkdown(content.ifBlank { "此资源未提供可导入正文。" }) }
                item {
                    Text(
                        "导入后可修改。技能仅作为你主动选择的说明文本使用，不自动执行附带脚本。",
                        style = XfType.meta,
                        color = colors.ink3,
                    )
                }
            }
        }
    }
}

/**
 * 本地私有 Markdown 渲染，避免跨文件依赖 SalesRecordPage.kt 的 SalesMarkdown；
 * 字体大小取 XfType.body，颜色取 MaterialTheme 令牌。
 */
@Composable
private fun MarketMarkdown(text: String) {
    val context = LocalContext.current
    val renderer = remember(context) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).build() }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val bodySize = XfType.body.fontSize.value
    AndroidView(
        factory = {
            TextView(it).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, bodySize)
                setTextIsSelectable(true)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        update = {
            it.setTextColor(foreground)
            it.setLinkTextColor(linkColor)
            if (it.tag != text) { renderer.setMarkdown(it, text); it.tag = text }
        },
    )
}
