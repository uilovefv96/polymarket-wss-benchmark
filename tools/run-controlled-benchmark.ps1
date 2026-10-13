#requires -Version 5.1
<#
.SYNOPSIS
Runs a Windows benchmark with fixed JVM resources and process metrics.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools/run-controlled-benchmark.ps1 -DryRun

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools/run-controlled-benchmark.ps1 -DurationSeconds 600 -ApplicationArgs '--benchmark.connections=2'
#>
[CmdletBinding()]
param(
    [string]$JarPath = "target/polymarket-wss-benchmark-1.0.0.jar",
    [string]$ReportRoot = "reports",
    [ValidateRange(1, 3600)]
    [int]$SampleIntervalSeconds = 1,
    [ValidateRange(0, 2147483647)]
    [int]$DurationSeconds = 0,
    [string]$JavaCommand = "java",
    [switch]$DryRun,
    [switch]$Help,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ApplicationArgs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$InvariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$Utf8Bom = New-Object System.Text.UTF8Encoding($true)

function Quote-ProcessArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Write-Utf8File([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, $Utf8Bom)
}

function Get-GitValue([string[]]$GitArgs, [string]$Fallback = "unknown") {
    try {
        $value = (& git -C $ProjectRoot @GitArgs 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $value) { return $value }
    } catch { }
    return $Fallback
}

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 0
}

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$ResolvedJarPath = if ([System.IO.Path]::IsPathRooted($JarPath)) {
    [System.IO.Path]::GetFullPath($JarPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $JarPath))
}
$ResolvedReportRoot = if ([System.IO.Path]::IsPathRooted($ReportRoot)) {
    [System.IO.Path]::GetFullPath($ReportRoot)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $ReportRoot))
}
$CpuQuota = 2
$RunId = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssfffZ", $InvariantCulture)
$RunDirectory = Join-Path $ResolvedReportRoot ("controlled-" + $RunId)
$GcLogPath = Join-Path $RunDirectory "gc.log"
$JfrPath = Join-Path $RunDirectory "recording.jfr"
$MetricsPath = Join-Path $RunDirectory "process-metrics.csv"
$MetadataPath = Join-Path $RunDirectory "metadata.json"

$JvmArgs = @(
    "-XX:ActiveProcessorCount=$CpuQuota",
    "-Xms512m",
    "-Xmx1200m",
    "-XX:MaxDirectMemorySize=256m",
    "-Xss512k",
    "-XX:+UseG1GC",
    "-Xlog:gc*:file=$($GcLogPath):time,uptime,level,tags:filecount=5,filesize=20m",
    "-XX:StartFlightRecording=filename=$($JfrPath),settings=profile,dumponexit=true",
    "-jar",
    $ResolvedJarPath
)
$AllJavaArgs = @($JvmArgs) + @($ApplicationArgs)
$DisplayCommand = (($JavaCommand) + " " + (($AllJavaArgs | ForEach-Object { Quote-ProcessArgument $_ }) -join " "))

if ($DryRun) {
    Write-Output "DRY RUN: Java will not start, no report files will be created, and no network access will occur."
    Write-Output "Project root: $ProjectRoot"
    Write-Output "Run directory: $RunDirectory"
    Write-Output "Metrics CSV: $MetricsPath"
    Write-Output "Metadata: $MetadataPath"
    Write-Output "Command: $DisplayCommand"
    if (-not (Test-Path -LiteralPath $ResolvedJarPath -PathType Leaf)) {
        Write-Warning "JAR does not exist; build it before an actual run: $ResolvedJarPath"
    }
    exit 0
}

if (-not (Test-Path -LiteralPath $ResolvedJarPath -PathType Leaf)) {
    throw "JAR does not exist: $ResolvedJarPath"
}
$JavaExecutable = Get-Command $JavaCommand -ErrorAction Stop
$JavaVersion = (& $JavaExecutable.Source -version 2>&1 | Out-String).Trim()

# Use a new directory per run so existing diagnostic files are never overwritten.
[System.IO.Directory]::CreateDirectory($RunDirectory) | Out-Null
Write-Utf8File $MetricsPath "timestamp_utc,cpu_core_equivalent_percent,cpu_percent_host,cpu_percent_quota,working_set_bytes,private_memory_bytes,thread_count,handle_count`r`n"

$StartTimeUtc = (Get-Date).ToUniversalTime()
$processStartInfo = New-Object System.Diagnostics.ProcessStartInfo
$processStartInfo.FileName = $JavaExecutable.Source
$processStartInfo.Arguments = (($AllJavaArgs | ForEach-Object { Quote-ProcessArgument $_ }) -join " ")
$processStartInfo.WorkingDirectory = $ProjectRoot
$processStartInfo.UseShellExecute = $false
$processStartInfo.CreateNoWindow = $true
$processStartInfo.RedirectStandardOutput = $false
$processStartInfo.RedirectStandardError = $false
$processStartInfo.EnvironmentVariables["JAVA_TOOL_OPTIONS"] = ""
$processStartInfo.EnvironmentVariables["JDK_JAVA_OPTIONS"] = ""

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $processStartInfo
$process.Start() | Out-Null

