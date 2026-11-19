# System watcher: microphone/camera in use, audio devices, removable drives.
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Emit($o) {
  [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress -Depth 4))
  [Console]::Out.Flush()
}

function InUse($cap) {
  $base = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\' + $cap
  $keys = @(Get-ChildItem $base) + @(Get-ChildItem ($base + '\NonPackaged'))
  foreach ($k in $keys) {
    $p = Get-ItemProperty $k.PSPath
    if ($p -and $null -ne $p.LastUsedTimeStart -and $null -ne $p.LastUsedTimeStop) {
      if ([int64]$p.LastUsedTimeStart -gt 0 -and [int64]$p.LastUsedTimeStop -eq 0) { return $true }
    }
  }
  return $false
}

$i = 0
while ($true) {
  $o = @{ t = 'sys'; mic = (InUse 'microphone'); cam = (InUse 'webcam') }
  if ($i % 3 -eq 0) {
    $o.audio = @(Get-PnpDevice -Class AudioEndpoint -PresentOnly -Status OK | ForEach-Object { $_.FriendlyName } | Where-Object { $_ } | Sort-Object -Unique)
    $o.disks = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=2' | ForEach-Object { ($_.DeviceID + ' ' + $_.VolumeName).Trim() })
  }
  Emit $o
  $i++
  Start-Sleep -Seconds 2
}
