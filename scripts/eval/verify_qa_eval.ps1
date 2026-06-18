#requires -Version 5.1
<#
.SYNOPSIS
    P0 门禁(Step1)：一键完成"启动应用 → 等待就绪 → 跑 eval → 关停 → 透传退出码"。

.DESCRIPTION
    阶段一回归门禁。设计目标：
      - 跑通整个 QA 评估闭环
      - 退出码透传到调用方（CI、本地手动）
      - eval 跑完自动落 baseline CSV（首次跑）

    退出码（与 run_qa_eval.py 对齐）：
      0  全部 pass + 满足 fail-under
      2  有 case fail 但通过率 ≥ fail-under（部分失败）
      3  通过率 < fail-under（门禁触发，Step1 预期红）
      4  --must-pass 命中的 case 有 fail
      5  启动/就绪失败（Maven 没起来、端口被占）

.PARAMETER FailUnder
    通过率下限（百分数，默认 80，对齐 architecture-and-future-plan.md §5.1）

.PARAMETER MustPass
    逗号分隔的 case id 列表，这些 case 必须 pass 才能 exit 0

.PARAMETER Baseline
    baseline CSV 路径；与本次结果比对 regressed

.PARAMETER SkipStart
    跳过 Maven 启动/关停，假设应用已在跑（开发迭代用）

.PARAMETER BaselinePath
    baseline CSV 落盘路径（首次跑会落，第二次跑不覆盖；与 --baseline 区分）

.EXAMPLE
    pwsh ./scripts/eval/verify_qa_eval.ps1
.EXAMPLE
    pwsh ./scripts/eval/verify_qa_eval.ps1 -FailUnder 90 -MustPass "q01_person_role_list,q02_followup_cert"
.EXAMPLE
    pwsh ./scripts/eval/verify_qa_eval.ps1 -SkipStart
#>
param(
    [int]$FailUnder = 80,
    [string]$MustPass = "",
    [string]$Baseline = "",
    [string]$BaselinePath = "data/eval/qa_eval_baseline.csv",
    [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
$script:RepoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$script:SpringLog = Join-Path $script:RepoRoot "data\qa_logs\spring_run.log"
$script:AppPort = 8080
$script:AppReadyUrl = "http://localhost:$($script:AppPort)/qa/assistant/runtime-summary"
$script:MaxWaitSec = 90
$script:MaxStopWaitSec = 15

# --- helpers -----------------------------------------------------------------

function Test-PortInUse {
    param([int]$Port)
    $conn = Test-NetConnection -ComputerName localhost -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
    return $conn
}

function Wait-AppReady {
    param([string]$Url, [int]$TimeoutSec)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 500) {
                Write-Host "  app ready after $([int]$sw.Elapsed.TotalSeconds)s"
                return $true
            }
        } catch {
            # 还没起来
        }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Stop-SpringBoot {
    # 杀 Maven + 占用端口的 java 进程
    Get-Process -Name mvn, java -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "  killing PID $($_.Id) ($($_.ProcessName))"
        try { $_.Kill() } catch {}
    }
    # 兜底：杀占用端口的进程
    $conn = Get-NetTCPConnection -LocalPort $script:AppPort -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conn) {
        $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($p) {
            Write-Host "  killing port owner PID $($p.Id) ($($p.ProcessName))"
            try { $p.Kill() } catch {}
        }
    }
    # 等待端口释放
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $script:MaxStopWaitSec) {
        if (-not (Test-PortInUse -Port $script:AppPort)) { return }
        Start-Sleep -Seconds 1
    }
}

# --- main --------------------------------------------------------------------

$exitCode = 0
$bootStarted = $false
$mvnProc = $null

try {
    Write-Host "== verify_qa_eval.ps1 =="
    Write-Host "RepoRoot:  $($script:RepoRoot)"
    Write-Host "FailUnder: $FailUnder"
    Write-Host "MustPass:  $MustPass"
    Write-Host "Baseline:  $(if ($Baseline) { $Baseline } else { '(none)' })"
    Write-Host "SkipStart: $SkipStart"
    Write-Host ""

    if (-not $SkipStart) {
        if (Test-PortInUse -Port $script:AppPort) {
            Write-Host "[error] port $script:AppPort already in use. Stop the existing process or run with -SkipStart" -ForegroundColor Red
            $exitCode = 5
            return
        }

        Write-Host "[1/4] starting Spring Boot (mvn spring-boot:run)..."
        $mvnCmd = Join-Path $script:RepoRoot "mvnw.cmd"
        if (-not (Test-Path $mvnCmd)) { $mvnCmd = "mvn" }
        New-Item -ItemType Directory -Force -Path (Split-Path $script:SpringLog) | Out-Null
        $mvnProc = Start-Process -FilePath $mvnCmd -ArgumentList "spring-boot:run" `
            -WorkingDirectory $script:RepoRoot `
            -RedirectStandardOutput $script:SpringLog `
            -RedirectStandardError "$($script:SpringLog).err" `
            -NoNewWindow -PassThru
        $bootStarted = $true
        Write-Host "  mvn pid=$($mvnProc.Id), log=$($script:SpringLog)"

        Write-Host "[2/4] waiting for app ready (timeout $($script:MaxWaitSec)s)..."
        $ready = Wait-AppReady -Url $script:AppReadyUrl -TimeoutSec $script:MaxWaitSec
        if (-not $ready) {
            Write-Host "[error] app did not become ready in $($script:MaxWaitSec)s" -ForegroundColor Red
            Write-Host "  tail of spring_run.log:"
            if (Test-Path $script:SpringLog) {
                Get-Content $script:SpringLog -Tail 30 | ForEach-Object { Write-Host "  | $_" }
            }
            $exitCode = 5
            return
        }
    } else {
        Write-Host "[1/4] -SkipStart set, assuming app is already up"
        if (-not (Test-PortInUse -Port $script:AppPort)) {
            Write-Host "[error] port $script:AppPort not listening and -SkipStart was given" -ForegroundColor Red
            $exitCode = 5
            return
        }
    }

    # --- run eval -----------------------------------------------------------
    Write-Host "[3/4] running run_qa_eval.py..."
    $argsList = @("scripts/eval/run_qa_eval.py", "--fail-under", "$FailUnder")
    if ($MustPass) { $argsList += @("--must-pass", $MustPass) }
    if ($Baseline) { $argsList += @("--baseline", $Baseline) }
    $python = (Get-Command python -ErrorAction SilentlyContinue)
    if (-not $python) {
        Write-Host "[error] python not on PATH" -ForegroundColor Red
        $exitCode = 5
        return
    }
    Push-Location $script:RepoRoot
    try {
        & python @argsList
        $evalExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    Write-Host "  eval exit code: $evalExit"
    $exitCode = $evalExit

    # --- 自动落 baseline ----------------------------------------------------
    if ($BaselinePath) {
        $absBase = Join-Path $script:RepoRoot $BaselinePath
        $absResult = Join-Path $script:RepoRoot "data/eval/qa_eval_results.csv"
        if ((Test-Path $absResult) -and -not (Test-Path $absBase)) {
            Copy-Item $absResult $absBase -Force
            Write-Host "  baseline snapshot saved: $BaselinePath"
        } else {
            Write-Host "  baseline not updated (already exists or result missing)"
        }
    }
}
finally {
    Write-Host "[4/4] cleanup..."
    if ($bootStarted) {
        Stop-SpringBoot
    }
}

Write-Host ""
Write-Host "== verify_qa_eval.ps1 exit=$exitCode =="
exit $exitCode
