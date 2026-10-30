package com.singu.xfriend

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 客户编辑类底部弹层：客户资料、商机、跟进与画像编辑共用。
 * 弹层容器改为新令牌的 surface 色，内部字段与按钮全部使用 Xf 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CustomerEditSheet(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = xf.surface,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(XfDimens.pagePadding), verticalArrangement = Arrangement.spacedBy(XfDimens.cardPadding)) {
            title()
            Box(Modifier.weight(1f, fill = false)) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton(); Spacer(Modifier.width(XfDimens.rowGap)); confirmButton() }
        }
    }
}

internal fun customerDateLabel(value: String): String = if (value.isBlank()) "未设日期" else
    runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("yyyy年M月d日")) }.getOrDefault(value)

@Composable internal fun CustomerDateField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        XfButton(
            text = "$label · ${customerDateLabel(value)}",
            onClick = {
                val initial = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
                DatePickerDialog(context, { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) },
                    initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
            },
            enabled = enabled,
            kind = XfButtonKind.Secondary,
            modifier = Modifier.weight(1f),
            small = true,
        )
        if (value.isNotBlank()) XfButton("清除", { onChange("") }, enabled = enabled, kind = XfButtonKind.Ghost, small = true)
    }
}

@Composable internal fun CustomerTimeField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        XfButton(
            text = "时间 · ${value.ifBlank { "未设置" }}",
            onClick = {
                val initial = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(9, 0))
                TimePickerDialog(context, { _, hour, minute -> onChange(LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm"))) },
                    initial.hour, initial.minute, true).show()
            },
            enabled = enabled,
            kind = XfButtonKind.Secondary,
            modifier = Modifier.weight(1f),
            small = true,
        )
        if (value.isNotBlank()) XfButton("清除", { onChange("") }, enabled = enabled, kind = XfButtonKind.Ghost, small = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CustomerInsightEditor(customer: Customer, initial: CustomerInsight, vm: SalesViewModel, onClose: () -> Unit) {
    val colors = xf
    var summary by rememberSaveable(customer.id) { mutableStateOf(initial.summary) }
    var preferences by rememberSaveable(customer.id) { mutableStateOf(initial.preferences) }
    var needs by rememberSaveable(customer.id) { mutableStateOf(initial.needs) }
    var budget by rememberSaveable(customer.id) { mutableStateOf(initial.budget) }
    var timing by rememberSaveable(customer.id) { mutableStateOf(initial.timing) }
    var decisionRole by rememberSaveable(customer.id) { mutableStateOf(initial.decisionRole) }
    var grade by rememberSaveable(customer.id) { mutableStateOf(initial.grade) }
    var pending by remember { mutableStateOf(false) }
    val blocked = vm.busy || vm.recording
    LaunchedEffect(vm.busy) { if (pending && !vm.busy) { pending = false; if (vm.error == null) onClose() } }
    ModalBottomSheet(onDismissRequest = { if (!blocked) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(XfDimens.pagePadding), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            XfPageHeader(title = "${customer.name} · 客户画像", subtitle = "核对信息后确认保存。AI 建议可修改，空缺信息保留待确认。")
            Input("画像摘要", summary, { summary = it.take(12000) }, false, enabled = !blocked)
            Input("沟通与产品偏好", preferences, { preferences = it.take(12000) }, false, enabled = !blocked)
            Input("需求与痛点", needs, { needs = it.take(12000) }, false, enabled = !blocked)
            Input("预算", budget, { budget = it.take(12000) }, true, enabled = !blocked)
            Input("采购时间与计划", timing, { timing = it.take(12000) }, true, enabled = !blocked)
            Input("决策角色", decisionRole, { decisionRole = it.take(12000) }, true, enabled = !blocked)
            Input("客户等级（未确认时填待判断）", grade, { grade = it.take(100) }, true, enabled = !blocked)
            vm.error?.let { XfBanner(it, tone = XfTone.Danger, icon = XfIcons.Bell) }
            XfButton(
                if (pending) "保存中…" else "确认保存画像",
                {
                    pending = true
                    vm.update { it.withCustomerInsight(customer.id, CustomerInsight(summary, preferences, needs, budget, timing, decisionRole, grade.ifBlank { "待判断" })) }
                },
                enabled = !blocked,
                modifier = Modifier.fillMaxWidth(),
                loading = pending,
            )
            XfButton("取消", onClose, enabled = !blocked, kind = XfButtonKind.Ghost, modifier = Modifier.fillMaxWidth(), small = true)
        }
    }
}
