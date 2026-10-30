package com.singu.xfriend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Integrate PlatformScreen() into the Settings destination. */
@Composable
fun PlatformScreen(accountScope: String) {
    val context = LocalContext.current.applicationContext
    val colors = xf
    val client = remember(context, accountScope) { PlatformClient(context, accountScope) }
    val scope = rememberCoroutineScope()
    // Deliberately not rememberSaveable: passwords must not enter saved state.
    var origin by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<PlatformClient.Account?>(null) }
    var devices by remember { mutableStateOf<List<PlatformClient.Device>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun perform(action: suspend () -> Unit) {
        loading = true
        error = null
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "平台操作失败" }
        finally { loading = false }
    }

    LaunchedEffect(client) {
        perform {
            account = client.restore()
            if (account!!.origin.isNotEmpty()) origin = account!!.origin
            if (account!!.loggedIn) devices = client.devices()
        }
    }
    DisposableEffect(client) { onDispose { password = "" } }

    LazyColumn(
        contentPadding = PaddingValues(XfDimens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = "平台协作",
                subtitle = "直接连接 HTTPS 平台；密码仅用于本次登录，平台会话加密保存在此设备。",
            )
        }
        if (loading) item { XfProgressBar(progress = null) }
        error?.let { message -> item { XfBanner(text = message, tone = XfTone.Danger, icon = XfIcons.Bell) } }
        val current = account
        if (current?.loggedIn == true) {
            item {
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfIconTile(XfIcons.Shield, size = XfDimens.avatarMedium)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("已登录：${current.name}", style = XfType.title, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.origin, style = XfType.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        XfButton(
                            text = "刷新设备",
                            onClick = { scope.launch { perform { devices = client.devices() } } },
                            enabled = !loading,
                            kind = XfButtonKind.Secondary,
                            icon = XfIcons.Refresh,
                        )
                        XfButton(
                            text = "退出登录",
                            onClick = {
                                scope.launch { perform {
                                    devices = emptyList()
                                    account = null
                                    password = ""
                                    account = client.logout()
                                } }
                            },
                            enabled = !loading,
                            kind = XfButtonKind.Ghost,
                        )
                    }
                }
            }
            if (devices.isEmpty() && !loading) item {
                XfCard(padding = 18.dp) {
                    XfEmptyState(icon = XfIcons.Laptop, title = "暂无设备", body = "暂无属于当前用户的设备。")
                }
            }
            items(devices) { device ->
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfIconTile(XfIcons.Laptop, size = XfDimens.avatarMedium, tone = if (device.isActive.equals("true", ignoreCase = true) || device.isActive == "1") XfTone.Accent else XfTone.Neutral)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(device.name.ifEmpty { device.id }, style = XfType.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("ID：${device.id}", style = XfType.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (device.description.isNotEmpty() && device.description != "null") {
                                Text(device.description, style = XfType.meta, color = colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        XfStatusChip(
                            label = if (device.isActive.equals("true", ignoreCase = true) || device.isActive == "1") "在线" else "离线",
                            tone = if (device.isActive.equals("true", ignoreCase = true) || device.isActive == "1") XfTone.Success else XfTone.Neutral,
                        )
                    }
                }
            }
        } else if (current != null && current.tenants.isNotEmpty()) {
            item { XfSectionHeader(title = "选择租户", subtitle = "当前账号可进入以下平台空间。") }
            items(current.tenants) { tenant ->
                XfCard(onClick = {
                    scope.launch { perform {
                        account = client.selectTenant(tenant.id)
                        devices = client.devices()
                    } }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfAvatar(text = tenant.name.ifEmpty { tenant.id.toString() }, size = XfDimens.avatarMedium)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tenant.name.ifEmpty { tenant.id.toString() }, style = XfType.bodyStrong, color = colors.ink)
                            Text("租户 ID：${tenant.id}", style = XfType.meta, color = colors.ink3)
                        }
                        Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.ink4)
                    }
                }
            }
            item {
                XfButton(
                    text = "取消登录",
                    onClick = { scope.launch { perform { account = client.logout(); devices = emptyList(); password = "" } } },
                    enabled = !loading,
                    kind = XfButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item {
                XfCard {
                    XfSectionHeader(title = "登录平台", subtitle = "使用平台账号登录后读取本人设备。")
                    Spacer(Modifier.height(10.dp))
                    XfTextField(
                        value = origin,
                        onValueChange = { origin = it },
                        label = "平台源地址（HTTPS）",
                        placeholder = "https://平台域名",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.height(12.dp))
                    XfTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "手机号或邮箱",
                        placeholder = "请输入手机号或邮箱",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    XfTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        placeholder = "请输入密码",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(16.dp))
                    XfButton(
                        text = "登录",
                        onClick = {
                            val enteredPassword = password
                            password = ""
                            scope.launch { perform {
                                devices = emptyList()
                                account = null
                                account = client.login(origin, username, enteredPassword)
                                if (account!!.loggedIn) devices = client.devices()
                            } }
                        },
                        enabled = !loading && origin.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "密码只在本次登录使用，不会写入本地记录；平台会话加密保存在此设备。",
                        style = XfType.meta,
                        color = colors.ink3,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
