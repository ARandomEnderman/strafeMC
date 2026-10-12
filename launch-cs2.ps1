#Requires -Version 5.1
<#
  launch-cs2.ps1 - the ONLY sanctioned way to start the private CS2 copy.
  Always invoke through launch-cs2.bat. Never start cs2.exe directly, never through Steam.

  Hard guarantees (it refuses to start or kills the process otherwise):
    * the executable resolves inside the CS2 copy, with no reparse points (symlink/junction) on the path,
      and never inside the real install
    * -dedicated -insecure +sv_lan 1 +ip 127.0.0.1 are present; nothing that could re-enable secure mode
      or connect anywhere is allowed in extra args
    * the CS2MC-COPY firewall block rules exist, are enabled, and are Block rules
    * Steam may be running (allowed since 2026-10-12); the server still never logs on to Steam or VAC
    * while running: every 500 ms, kill on any non-loopback/non-LAN TCP peer, kill on any console-log line
      showing secure mode / Steam logon, kill if insecure mode is not confirmed in the log within the timeout
    * if this script exits for any reason, the CS2 process is killed (it is never left unmonitored)
#>
param(
  [string]$Map = 'de_dust2',
  [int]$GamePort = 27015,
  [int]$NetconPort = 29015,
  [int]$ConfirmTimeoutSec = 120,
  [switch]$Metamod,
  [string[]]$ExtraArgs = @()
)
$ErrorActionPreference = 'Stop'

$CopyRoot = 'C:\Program Files (x86)\Steam\steamapps\common\Counter-Strike Global Offensive - MINECRAFT INTEGRATION'
$RealRoot = 'C:\Program Files (x86)\Steam\steamapps\common\Counter-Strike Global Offensive'
$Exe      = Join-Path $CopyRoot 'game\bin\win64\cs2.exe'
$CsgoDir  = Join-Path $CopyRoot 'game\csgo'
$RuleNames = @('CS2MC-COPY-cs2-Outbound', 'CS2MC-COPY-cs2-Inbound')

