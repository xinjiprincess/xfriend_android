package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun EditorDialog(kind:String,id:String,s:Store,busy:Boolean,dismiss:()->Unit,agent:Boolean=false,error:String?=null,save:(String,String,List<String>,List<String>,List<String>)->Unit) {
    @Suppress("UNUSED_VARIABLE") val agentHint = agent
    val colors = xf
    val expert=s.experts.find{it.id==id};val team=s.teams.find{it.id==id}
    val oldName=when(kind){"expert"->expert?.name;"team"->team?.name;"skill"->s.skills.find{it.id==id}?.name;else->s.knowledge.find{it.id==id}?.name}.orEmpty()
    val oldContent=when(kind){"expert"->expert?.prompt;"skill"->s.skills.find{it.id==id}?.content;"knowledge"->s.knowledge.find{it.id==id}?.content;else->""}.orEmpty()
    var name by rememberSaveable(kind,id){mutableStateOf(oldName)}
    var content by rememberSaveable(kind,id){mutableStateOf(oldContent)}
    var skills by rememberSaveable(kind,id){mutableStateOf(expert?.skills.orEmpty())}
    var knowledge by rememberSaveable(kind,id){mutableStateOf(expert?.knowledge.orEmpty())}
    var members by rememberSaveable(kind,id){mutableStateOf(team?.members.orEmpty())}
    var advanced by rememberSaveable{mutableStateOf(false)};var query by rememberSaveable{mutableStateOf("")}
    var discard by remember{mutableStateOf(false)}
    val dirty=name!=oldName || content!=oldContent || skills!=expert?.skills.orEmpty() || knowledge!=expert?.knowledge.orEmpty() || members!=team?.members.orEmpty()
    fun close(){if(!busy){if(dirty)discard=true else dismiss()}}
    val label=when(kind){"expert"->"专家";"team"->"团队";"skill"->"技能";else->"知识"}
    val canSave = !busy && name.isNotBlank() && (if(kind=="team") members.size in 1..8 else content.isNotBlank())
    Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BackHandler{close()}
        Surface(Modifier.fillMaxSize(),color=colors.canvas) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding().imePadding(),
                containerColor = colors.canvas,
                topBar = {
                    XfTopBar(
                        title = (if(id.isBlank()) "创建" else "编辑")+label,
                        onBack = ::close,
                        actions = {
                            XfButton(
                                text = "保存",
                                onClick = { save(name.trim(),content.trim(),skills,knowledge,members) },
                                enabled = canSave,
                                small = true,
                            )
                        },
                    )
                },
            ) { insets ->
                LazyColumn(
                    Modifier.padding(insets).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if(error != null) item { XfBanner(text = error, tone = XfTone.Danger, icon = XfIcons.Bell) }
                    item {
                        XfCard {
                            XfTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = "名称",
                                placeholder = "给${label}起一个名字",
                                singleLine = true,
                            )
                            if (kind != "team") {
                                Spacer(Modifier.height(14.dp))
                                XfTextField(
                                    value = content,
                                    onValueChange = { content = it },
                                    label = if (kind == "expert") "工作方式与指令" else "正文",
                                    placeholder = if (kind == "expert") "擅长什么、如何回答、需要注意什么…" else "填写内容",
                                    singleLine = false,
                                    minLines = 7,
                                )
                            }
                        }
                    }
                    if(kind=="expert") {
                        item {
                            XfCard {
                                XfSectionHeader(
                                    title = "能力与资料",
                                    subtitle = if (skills.size + knowledge.size == 0) "尚未关联技能或知识" else "已关联 ${skills.size + knowledge.size} 项",
                                    action = if (advanced) "收起" else "展开",
                                    onAction = { advanced = !advanced },
                                )
                                if (advanced) {
                                    Spacer(Modifier.height(8.dp))
                                    XfSearchField(value = query, onValueChange = { query = it }, placeholder = "搜索技能或知识")
                                    Spacer(Modifier.height(10.dp))
                                    XfSectionLabel("技能")
                                    if(s.skills.isEmpty()) {
                                        Text("在专家页的“技能与知识”中创建或导入。", style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                    s.skills.filter { it.name.contains(query, true) }.forEach { record ->
                                        FriendEditorChoice(record.name, record.id in skills, !busy) {
                                            skills = if (record.id in skills) skills - record.id else skills + record.id
                                        }
                                    }
                                    XfSectionLabel("知识")
                                    if(s.knowledge.isEmpty()) {
                                        Text("在资源页的“技能与知识”中创建或导入。", style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                    s.knowledge.filter { it.name.contains(query, true) }.forEach { record ->
                                        FriendEditorChoice(record.name, record.id in knowledge, !busy) {
                                            knowledge = if (record.id in knowledge) knowledge - record.id else knowledge + record.id
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    XfBanner(
                                        text = "继承当前模型。工具权限由每轮问答／计划／执行模式控制；电脑专属引擎请在电脑设置。",
                                        tone = XfTone.Neutral,
                                        icon = XfIcons.Fingerprint,
                                    )
                                }
                            }
                        }
                    }
                    if(kind=="team") {
                        item {
                            XfCard {
                                XfSectionHeader(
                                    title = "顺序协作 · 选择 1–8 位成员",
                                    subtitle = "这会按成员顺序处理任务，不是电脑版只读会议。",
                                )
                                Spacer(Modifier.height(10.dp))
                                XfSearchField(value = query, onValueChange = { query = it }, placeholder = "搜索专家")
                                Spacer(Modifier.height(12.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(s.experts.filter { it.name.contains(query, true) }, key = { it.id }) { expertRecord ->
                                        val selected = expertRecord.id in members
                                        val canToggle = !busy && (selected || members.size < 8)
                                        val onToggle: (() -> Unit)? = if (canToggle) {
                                            {
                                                members = if (selected) members - expertRecord.id else members + expertRecord.id
                                            }
                                        } else null
                                        XfChip(
                                            text = expertRecord.name,
                                            selected = selected,
                                            onClick = onToggle,
                                        )
                                    }
                                }
                            }
                        }
                        items(members, key = { it }) { member ->
                            val index = members.indexOf(member)
                            XfCard(padding = 6.dp) {
                                XfListRow(
                                    title = "${index + 1}. ${s.experts.find { it.id == member }?.name ?: "成员不存在"}",
                                    subtitle = if (index == 0) "第一位执行" else "第 ${index + 1} 位执行",
                                    onClick = null,
                                    minHeight = 48.dp,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    trailing = {
                                        XfIconButton(
                                            icon = XfIcons.ChevDown,
                                            contentDescription = "上移",
                                            onClick = { members = members.toMutableList().apply { java.util.Collections.swap(this, index, index - 1) } },
                                            enabled = !busy && index > 0,
                                            rotation = 180f,
                                        )
                                        XfIconButton(
                                            icon = XfIcons.X,
                                            contentDescription = "移除",
                                            onClick = { members = members - member },
                                            enabled = !busy,
                                            tint = colors.dangerText,
                                        )
                                    },
                                )
                            }
                        }
                        if (members.isEmpty()) item {
                            XfCard(padding = 18.dp) {
                                XfEmptyState(icon = XfIcons.Users, title = "还没有成员", body = "从上方专家列表选择 1–8 位成员，按点击顺序执行。")
                            }
                        }
                    }
                }
            }
        }
        if(discard) XfDialog(
            title = "放弃未保存的修改？",
            onDismiss = { discard = false },
            confirmLabel = "放弃",
            onConfirm = { discard = false; dismiss() },
            dismissLabel = "继续编辑",
            danger = true,
        ) {
            Text("当前修改尚未保存，放弃后无法恢复。", style = XfType.small, color = colors.ink2)
        }
    }
}

@Composable private fun FriendEditorChoice(name:String,checked:Boolean,enabled:Boolean,toggle:()->Unit){
    val colors = xf
    XfListRow(
        title = name,
        onClick = if (enabled) toggle else null,
        minHeight = 48.dp,
        leading = {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) toggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accentSolid,
                    uncheckedColor = colors.ink4,
                    checkmarkColor = androidx.compose.ui.graphics.Color.White,
                ),
            )
        },
    )
}
