<#
.SYNOPSIS
    向 Android 模拟器/设备输入任意 Unicode 文本（含中文）。

.DESCRIPTION
    `adb shell input text` 只支持 ASCII，传中文会在系统层抛 NPE。
    本脚本改用 ADBKeyboard（commitText 通道）提交文本。

    两个必须注意的环境陷阱：
      1. 默认输入法常被 Gboard 抢回（ADBKeyboard 仍是 enabled 但不是 default），
         此时广播会被 Gboard 吞掉、出现乱码或只有零星字符 —— 故每次执行都重新 set。
      2. 3 键硬件键盘会抑制软键盘，导致 InputConnection 不活跃 —— 需打开
         show_ime_with_hard_keyboard，ADBKeyboard 才能提交成功。

    中文必须用 ADB_INPUT_B64（base64）传递；ADB_INPUT_TEXT 会被 shell 编码破坏。

.PARAMETER Text
    要输入的文本，可含中文/emoji。

.PARAMETER Serial
    目标设备序列号，默认 emulator-5554。

.PARAMETER Adb
    adb 可执行文件路径，默认取 LOCALAPPDATA 下的 Android SDK。

.PARAMETER NoClear
    保留输入框已有内容（默认先清空，保证结果可复现）。

.EXAMPLE
    .\type-text.ps1 "天安门"
    .\type-text.ps1 "西单" -NoClear
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Text,
    [string]$Serial = "emulator-5554",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$NoClear
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

$Ime = "com.android.adbkeyboard/.AdbIME"

$devices = (& $Adb devices | Out-String)
if ($devices -notmatch [regex]::Escape($Serial)) { throw "设备未连接：$Serial" }

$installed = & $Adb -s $Serial shell pm list packages | Select-String "com\.android\.adbkeyboard"
if (-not $installed) {
    $apk = Join-Path $PSScriptRoot "ADBKeyboard.apk"
    if (-not (Test-Path $apk)) {
        Write-Host "下载 ADBKeyboard.apk ..."
        Invoke-WebRequest -Uri "https://github.com/senzhk/ADBKeyBoard/raw/master/ADBKeyboard.apk" -OutFile $apk
    }
    & $Adb -s $Serial install -r $apk | Out-Null
    Start-Sleep -Seconds 3
}

for ($i = 0; $i -lt 5; $i++) {
    $out = & $Adb -s $Serial shell ime enable $Ime 2>&1
    if ($out -notmatch "Unknown input method") { break }
    Start-Sleep -Seconds 1
}
& $Adb -s $Serial shell ime set $Ime | Out-Null
& $Adb -s $Serial shell settings put secure show_ime_with_hard_keyboard 1
Start-Sleep -Milliseconds 500

$current = & $Adb -s $Serial shell settings get secure default_input_method
if ($current -notmatch "adbkeyboard") {
    throw "切换默认输入法失败，当前为：$current"
}

if (-not $NoClear) {
    & $Adb -s $Serial shell am broadcast -a ADB_CLEAR_TEXT | Out-Null
    Start-Sleep -Milliseconds 400
}

$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Text))
& $Adb -s $Serial shell am broadcast -a ADB_INPUT_B64 --es msg $b64 | Out-Null
Start-Sleep -Milliseconds 600

Write-Host "已输入：$Text"
Write-Host "提示：用完可执行 adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME 切回 Gboard"