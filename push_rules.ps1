# adskip rule hot-update & debug script (via adb broadcast, no rebuild/install needed)
# Usage:
#   .\push_rules.ps1 set -File rules.json          replace all rules from JSON file
#   .\push_rules.ps1 set -Rules '<json>'           ...or pass JSON string directly
#   .\push_rules.ps1 clear                         clear all rules
#   .\push_rules.ps1 clearPkg -Pkg cn.wenyu.bodian clear rules of one package
#   .\push_rules.ps1 dump                          print rule store
#   .\push_rules.ps1 scan                          trigger a foreground scan now
#   .\push_rules.ps1 pause                         pause whole service (emergency stop)
#   .\push_rules.ps1 resume                        resume service
#   .\push_rules.ps1 state                         print service state
#   .\push_rules.ps1 tree                          dump foreground window node tree
#   .\push_rules.ps1 trace [-Off]                  enable match-level trace (default on) / -Off disable
#   .\push_rules.ps1 hist                          dump recent action history
#   .\push_rules.ps1 fired                         show session-fired rule set
#   .\push_rules.ps1 firedReset                    clear session-fired set, re-arm tracking
param(
  [Parameter(Mandatory = $true)][ValidateSet("set", "clear", "clearPkg", "dump", "scan", "pause", "resume", "state", "tree", "trace", "hist", "fired", "firedReset")][string]$Mode,
  [string]$File = "",
  [string]$Rules = "",
  [string]$Pkg = "",
  [switch]$Off
)
$adb = "C:\Users\2504\AppData\Local\Android\Sdk\platform-tools\adb.exe"

switch ($Mode) {
  "set" {
    if ($File) {
      if (-not (Test-Path $File)) { Write-Host "file not found: $File"; exit 1 }
      $json = (Get-Content $File -Raw -Encoding UTF8).Trim()
    } else {
      $json = $Rules
    }
    if (-not $json) { Write-Host "rules json empty"; exit 1 }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $b64 = [Convert]::ToBase64String($bytes)
    Write-Host "send SET_RULES (base64 len $($b64.Length))"
    & $adb shell am broadcast -a com.simely.adskip.action.SET_RULES --es rules $b64
  }
  "clear" {
    & $adb shell am broadcast -a com.simely.adskip.action.CLEAR_RULES
  }
  "clearPkg" {
    if (-not $Pkg) { Write-Host "use -Pkg to specify package"; exit 1 }
    & $adb shell am broadcast -a com.simely.adskip.action.CLEAR_RULES_PKG --es pkg $Pkg
  }
  "scan" {
    & $adb shell am broadcast -a com.simely.adskip.action.SCAN_NOW
  }
  "pause" {
    & $adb shell am broadcast -a com.simely.adskip.action.PAUSE
  }
  "resume" {
    & $adb shell am broadcast -a com.simely.adskip.action.RESUME
  }
  "state" {
    & $adb shell am broadcast -a com.simely.adskip.action.DUMP_STATE
  }
  "tree" {
    & $adb shell am broadcast -a com.simely.adskip.action.DUMP_TREE
  }
  "dump" {
    & $adb shell am broadcast -a com.simely.adskip.action.DUMP_RULES
  }
  "trace" {
    if ($Off) {
      & $adb shell am broadcast -a com.simely.adskip.action.TRACE --ez on 0
    } else {
      & $adb shell am broadcast -a com.simely.adskip.action.TRACE --ez on 1
    }
  }
  "hist" {
    & $adb shell am broadcast -a com.simely.adskip.action.DUMP_HISTORY
  }
  "fired" {
    & $adb shell am broadcast -a com.simely.adskip.action.DUMP_FIRED
  }
  "firedReset" {
    & $adb shell am broadcast -a com.simely.adskip.action.RESET_FIRED
  }
}

# read back logcat for service-level commands (their output uses [dj] tag)
if ($Mode -in "scan", "pause", "resume", "state", "tree", "dump", "trace", "hist", "fired", "firedReset") {
  Start-Sleep -Milliseconds 900
  & $adb logcat -d -v brief | Select-String -Pattern "AdSkip" | Select-Object -Last 40 | ForEach-Object { $_.Line }
}