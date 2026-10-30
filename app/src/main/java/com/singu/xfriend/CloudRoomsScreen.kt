package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun CloudRoomsPanel(account: CloudAccount) {
    val context=LocalContext.current
    val coroutine=rememberCoroutineScope()
    val identity=account.scope ?: return
    var rooms by remember(identity){mutableStateOf<List<JSONObject>>(emptyList())}
    var invitations by remember(identity){mutableStateOf<List<JSONObject>>(emptyList())}
    var selected by remember(identity){mutableStateOf("")}
    var title by remember {mutableStateOf("")}
    var invitee by remember {mutableStateOf("")}
    var text by remember {mutableStateOf("")}
    var messages by remember(identity){mutableStateOf<List<JSONObject>>(emptyList())}
    var error by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var targets by remember(identity){mutableStateOf<List<JSONObject>>(emptyList())}
    var selectedTarget by remember(identity){mutableStateOf("")}
    var tasks by remember(identity){mutableStateOf<List<JSONObject>>(emptyList())}
    var execution by remember {mutableStateOf<Triple<String,String,String>?>(null)}
    var approval by remember {mutableStateOf<JSONObject?>(null)}
    suspend fun get(path:String,query:Map<String,String> = emptyMap())=account.authorizedGet("/api/v2/collaboration$path",query,identity)
    suspend fun post(path:String,body:JSONObject)=account.authorizedPost("/api/v2/collaboration$path",body,identity)
    suspend fun reload() {
        val list=get("/rooms").getJSONArray("items")
        rooms=(0 until list.length()).map{list.getJSONObject(it)}
        if(rooms.none{it.getString("room_id")==selected}) selected=rooms.firstOrNull()?.getString("room_id") ?: ""
        val invites=get("/invitations").getJSONArray("items")
        invitations=(0 until invites.length()).map{invites.getJSONObject(it)}
        val available=get("/execution/targets").getJSONArray("items")
        targets=(0 until available.length()).map{available.getJSONObject(it)}
        if(targets.none{it.getString("target_id")==selectedTarget})selectedTarget=targets.firstOrNull()?.getString("target_id") ?: ""
    }
    fun action(block:suspend()->Unit) {coroutine.launch{busy=true;try{block();error=""}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "云协作失败"}finally{busy=false}}}
    LaunchedEffect(identity){try{reload()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "无法读取云房间"}}
    LaunchedEffect(identity,selected){
        if(selected.isBlank())return@LaunchedEffect
        var cursor=0L;messages=emptyList()
        while(isActive){
            try{
                val page=get("/rooms/$selected/messages",mapOf("after" to cursor.toString()))
                val values=page.getJSONArray("messages")
                val next=(0 until values.length()).map{values.getJSONObject(it)}
                messages=(messages+next.filter{n->messages.none{it.getString("message_id")==n.getString("message_id")}}).takeLast(200)
                cursor=page.getLong("cursor")
                val active=get("/rooms/$selected/ai").getJSONArray("items")
                tasks=(0 until active.length()).map{active.getJSONObject(it)}
                delay(if(page.getBoolean("has_more"))10 else 3000)
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "消息连接中断，等待重连";delay(10000)}
        }
    }
    XfCard {
        Text("云端协作房间",style=XfType.title,color=xf.ink)
        Text("组织成员和受邀外部账号共享消息；只有接受邀请后才能查看。",style=XfType.meta,color=xf.ink3)
        XfTextField(title,{title=it},label="新房间名称",singleLine=true)
        XfButton("创建房间",{action{post("/rooms",JSONObject().put("title",title));title="";reload()}},enabled=!busy&&title.isNotBlank(),kind=XfButtonKind.Secondary)
        XfButton("刷新房间与邀请",{action{reload()}},enabled=!busy,kind=XfButtonKind.Ghost)
        for(invite in invitations){
            Text("邀请：${invite.getString("title")}",style=XfType.body,color=xf.ink)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                XfButton("接受入群",{action{post("/invitations/${invite.getString("invite_id")}",JSONObject().put("accept",true));reload()}},enabled=!busy,small=true)
                XfButton("拒绝",{action{post("/invitations/${invite.getString("invite_id")}",JSONObject().put("accept",false));reload()}},enabled=!busy,kind=XfButtonKind.Ghost,small=true)
            }
        }
        for(room in rooms) XfButton((if(selected==room.getString("room_id"))"● " else "")+room.getString("title"),{selected=room.getString("room_id")},kind=XfButtonKind.Ghost,small=true)
        if(selected.isNotBlank()){
            Text("执行位置：按配置指定电脑或托管服务器，手机只提交并观察任务。",style=XfType.meta,color=xf.ink3)
            for(target in targets)XfButton((if(target.getString("target_id")==selectedTarget)"● " else "")+target.getString("label"),{selectedTarget=target.getString("target_id")},enabled=!busy,kind=XfButtonKind.Ghost,small=true)
            if(targets.isEmpty())Text("尚未配置可用的执行目标，请由组织管理员绑定电脑或服务器工作区。",style=XfType.meta,color=xf.ink3)
            if(rooms.find{it.getString("room_id")==selected}?.getString("role")=="owner"){
                XfTextField(invitee,{invitee=it},label="受邀用户邮箱或完整手机号",singleLine=true)
                XfButton("发送邀请",{action{post("/rooms/$selected/invitations",JSONObject().put("identifier",invitee));invitee=""}},enabled=!busy&&invitee.isNotBlank(),kind=XfButtonKind.Secondary)
            }
            for(message in messages.takeLast(30)){
                Spacer(Modifier.height(8.dp));Text(message.getString("user_id").take(8),style=XfType.meta,color=xf.ink3)
                Text(message.getString("text"),style=XfType.body,color=xf.ink)
                if(!message.optBoolean("assistant") && selectedTarget.isNotBlank())XfButton("交给所选 AI 执行",{execution=Triple(selected,message.getString("message_id"),selectedTarget)},enabled=!busy,kind=XfButtonKind.Secondary,small=true)
            }
            for(task in tasks.takeLast(10)){
                Text("AI ${task.optString("target_id")} · ${task.optString("state",task.optString("status"))}",style=XfType.meta,color=xf.ink3)
                val pending=task.optJSONArray("approvals")
                if(pending!=null)for(index in 0 until pending.length()){
                    val item=pending.getJSONObject(index)
                    XfButton("查看服务器工具授权",{approval=JSONObject().put("room",selected).put("task",task.getString("task_id")).put("id",item.getString("approval_id")).put("request",item.getJSONObject("request"))},enabled=!busy,kind=XfButtonKind.Secondary,small=true)
                }
            }
            XfTextField(text,{text=it},label="发送到房间",minLines=2)
            XfButton("发送 / 重试",{action{
                val prefs=context.getSharedPreferences("cloud-room-outbox",android.content.Context.MODE_PRIVATE)
                val key="$identity:$selected"
                val body=prefs.getString(key,null)?.let(::JSONObject) ?: JSONObject().put("client_request_id",java.util.UUID.randomUUID().toString()).put("text",text)
                check(prefs.edit().putString(key,body.toString()).commit()){"待发送消息无法保存"}
                post("/rooms/$selected/messages",body);prefs.edit().remove(key).apply();text=""
            }},enabled=!busy,kind=XfButtonKind.Primary)
        }
        if(error.isNotBlank())Text(error,style=XfType.meta,color=xf.ink3)
    }
    execution?.let { pending -> androidx.compose.material3.AlertDialog(onDismissRequest={execution=null},title={Text("确认提交 AI 任务")},text={Text("此消息将交给 ${targets.find{it.getString("target_id")==pending.third}?.optString("label") ?: pending.third} 执行，执行结果会同步到房间。工具授权仍按该执行位置的权限流程确认。")},confirmButton={androidx.compose.material3.TextButton(onClick={action{post("/rooms/${pending.first}/ai",JSONObject().put("message_id",pending.second).put("target_id",pending.third).put("confirmed",true));execution=null}}){Text("确认执行")}},dismissButton={androidx.compose.material3.TextButton(onClick={execution=null}){Text("取消")}}) }
    approval?.let { pending ->
        fun decide(decision:String){action{post("/rooms/${pending.getString("room")}/ai/${pending.getString("task")}/approvals/${pending.getString("id")}",JSONObject().put("decision",decision));approval=null}}
        androidx.compose.material3.AlertDialog(onDismissRequest={approval=null},title={Text("服务器请求工具授权")},text={Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())){Text(pending.getJSONObject("request").toString(2));Text("仅对当前请求生效。指定电脑上的审批仍需在该电脑确认。")}},confirmButton={androidx.compose.material3.TextButton(enabled=!busy,onClick={decide("approve")}){Text("允许本次")}},dismissButton={androidx.compose.material3.TextButton(enabled=!busy,onClick={decide("reject")}){Text("拒绝")}})
    }
}