$LogDir = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$script:MonLog = Join-Path $LogDir ("launch-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))

function Log([string]$m, [string]$color = 'Gray') {
  $l = "[{0:HH:mm:ss.fff}] {1}" -f (Get-Date), $m
  Write-Host $l -ForegroundColor $color
  Add-Content -LiteralPath $script:MonLog -Value $l
}
function Fail([string]$m) { Log "REFUSING TO START: $m" 'Red'; exit 1 }

# ---------- 1. path safety ----------
$full = [IO.Path]::GetFullPath($Exe)
if ($full -ne $Exe) { Fail "exe path normalizes to '$full'" }
if (-not $full.StartsWith($CopyRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail "exe is outside the CS2 copy" }
if ($full.StartsWith($RealRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail "exe is inside the REAL install" }
if (-not (Test-Path -LiteralPath $full)) { Fail "exe not found: $full" }
$p = $full
while ($p) {
  $it = Get-Item -LiteralPath $p -Force
  if ($it.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail "reparse point (symlink/junction) on path: $p" }
  $p = Split-Path -Parent $p
}
Log "path check OK: $full (no reparse points)"

# ---------- 2. firewall rules present ----------
foreach ($n in $RuleNames) {
  $r = Get-NetFirewallRule -Name $n -ErrorAction SilentlyContinue
  if (-not $r) { Fail "firewall rule '$n' missing - run cs2mc-firewall.ps1 as admin first" }
  if ($r.Enabled -ne 'True' -or $r.Action -ne 'Block') { Fail "firewall rule '$n' not enabled/Block" }
  $prog = ($r | Get-NetFirewallApplicationFilter).Program
  if ($prog -ne $full) { Fail "firewall rule '$n' targets '$prog', not the copy exe" }
  $addr = ($r | Get-NetFirewallAddressFilter).RemoteAddress
  Log "firewall OK: $n ($($r.Direction), $(@($addr).Count) blocked remote ranges)"
}

# ---------- 3. environment ----------
$steamProcs = Get-Process -Name steam, steamwebhelper, steamservice -ErrorAction SilentlyContinue
if ($steamProcs) { Log "Steam is running ($(($steamProcs.Name | Sort-Object -Unique) -join ', ')): allowed; the server still runs insecure and LAN-only" 'Yellow' }
if (Get-Process -Name cs2 -ErrorAction SilentlyContinue) { Fail "a cs2.exe process is already running" }

# ---------- 4. arguments ----------
$forbidden = '(?i)(^[-+]?secure$|sv_lan|^[-+]?connect|^[-+]?retry|playcast|^[-+]?ip$|sv_setsteamaccount|^-steam|^\+?tv_|^-?gslt|matchmaking|^[-+]?hostip)'
foreach ($a in $ExtraArgs) { if ($a -match $forbidden) { Fail "forbidden extra arg '$a'" } }
$cmd = @('-dedicated', '-insecure', '-condebug', '-consolelog',
         '-port', "$GamePort", '-netconport', "$NetconPort",
         '-maxplayers', '16',
         '+ip', '127.0.0.1',
         '+sv_lan', '1',
         '+sv_hibernate_when_empty', '0',
         '+bot_quota', '0',          # no automatic bots; add them from the console (bot_add / bot_add_t / bot_add_ct)
         '+map', $Map) + $ExtraArgs
foreach ($req in '-dedicated', '-insecure') { if ($cmd -notcontains $req) { Fail "missing $req" } }
$i = [Array]::IndexOf($cmd, '+sv_lan'); if ($i -lt 0 -or $cmd[$i + 1] -ne '1') { Fail "+sv_lan 1 missing" }
$i = [Array]::IndexOf($cmd, '+ip');     if ($i -lt 0 -or $cmd[$i + 1] -ne '127.0.0.1') { Fail "+ip 127.0.0.1 missing" }
Log ("args: " + ($cmd -join ' '))

# ---------- 5. launch + monitor ----------
function IsLocalAddr([string]$a) {
  return ($a -match '^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|0\.0\.0\.0$|::1?$|fe[89ab][0-9a-f]:|f[cd][0-9a-f]{2}:)')
}
$secureRx  = '(?i)(VAC secure mode is activated|VAC secure mode enabled|Connection to Steam servers successful|logged on to Steam|SteamGameServer.*logged on|connected to game coordinator)'
$insecureRx = '(?i)(VAC secure mode disabled|insecure mode|running.*-insecure|VAC.*disabled)'
$interestRx = '(?i)(vac|secure|steam|coordinator|\bgc\b|netcon|rcon|sv_lan|listening|bind|port)'

# ---------- 4b. Metamod: only ever enabled for the lifetime of this wrapper ----------
# gameinfo.gi is restored from the pristine backup on every start (heals a crashed run) and on exit.
$GameInfo = Join-Path $CsgoDir 'gameinfo.gi'
$GameInfoOrig = Join-Path $CsgoDir 'gameinfo.gi.cs2mc-orig'
if (-not (Test-Path -LiteralPath $GameInfoOrig)) { Fail "pristine backup $GameInfoOrig missing" }
Copy-Item -LiteralPath $GameInfoOrig -Destination $GameInfo -Force
if ($Metamod) {
  $gi = [IO.File]::ReadAllText($GameInfo)
  $patched = [regex]::Replace($gi, '(?m)^(\s*)Game\s+csgo\s*$', "`$1Game`tcsgo/addons/metamod`r`n`$0", 1)
  if ($patched -eq $gi) { Fail "could not find 'Game csgo' line in gameinfo.gi" }
  [IO.File]::WriteAllText($GameInfo, $patched)
  Log "Metamod enabled for this run (gameinfo.gi patched; restored on exit)"
}

$start = Get-Date
$proc = Start-Process -FilePath $full -ArgumentList $cmd -WorkingDirectory (Split-Path $full) -PassThru
Log "started cs2.exe PID $($proc.Id)" 'Green'
$script:ProcId = $proc.Id

function KillIt([string]$why) {
  Log "KILLING PID $($script:ProcId): $why" 'Red'
  & taskkill /PID $script:ProcId /T /F | Out-Null
  exit 2
}

# CS2 never prints "VAC secure mode disabled"; the authoritative signal is `status` over netcon:
#   version : 1.41.9.0/14190 10924 insecure  public      steamid : [I:1:0] (not logged on)
function NetconStatus {
  try {
    $c = New-Object Net.Sockets.TcpClient; $c.ReceiveTimeout = 1500
    $c.Connect('127.0.0.1', $NetconPort); $s = $c.GetStream()
    $b = [Text.Encoding]::ASCII.GetBytes("status`n"); $s.Write($b, 0, $b.Length)
    $sb = New-Object Text.StringBuilder; $buf = New-Object byte[] 65536; $t = [DateTime]::Now
    while (([DateTime]::Now - $t).TotalMilliseconds -lt 1500 -and $sb.ToString() -notmatch '#end') {
      if ($s.DataAvailable) { $n = $s.Read($buf, 0, $buf.Length); [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $n)) } else { Start-Sleep -Milliseconds 20 }
    }
    $c.Close(); return $sb.ToString()
  } catch { return $null }
}
$lastStatus = [DateTime]::MinValue

$confirmed = $false; $logFile = $null; $logPos = 0; $seenBinds = @{}
try {
  while (-not $proc.HasExited) {
    # Steam must stay closed for the whole session, not just at launch
    $steamNow = Get-Process -Name steam, steamwebhelper, steamservice -ErrorAction SilentlyContinue
    if ($steamNow) { KillIt "Steam was started while CS2 was running ($(($steamNow.Name | Sort-Object -Unique) -join ', '))" }
    if (([DateTime]::Now - $lastStatus).TotalSeconds -ge 5) {
      $lastStatus = [DateTime]::Now
      $st = NetconStatus
      if ($st -and $st -match '(?m)^\s*version\s*:') {   # netcon accepts before the server is up; no version line = not ready yet
        $ver = ($st -split "`n" | Where-Object { $_ -match '^\s*version\s*:' }) -join ''
        $sid = ($st -split "`n" | Where-Object { $_ -match '^\s*steamid\s*:' }) -join ''
        if ($ver -notmatch '\binsecure\b') { Log "STATUS: $ver" 'Red'; KillIt "server status does not report insecure" }
        if ($sid -and $sid -notmatch '\[I:1:0\]') { Log "STATUS: $sid" 'Red'; KillIt "server has a Steam identity (logged on)" }
        if (-not $confirmed) { Log "insecure mode CONFIRMED by netcon status: $($ver.Trim()) | $($sid.Trim())" 'Green' }
        $confirmed = $true
      }
    }
    # TCP: any established/syn peer that is not loopback/LAN -> kill
    foreach ($c in (Get-NetTCPConnection -OwningProcess $proc.Id -ErrorAction SilentlyContinue)) {
      $k = "tcp $($c.LocalAddress):$($c.LocalPort) $($c.State) -> $($c.RemoteAddress):$($c.RemotePort)"
      if (-not $seenBinds[$k]) { $seenBinds[$k] = 1; Log "socket: $k" }
      if ($c.State -ne 'Listen' -and -not (IsLocalAddr $c.RemoteAddress)) { KillIt "external TCP peer $($c.RemoteAddress):$($c.RemotePort)" }
    }
    foreach ($u in (Get-NetUDPEndpoint -OwningProcess $proc.Id -ErrorAction SilentlyContinue)) {
      $k = "udp $($u.LocalAddress):$($u.LocalPort)"
      if (-not $seenBinds[$k]) { $seenBinds[$k] = 1; Log "socket: $k" }
    }
    # console log discovery + scan
    if (-not $logFile) {
      $cand = @(Get-ChildItem -LiteralPath $CsgoDir -Filter *.log -File -ErrorAction SilentlyContinue) +
              @(Get-ChildItem -LiteralPath (Join-Path $CsgoDir 'logs') -Filter *.log -File -ErrorAction SilentlyContinue) |
              Where-Object { $_.LastWriteTime -ge $start.AddSeconds(-2) } | Sort-Object LastWriteTime -Descending
      if ($cand) { $logFile = $cand[0].FullName; Log "console log: $logFile" }
    }
    if ($logFile) {
      $fs = [IO.File]::Open($logFile, 'Open', 'Read', 'ReadWrite')
      try {
        if ($fs.Length -gt $logPos) {
          $fs.Seek($logPos, 'Begin') | Out-Null
          $sr = New-Object IO.StreamReader($fs)
          $chunk = $sr.ReadToEnd(); $logPos = $fs.Length
          foreach ($line in ($chunk -split "`r?`n")) {
            if ($line -match $secureRx) { Log "LOG: $line" 'Red'; KillIt "console log shows secure session / Steam logon" }
            if ($line -match $insecureRx) { if (-not $confirmed) { Log "insecure mode CONFIRMED by log: $line" 'Green' }; $confirmed = $true }
            elseif ($line -match $interestRx) { Log "LOG: $line" }
          }
        }
      } finally { $fs.Close() }
    }
    if (-not $confirmed -and ((Get-Date) - $start).TotalSeconds -gt $ConfirmTimeoutSec) {
      KillIt "insecure mode not confirmed in console log within $ConfirmTimeoutSec s (log=$logFile)"
    }
    Start-Sleep -Milliseconds 500
  }
  Log "cs2.exe exited with code $($proc.ExitCode)"
} finally {
  if (-not $proc.HasExited) { Log "wrapper exiting - killing CS2 so it never runs unmonitored" 'Yellow'; & taskkill /PID $proc.Id /T /F | Out-Null; Start-Sleep 1 }
  Copy-Item -LiteralPath $GameInfoOrig -Destination $GameInfo -Force
  Log "gameinfo.gi restored (Metamod disabled)"
  Log "monitor log: $script:MonLog"
}
