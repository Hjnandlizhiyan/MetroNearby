# 底部工具栏与北京地铁线网图

落地日期：2026-09-19。

## 入口与安全区

`MetroBottomDock.kt` 提供首页、订阅、班次表、区间车、选线路、线网图六个稳定入口。底栏显式使用 Android 导航栏安全区，再增加 8dp 外边距；全面屏手势条、三键导航栏高度变化时都会自动抬高，不写死机型尺寸。

工具栏图标全部由 Compose Canvas 绘制，没有新增 material-icons 或其他第三方依赖。入口由 `DockDestination` 集中登记，后续添加地铁素材页面时沿用同一导航结构。

## 线网图来源

- 官方页面：https://www.mtr.bj.cn/article/line
- 页面标注日期：2026-05-16
- 官方原图：https://cdnwww.mtr.bj.cn/bjmtr/default/mxFXoKAXCCYv61DjKHdzl.jpg
- 工程资源：`app/src/main/res/drawable-nodpi/beijing_subway_network_map.jpg`
- 原图规格：6564×6537，JPEG，8,484,121 字节

官网当前下载项是高清 JPEG，没有提供 SVG/PDF 矢量下载。应用保留官方原图，不二次缩小；界面初始用 1/4 采样预览，拖动或缩放停止后再通过 `BitmapRegionDecoder` 从原图解码当前可见区域，解码完成立即释放解码器。这样放大时能读取原始像素，同时不会将约 4300 万像素整张展开到内存。模拟器实测首页与放大后的线网图总 PSS 分别约 98 MB、104 MB，没有留下整图解码的常驻开销。

## 浏览方式

- 双指缩放，最高 8 倍；
- 单指拖动；
- 双击在 3 倍与全图之间切换；
- 顶栏“看全图”恢复初始位置；
- 线网图完全离线，应用仍不需要 `INTERNET` 权限。
