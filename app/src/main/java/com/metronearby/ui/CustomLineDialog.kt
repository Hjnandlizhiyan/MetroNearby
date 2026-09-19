package com.metronearby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.MetroLine
import com.metronearby.domain.CustomLineBuilder
import com.metronearby.domain.CustomLineInput

@Composable
fun CustomLineDialog(
    onDismiss: () -> Unit,
    onConfirm: (MetroLine) -> Unit
) {
    var cityName by remember { mutableStateOf("北京") }
    var lineName by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#2F80ED") }
    var stationsText by remember { mutableStateOf("") }
    var firstDeparture by remember { mutableStateOf("06:00") }
    var lastDeparture by remember { mutableStateOf("23:00") }
    var intervalMinutes by remember { mutableStateOf("6") }
    var runMinutes by remember { mutableStateOf("2") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义线路") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "每行填写一个站：站名,纬度,经度。保存后可在“班次表”中分别修改工作日、周末和同一趟车的沿途时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(cityName, { cityName = it }, label = { Text("城市") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lineName, { lineName = it }, label = { Text("线路名称") }, singleLine = true,
                    placeholder = { Text("例如：机场接驳线") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(color, { color = it }, label = { Text("线路颜色") }, singleLine = true,
                    placeholder = { Text("#2F80ED") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = stationsText,
                    onValueChange = { stationsText = it },
                    label = { Text("站点与坐标") },
                    placeholder = { Text("甲站,39.9059,116.3505\n乙站,39.9142,116.4074") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(firstDeparture, { firstDeparture = it }, label = { Text("首班") },
                    placeholder = { Text("06:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lastDeparture, { lastDeparture = it }, label = { Text("末班") },
                    placeholder = { Text("23:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(intervalMinutes, { intervalMinutes = it.filter(Char::isDigit) },
                    label = { Text("初始发车间隔（分钟）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(runMinutes, { runMinutes = it.filter(Char::isDigit) },
                    label = { Text("每站初始运行时间（分钟）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (stations, parseError) = CustomLineBuilder.parseStations(stationsText)
                if (parseError != null || stations == null) {
                    error = parseError
                    return@TextButton
                }
                val result = CustomLineBuilder.build(
                    CustomLineInput(
                        stableId = System.currentTimeMillis().toString(36),
                        cityName = cityName,
                        lineName = lineName,
                        color = color,
                        stations = stations,
                        firstDeparture = firstDeparture,
                        lastDeparture = lastDeparture,
                        intervalMinutes = intervalMinutes.toIntOrNull() ?: 0,
                        runMinutesPerStop = runMinutes.toIntOrNull() ?: 0
                    )
                )
                when (result) {
                    is CustomLineBuilder.Result.Built -> onConfirm(result.line)
                    is CustomLineBuilder.Result.Rejected -> error = result.reason
                }
            }) { Text("创建线路") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
