# Sync MINIMAX_API_KEY -> ../application-local.properties (gitignored).
# Save this file as UTF-8 if you add non-ASCII comments; messages below are ASCII-only for PS 5.1.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $repo 'application-local.properties'

function Read-MinimaxKey {
    $p = [Environment]::GetEnvironmentVariable('MINIMAX_API_KEY', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($p)) { return $p.Trim() }

    $u = [Environment]::GetEnvironmentVariable('MINIMAX_API_KEY', 'User')
    if (-not [string]::IsNullOrWhiteSpace($u)) { return $u.Trim() }

    $m = [Environment]::GetEnvironmentVariable('MINIMAX_API_KEY', 'Machine')
    if (-not [string]::IsNullOrWhiteSpace($m)) { return $m.Trim() }

    $hkcu = 'HKCU:\Environment'
    try {
        $row = Get-ItemProperty -LiteralPath $hkcu -Name 'MINIMAX_API_KEY' -ErrorAction Stop
        if ($row.MINIMAX_API_KEY) { return [string]$row.MINIMAX_API_KEY.Trim() }
    } catch { }

    $hklm = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment'
    try {
        $row2 = Get-ItemProperty -LiteralPath $hklm -Name 'MINIMAX_API_KEY' -ErrorAction Stop
        if ($row2.MINIMAX_API_KEY) { return [string]$row2.MINIMAX_API_KEY.Trim() }
    } catch { }

    return $null
}

$k = Read-MinimaxKey
if ([string]::IsNullOrWhiteSpace($k)) {
    Write-Host ''
    Write-Host 'MINIMAX_API_KEY not found. Check:' -ForegroundColor Yellow
    Write-Host '  1) Exact name: MINIMAX_API_KEY'
    Write-Host '  2) After editing System Environment, close and reopen PowerShell'
    Write-Host '  3) Or set for this session only, then run this script again:'
    Write-Host "       `$env:MINIMAX_API_KEY = '<paste-your-key-here>'"
    Write-Host '  4) Or create application-local.properties next to pom.xml with:'
    Write-Host '       qa.assistant.api-key=<your-key>'
    Write-Host ''
    exit 2
}

$escaped = $k.Replace('\', '\\')
$body = "# synced (gitignored; do not commit)`r`nqa.assistant.api-key=$escaped"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($target, $body, $utf8NoBom)
Write-Host "OK: wrote $target (key length $($k.Length) chars)" -ForegroundColor Green
