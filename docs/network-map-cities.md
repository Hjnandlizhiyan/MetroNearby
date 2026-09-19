# 线网图城市选择

线网图页面顶部提供“选城市”窗口。当前目录只有北京，用户选择会保存到 `metro_settings.xml` 的 `network_map_city_id`，再次进入线网图时沿用上次选择。

城市元数据集中在 `domain/NetworkMapCatalog.kt`，包含稳定 id、省级名称、城市名称、标题、来源和更新时间；图片资源映射在 `ui/NetworkMapScreen.kt#mapResourceId`。后续加入其它省市时：

1. 把地图图片放入 Android drawable 资源；
2. 在 `NetworkMapCatalog.available` 增加城市元数据；
3. 在 `mapResourceId` 增加该 id 对应的图片资源。

查看器会在城市变化时销毁旧图片视图并为新资源重新建立分块解码器，缩放、拖动、双击和“看全图”行为保持一致。
