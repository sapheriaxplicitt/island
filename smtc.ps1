# Reads Windows media session (SMTC): browser, Spotify app, etc. Works in Windows PowerShell 5.1.
# Prints one JSON line per ~0.5 s. Reads commands from stdin: play, pause, next, prev, seek <ms>.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Emit($o) {
  [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress -Depth 4))
  [Console]::Out.Flush()
}

try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
  $null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
} catch {
  Emit @{ t = 'error'; msg = 'winrt-load: ' + $_.Exception.Message }
  exit 1
}

$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  })[0]

function Await($op, $type) {
  try {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(4000)
    if ($task.IsCompleted) { return $task.Result }
  } catch { }
  return $null
}

$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
if (-not $mgr) {
  Emit @{ t = 'error'; msg = 'smtc-unavailable' }
  exit 1
}

# stdin reader in a separate runspace -> queue
$q = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
$rs = [runspacefactory]::CreateRunspace()
$rs.Open()
$rs.SessionStateProxy.SetVariable('q', $q)
$ps = [powershell]::Create()
$ps.Runspace = $rs
$null = $ps.AddScript({
    while ($true) {
      $l = [Console]::In.ReadLine()
      if ($null -eq $l) { break }
      $q.Enqueue($l)
    }
  }).BeginInvoke()

function Pick {
  foreach ($x in $mgr.GetSessions()) {
    $pi = $x.GetPlaybackInfo()
    if ($pi -and $pi.PlaybackStatus.ToString() -eq 'Playing') { return $x }
  }
  return $mgr.GetCurrentSession()
}

function HandleCmd($c) {
  $s = Pick
  if (-not $s) { return }
  $parts = $c.Trim().Split(' ')
  switch ($parts[0]) {
    'play'  { $null = Await ($s.TryPlayAsync()) ([bool]) }
    'pause' { $null = Await ($s.TryPauseAsync()) ([bool]) }
    'next'  { $null = Await ($s.TrySkipNextAsync()) ([bool]) }
    'prev'  { $null = Await ($s.TrySkipPreviousAsync()) ([bool]) }
    'seek'  {
      $ms = [double]$parts[1]
      $null = Await ($s.TryChangePlaybackPositionAsync([long]($ms * 10000))) ([bool])
    }
  }
}

function Drain {
  $c = $null
  while ($q.TryDequeue([ref]$c)) {
    try { HandleCmd $c } catch { }
  }
}

function ReadThumb($ref) {
  try {
    $st = Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    if (-not $st) { return $null }
    $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st)
    $ms = New-Object System.IO.MemoryStream
    $net.CopyTo($ms)
    $mime = $st.ContentType
    if (-not $mime) { $mime = 'image/jpeg' }
    $st.Dispose()
    return ('data:' + $mime + ';base64,' + [Convert]::ToBase64String($ms.ToArray()))
  } catch { return $null }
}

$lastKey = ''
$artDone = $false
$artTries = 0

while ($true) {
  Drain
  try {
    $s = Pick
    if (-not $s) {
      Emit @{ t = 'media'; none = $true }
    } else {
      $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
      if (-not $props -or (-not $props.Title -and -not $props.Artist)) {
        Emit @{ t = 'media'; none = $true }
      } else {
        $key = $props.Title + '|' + $props.Artist + '|' + $props.AlbumTitle
        if ($key -ne $lastKey) { $lastKey = $key; $artDone = $false; $artTries = 0 }

        $newArt = $null
        if (-not $artDone) {
          if ($props.Thumbnail) {
            $newArt = ReadThumb $props.Thumbnail
            $artDone = $true
          } else {
            $artTries++
            if ($artTries -gt 12) { $artDone = $true }
          }
        }

        $status = $s.GetPlaybackInfo().PlaybackStatus.ToString()
        $tl = $s.GetTimelineProperties()
        $dur = [math]::Round(($tl.EndTime - $tl.StartTime).TotalMilliseconds)
        $pos = [math]::Round($tl.Position.TotalMilliseconds)
        if ($status -eq 'Playing' -and $dur -gt 0) {
          $age = ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
          if ($age -gt 0 -and $age -lt 21600000) { $pos = $pos + $age }
        }
        if ($dur -gt 0 -and $pos -gt $dur) { $pos = $dur }

        $o = @{
          t = 'media'; key = $key; app = [string]$s.SourceAppUserModelId
          title = [string]$props.Title; artist = [string]$props.Artist; album = [string]$props.AlbumTitle
          status = $status; pos = [math]::Round($pos); dur = $dur
        }
        if ($newArt) { $o.art = $newArt }
        Emit $o
      }
    }
  } catch {
    Emit @{ t = 'error'; msg = $_.Exception.Message }
  }
  for ($i = 0; $i -lt 10; $i++) {
    Drain
    Start-Sleep -Milliseconds 50
  }
}
