package com.metronearby.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.R

private data class RoadmapItem(
    val title: String,
    val description: String
)

private data class RoadmapStage(
    val label: String,
    val color: Color,
    val summary: String,
    val items: List<RoadmapItem>
)

private val roadmapStages = listOf(
    RoadmapStage(
        label = "已实现 · 第一阶段",
        color = Color(0xFF2E7D32),
        summary = "这四项已可在底部“路线”和“雷达”中使用。",
        items = listOf(
            RoadmapItem("地铁方向助手", "选好目的地后，直接告诉用户乘哪条线、去哪个方向、在哪里换乘。"),
            RoadmapItem("附近站雷达", "上北下南展示车站方位、直线距离和线路，点击即可从此站规划。"),
            RoadmapItem("通勤快捷模式", "保存家、公司和学校等常用行程，命名收藏并设为通勤，一键查看或反向规划。"),
            RoadmapItem("离线行程卡", "把上车方向、经过站、换乘站和下车站整理成适合查看与截图的卡片。")
        )
    ),
    RoadmapStage(
        label = "第二阶段 · 逐步完善",
        color = Color(0xFF1565C0),
        summary = "出入口与设施、常用站收藏、站名工具和离线应急卡均已上线基础能力。",
        items = listOf(
            RoadmapItem("已实现基础版 · 出入口与设施", "车站详情可查看复兴门、积水潭和阜成门的部分官方资料；所有站均可保存个人出口、设施和换乘备注。"),
            RoadmapItem("已实现 · 常用站收藏升级", "为车站保存常用方向、目的地、出口、自定义备注和家或公司标签。"),
            RoadmapItem("已实现 · 站名与线路工具", "支持内置站名拼音、首字母、已有别名与错字建议；搜索区分城市和线路，路线可反转并突出换乘提醒。"),
            RoadmapItem("已实现 · 离线应急卡", "集中显示最近定位及时间、附近车站、最近规划路线、官方服务热线和自填联系信息；支持清除，拨号由用户确认。")
        )
    ),
    RoadmapStage(
        label = "有趣探索",
        color = Color(0xFF8E24AA),
        summary = "给熟悉的地铁增加收集和探索乐趣。",
        items = listOf(
            RoadmapItem("北京地铁足迹", "自动点亮到过的车站，记录线路探索进度、首次到访和特色徽章。"),
            RoadmapItem("随机探索", "随机推荐未去过的车站，可限制距离、线路或换乘次数。"),
            RoadmapItem("吉祥物成长", "随着车站和线路逐渐点亮，解锁吉祥物的新表情、装饰和纪念卡片。")
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FutureRoadmapScreen(
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("未来规划") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.mascot_metro_conductor),
                            contentDescription = "Metro Nearby 吉祥物",
                            modifier = Modifier.size(86.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "把 Metro Nearby 做成离线地铁随身助手",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "第一阶段已经实现；其余功能将根据数据条件和实际体验逐步升级。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
            items(roadmapStages) { stage ->
                RoadmapStageCard(stage)
            }
            item {
                Text(
                    "后续规划顺序可能调整。应用专注离线位置与出行，旧预测和时刻管理入口已移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun RoadmapStageCard(stage: RoadmapStage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(stage.color, RoundedCornerShape(5.dp))
                )
                Text(
                    stage.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                stage.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            stage.items.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}