# Write metadata after startup so it contains the actual PID.
$GitBranch = Get-GitValue @("rev-parse", "--abbrev-ref", "HEAD")
$GitCommit = Get-GitValue @("rev-parse", "HEAD")
$GitAhead = Get-GitValue @("rev-list", "--count", "@{upstream}..HEAD") "unavailable"
$GitDirtyText = Get-GitValue @("status", "--porcelain") ""
$Metadata = [ordered]@{
    runId = $RunId
    startTimeUtc = $StartTimeUtc.ToString("o", $InvariantCulture)
    pid = $process.Id
    javaExecutable = $JavaExecutable.Source
    javaVersion = $JavaVersion
    jvmArguments = $JvmArgs
    applicationArguments = @($ApplicationArgs)
    command = $DisplayCommand
    sampleIntervalSeconds = $SampleIntervalSeconds
    durationSeconds = $DurationSeconds
    cpuQuota = $CpuQuota
    hostLogicalProcessors = [Environment]::ProcessorCount
    cpuMetricDefinitions = [ordered]@{
        cpuCoreEquivalentPercent = "100 means one logical CPU fully occupied"
        cpuPercentHost = "process CPU divided by host logical processor count"
        cpuPercentQuota = "process CPU divided by ActiveProcessorCount quota"
    }
    git = [ordered]@{
        branch = $GitBranch
        commit = $GitCommit
        ahead = $GitAhead
        dirty = [bool]$GitDirtyText
    }
    files = [ordered]@{
        runDirectory = $RunDirectory
        jar = $ResolvedJarPath
        gcLog = $GcLogPath
        jfr = $JfrPath
        processMetricsCsv = $MetricsPath
        metadata = $MetadataPath
    }
}
Write-Utf8File $MetadataPath ($Metadata | ConvertTo-Json -Depth 6)

Write-Output "Started PID $($process.Id); report directory: $RunDirectory"
$previousCpuSeconds = $process.TotalProcessorTime.TotalSeconds
$previousSampleUtc = [DateTime]::UtcNow
$logicalProcessors = [Environment]::ProcessorCount
$sampleCount = 0
$forcedTermination = $false

while (-not $process.HasExited) {
    Start-Sleep -Seconds $SampleIntervalSeconds
    $process.Refresh()
    if ($process.HasExited) { break }

    $sampleUtc = [DateTime]::UtcNow
    $cpuSeconds = $process.TotalProcessorTime.TotalSeconds
    $wallSeconds = ($sampleUtc - $previousSampleUtc).TotalSeconds
    $cpuCoreEquivalentPercent = if ($wallSeconds -gt 0) {
        100.0 * ($cpuSeconds - $previousCpuSeconds) / $wallSeconds
    } else { 0.0 }
    $cpuPercentHost = $cpuCoreEquivalentPercent / $logicalProcessors
    $cpuPercentQuota = $cpuCoreEquivalentPercent / $CpuQuota
    $line = [string]::Join(",", @(
        $sampleUtc.ToString("o", $InvariantCulture),
        $cpuCoreEquivalentPercent.ToString("F3", $InvariantCulture),
        $cpuPercentHost.ToString("F3", $InvariantCulture),
        $cpuPercentQuota.ToString("F3", $InvariantCulture),
        $process.WorkingSet64.ToString($InvariantCulture),
        $process.PrivateMemorySize64.ToString($InvariantCulture),
        $process.Threads.Count.ToString($InvariantCulture),
        $process.HandleCount.ToString($InvariantCulture)
    ))
    [System.IO.File]::AppendAllText($MetricsPath, $line + "`r`n", $Utf8Bom)
    $sampleCount++
    $previousCpuSeconds = $cpuSeconds
    $previousSampleUtc = $sampleUtc

    $elapsedSeconds = ($sampleUtc - $StartTimeUtc).TotalSeconds
    if (($DurationSeconds -gt 0) -and ($elapsedSeconds -ge $DurationSeconds)) {
        Write-Output "Duration limit of $DurationSeconds seconds reached; forcibly stopping PID $($process.Id)."
        $forcedTermination = $true
        $process.Kill()
        break
    }
}
$process.WaitForExit()
$EndTimeUtc = (Get-Date).ToUniversalTime()
$Metadata.endTimeUtc = $EndTimeUtc.ToString("o", $InvariantCulture)
$Metadata.exitCode = $process.ExitCode
$Metadata.sampleCount = $sampleCount
$Metadata.forcedTermination = $forcedTermination
$Metadata.jfrMayBeIncomplete = $forcedTermination -or -not (Test-Path -LiteralPath $JfrPath -PathType Leaf)
Write-Utf8File $MetadataPath ($Metadata | ConvertTo-Json -Depth 6)
Write-Output "Process exit code: $($process.ExitCode); samples: $sampleCount; forced termination: $forcedTermination"
exit $process.ExitCode
