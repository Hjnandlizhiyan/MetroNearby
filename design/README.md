# 应用图标

`metro-nearby-app-icon.png` 是应用图标的高分辨率母图，使用正方形画布和透明圆角，主体位于 Android 自适应图标安全区域内。

图标表现为蓝色圆角方形、正面地铁列车、简洁二次元乘务员，以及向上弯曲的青色线路。线路右侧的珊瑚色站点圆点用于表达“附近站点”。

Android 资源分为两组：

- `mipmap-*/ic_launcher.png` 与 `ic_launcher_round.png`：供旧系统和不支持自适应图标的桌面使用。
- `drawable-*/ic_launcher_foreground.png`：供 Android 8.0 及以上的自适应图标使用；桌面可以将其裁成圆角方形、圆形等形状。

`drawable/ic_launcher_monochrome.xml` 为 Android 13 及以上的主题色图标轮廓。

启动加载画面在 Android 12 及以上使用系统 SplashScreen，并在旧系统通过 `drawable/launch_screen.xml` 显示同一图标。应用在桌面和系统设置中的名称统一为 **Metro Nearby**。

`drawable-nodpi/ic_splash_icon.png` 是单独的启动图标，四周保留额外透明安全区，避免系统再次套用圆形遮罩时裁掉列车车身。桌面图标不使用这份留白资源。
