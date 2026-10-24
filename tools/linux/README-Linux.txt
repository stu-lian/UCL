Union Craft Launcher — Linux x64
================================

启动
  1. 在终端进入本目录
  2. chmod +x start.sh
  3. ./start.sh

也可以直接运行：
  bash start.sh

首次启动会把内置的 Java 21 运行时解压到 runtime/，需要几百 MB 磁盘空间。
之后启动不会重复解压。

数据目录
  data/       配置、账户、日志和缓存
  minecraft/  游戏数据：assets、libraries、versions、instances

这两个目录都在 start.sh 旁边，整个文件夹可以直接移动。
启动器窗口需要 Chrome / Chromium / Edge / Brave 之一；
如果找不到 Chromium 浏览器，会退回系统默认浏览器打开界面。
微软正版验证始终使用系统默认浏览器。

依赖
  tar、bash，以及至少一个 Chromium 浏览器。
  Minecraft 26.x 需要 Java 25；启动器会自动下载 Mojang 官方 Linux 运行时。
