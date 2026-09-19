package com.metronearby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.HeadwaySession
import com.metronearby.domain.*
import java.util.Calendar

@Composable
fun HeadwayLearningDialog(item: ArrivalItem, sessions: List<HeadwaySession>,
                          onSave: (HeadwaySession) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val relevant = sessions.filter { it.stationId == item.stationId && it.patternId == item.patternId }
    val active = HeadwayLearning.active(relevant, item.stationId, item.patternId)
    var confirmed by remember(active?.id, active?.points?.size) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连续记录间隔") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${item.patternName} · 开往${item.terminalStationName}")
                Text("只记录本站、同一交路的车，区间车与全程车分开。至少看到3班车；离开站台或漏车时结束本组。",
                    style = MaterialTheme.typography.bodySmall)
                if (active == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("确认第一班是系统预计 ${TimeUtils.formatSecondsOfDay(item.arrivalSecondsOfDay)} 的车",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = confirmed, onClick = {
                        runCatching { HeadwayLearning.start(item, Calendar.getInstance()) }
                            .onSuccess { onSave(it); confirmed = false; error = null }
                            .onFailure { error = "该班时间已相差超过10分钟，请关闭窗口重新选择班次" }
                    }) { Text("第一班已到站，开始记录") }
                } else {
                    Text("本组已记录 ${active.points.size} 班 · ${active.serviceDate}")
                    active.nextExpectedEpochMillis?.let { epoch ->
                        val expected = Calendar.getInstance().apply { timeInMillis = epoch }
                        Text("下一班预计 ${TimeUtils.formatSecondsOfDay(ServiceTypeResolver.secondsOfDay(expected))} 到站",
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(active.points.joinToString(" → ") { "%s:%02d".format(TimeUtils.formatSecondsOfDay(it.secondsOfDay), it.secondsOfDay % 60) },
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("确认上一班到这一班之间没有漏车", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = confirmed, onClick = {
                        val clock = Calendar.getInstance()
                        error = HeadwayLearning.appendError(active, clock, confirmed)
                        if (error == null) {
                            onSave(HeadwayLearning.append(active, clock, confirmed))
                            confirmed = false
                        }
                    }) { Text("下一班已到站") }
                    TextButton(onClick = { onSave(active.copy(ended = true)); error = null }) {
                        Text("有漏车 / 离开站台，结束本组")
                    }
                    Text("关闭窗口不会丢失本组；返回相同交路的记录入口可以继续。", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val clock = Calendar.getInstance()
                Text(HeadwayLearning.report(relevant, ServiceTypeResolver.from(clock),
                    ServiceTypeResolver.secondsOfDay(clock), clock.timeInMillis), style = MaterialTheme.typography.bodySmall)
                Text(HeadwayLearning.forecastAccuracy(relevant, clock.timeInMillis), style = MaterialTheme.typography.bodySmall)
                relevant.takeLast(5).reversed().forEach { session ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${session.serviceDate} · ${session.points.size}班 · ${if (session.ended) "已结束" else "进行中"}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onRemove(session.id) }) { Text("删除本组") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
