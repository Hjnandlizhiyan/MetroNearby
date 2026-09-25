# 离线拼音索引生成

需要 Python 3 与开发依赖 pypinyin==0.55.0（可安装在临时目录或虚拟环境）。
在仓库根目录运行 python tools/search-index/generate.py。

脚本读取全部 line_beijing_*.json 的站名和 aliases，生成 domain/StationPinyin.kt。
输出是拼音检索词，不是官方英文站名。重点审核地名多音字；例外在脚本中明确维护。
不改线路 JSON，不改变 Android 依赖。详细范围见 docs/station-search-tools.md。
