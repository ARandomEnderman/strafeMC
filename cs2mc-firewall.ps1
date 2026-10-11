# Run ONCE in an elevated (Administrator) PowerShell. Claude does not run this.
# Blocks the CS2 copy's executables from every remote address except loopback (127.0.0.0/8, ::1)
# and local LAN (10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7), in both directions.
# Block rules override any allow rule (including ones Windows creates from its "allow access" prompt).

$copyBin = 'C:\Program Files (x86)\Steam\steamapps\common\Counter-Strike Global Offensive - MINECRAFT INTEGRATION\game\bin\win64'
$exes = @("$copyBin\cs2.exe", "$copyBin\vconsole2.exe")

$v4 = '0.0.0.0-9.255.255.255', '11.0.0.0-126.255.255.255', '128.0.0.0-169.253.255.255',
      '169.255.0.0-172.15.255.255', '172.32.0.0-192.167.255.255', '192.169.0.0-255.255.255.255'
$v6 = '::2-fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff', 'fe00::-fe7f:ffff:ffff:ffff:ffff:ffff:ffff:ffff',
      'fec0::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff'

# Inbound is loopback-only: netcon (29015) and VConsole (29000) bind 0.0.0.0 with no password, so LAN must not reach them.
$inAll = '0.0.0.0-126.255.255.255', '128.0.0.0-255.255.255.255', '::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff'

foreach ($exe in $exes) {
  $n = [IO.Path]::GetFileNameWithoutExtension($exe)
  foreach ($dir in 'Outbound', 'Inbound') {
    $ranges = if ($dir -eq 'Inbound') { $inAll } else { $v4 + $v6 }
    $what = if ($dir -eq 'Inbound') { 'all but loopback' } else { 'all but loopback/LAN' }
    Remove-NetFirewallRule -Name "CS2MC-COPY-$n-$dir" -ErrorAction SilentlyContinue
    New-NetFirewallRule -Name "CS2MC-COPY-$n-$dir" -DisplayName "CS2MC copy: block $n $dir ($what)" `
      -Direction $dir -Action Block -Program $exe -Protocol Any -Profile Any `
      -RemoteAddress $ranges -Enabled True | Out-Null
  }
}
Get-NetFirewallRule -Name 'CS2MC-COPY-*' | Format-Table Name, Enabled, Direction, Action -AutoSize
