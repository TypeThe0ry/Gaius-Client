#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $BuilderExe,
    [string] $OutDir = $env:GAIUS_BUILDER_OUT_DIR,
    [string] $TempDir = $env:GAIUS_BUILDER_TMP_DIR,
    [ValidateSet('dark', 'light')] [string] $Theme = 'dark',
    [ValidateSet('zh', 'en')] [string] $Lang = 'zh',
    [ValidateRange(1, 300)] [int] $TimeoutSeconds = 30,
    [switch] $Capture
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT' -or -not [Environment]::UserInteractive) {
    throw 'Run this script on an interactive Windows desktop.'
}
if (-not $OutDir) { throw 'Specify -OutDir or set GAIUS_BUILDER_OUT_DIR.' }
if (-not $TempDir) { $TempDir = [IO.Path]::GetTempPath() }
$exe = Get-Item -LiteralPath $BuilderExe
if ($exe -isnot [IO.FileInfo] -or $exe.Extension -ne '.exe') {
    throw '-BuilderExe must point to a native Windows .exe with --dpi-report support.'
}
$Theme = $Theme.ToLowerInvariant()
$Lang = $Lang.ToLowerInvariant()
$runName = 'dpi-' + [guid]::NewGuid().ToString('N')
$runDir = Join-Path $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir) $runName
$scratch = Join-Path $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($TempDir) $runName

function Initialize-DpiInterop {
    if ('Gaius.DpiCheck.Native' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace Gaius.DpiCheck {
    public static class Native {
        public static readonly IntPtr PerMonitorV2 = new IntPtr(-4);
        [StructLayout(LayoutKind.Sequential)]
        public struct Rect { public int Left, Top, Right, Bottom; }
        [StructLayout(LayoutKind.Sequential)]
        private struct MonitorInfo { public int Size; public Rect Bounds, Work; public uint Flags; }
        private delegate bool EnumProc(IntPtr hwnd, IntPtr param);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetProcessDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll", SetLastError = true)]
        public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll")]
        private static extern IntPtr GetThreadDpiAwarenessContext();
        [DllImport("user32.dll")]
        private static extern IntPtr GetWindowDpiAwarenessContext(IntPtr hwnd);
        [DllImport("user32.dll")]
        private static extern bool AreDpiAwarenessContextsEqual(IntPtr first, IntPtr second);
        [DllImport("user32.dll")]
        public static extern uint GetDpiForWindow(IntPtr hwnd);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumProc callback, IntPtr param);
        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")]
        private static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wparam, IntPtr lparam);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y,
                                                int width, int height, uint flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool QueryFullProcessImageName(IntPtr process, uint flags,
                                                             StringBuilder path, ref int size);

        public static void TrySetProcessPerMonitorV2() {
            if (SetProcessDpiAwarenessContext(PerMonitorV2)) return;
            int error = Marshal.GetLastWin32Error();
            // A manifest or the host can set the process default before the script runs.
            if (error != 5) throw new Win32Exception(error, "Cannot set process DPI awareness.");
        }

        public static bool ThreadIsPerMonitorV2() {
            return AreDpiAwarenessContextsEqual(GetThreadDpiAwarenessContext(), PerMonitorV2);
        }

        public static bool WindowIsPerMonitorV2(IntPtr hwnd) {
            return AreDpiAwarenessContextsEqual(GetWindowDpiAwarenessContext(hwnd), PerMonitorV2);
        }

        public static string ProcessImagePath(IntPtr process) {
            var path = new StringBuilder(32768);
            int length = path.Capacity;
            if (!QueryFullProcessImageName(process, 0, path, ref length)) throw new Win32Exception();
            return path.ToString();
        }

        private static bool OwnedBy(IntPtr hwnd, uint expectedPid) {
            uint owner;
            return hwnd != IntPtr.Zero && GetWindowThreadProcessId(hwnd, out owner) != 0
                && owner == expectedPid;
        }

        private static bool IsPreview(IntPtr hwnd, uint pid) {
            return OwnedBy(hwnd, pid) && IsWindowVisible(hwnd) && !IsIconic(hwnd);
        }

        public static IntPtr FindWindow(IntPtr mainWindow, uint pid) {
            if (IsPreview(mainWindow, pid)) return mainWindow;
            IntPtr window = IntPtr.Zero;
            EnumWindows(delegate(IntPtr hwnd, IntPtr unused) {
                if (!IsPreview(hwnd, pid)) return true;
                window = hwnd;
                return false;
            }, IntPtr.Zero);
            return window;
        }

        public static bool RequestClose(IntPtr hwnd, uint pid) {
            return OwnedBy(hwnd, pid) && PostMessage(hwnd, 0x0010, IntPtr.Zero, IntPtr.Zero); // WM_CLOSE
        }

        public static void RaiseForCapture(IntPtr hwnd, uint pid) {
            if (!IsPreview(hwnd, pid)) throw new InvalidOperationException("Preview is no longer visible.");
            // HWND_TOPMOST; SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE.
            if (!SetWindowPos(hwnd, new IntPtr(-1), 0, 0, 0, 0, 0x0013)) throw new Win32Exception();
        }

        public static Rect PhysicalOuterRect(IntPtr hwnd, uint pid) {
            if (!ThreadIsPerMonitorV2()) throw new InvalidOperationException("Capture thread is not PerMonitorV2.");
            if (!IsPreview(hwnd, pid)) throw new InvalidOperationException("Preview is no longer visible.");
            Rect rect;
            if (!GetWindowRect(hwnd, out rect)) throw new Win32Exception();
            return rect;
        }

        public static bool FitsMonitor(IntPtr hwnd, Rect rect) {
            IntPtr monitor = MonitorFromWindow(hwnd, 0); // MONITOR_DEFAULTTONULL
            if (monitor == IntPtr.Zero) return false;
            var info = new MonitorInfo { Size = Marshal.SizeOf(typeof(MonitorInfo)) };
            if (!GetMonitorInfo(monitor, ref info)) throw new Win32Exception();
            return rect.Left >= info.Bounds.Left && rect.Top >= info.Bounds.Top
                && rect.Right <= info.Bounds.Right && rect.Bottom <= info.Bounds.Bottom;
        }
    }
}
'@
}

function Wait-DpiPreview($Process, [string] $ReportPath) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $reportReady = $false
    $reportIssue = 'Report file has not appeared.'
    do {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "Builder exited ($($Process.ExitCode)) before the held preview was ready. Check --dpi-report/--dpi-hold support."
        }
        if (-not $reportReady -and [IO.File]::Exists($ReportPath)) {
            try {
                $text = [IO.File]::ReadAllText($ReportPath)
                $report = ConvertFrom-Json -InputObject $text -ErrorAction Stop
                if (-not $text.TrimStart().StartsWith('{') -or $null -eq $report -or
                    @($report.PSObject.Properties).Count -eq 0) {
                    throw 'Expected a non-empty JSON object.'
                }
                $reportReady = $true
            } catch {
                # The app may still be writing the report. Retry within the same deadline.
                $reportIssue = $_.Exception.Message
            }
        }
        if ($reportReady) {
            $hwnd = [Gaius.DpiCheck.Native]::FindWindow($Process.MainWindowHandle, $Process.Id)
            if ($hwnd -ne [IntPtr]::Zero) { return $hwnd }
        }
        Start-Sleep -Milliseconds 100
    } while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    if ($reportReady) { throw "Timed out waiting for a visible window for PID $($Process.Id)." }
    throw "Timed out waiting for '$ReportPath': $reportIssue"
}

function Stop-DpiPreview($Process, [IntPtr] $Window) {
    if ($Process.HasExited) { return }
    if ($Window -eq [IntPtr]::Zero) {
        $Process.Refresh()
        $Window = [Gaius.DpiCheck.Native]::FindWindow($Process.MainWindowHandle, $Process.Id)
    }
    if ([Gaius.DpiCheck.Native]::RequestClose($Window, $Process.Id) -and $Process.WaitForExit(3000)) {
        return
    }
    # The Process object retains the handle to the exact instance this script started.
    try {
        if (-not $Process.HasExited) { $Process.Kill() }
    } catch {
        if (-not $Process.HasExited) { throw }
    }
    if (-not $Process.WaitForExit(5000)) { throw "Could not stop preview PID $($Process.Id)." }
}

function Save-DpiCapture([string] $Path, $Rect, [int] $Width, [int] $Height) {
    $bitmap = [Drawing.Bitmap]::new($Width, $Height)
    try {
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CopyFromScreen($Rect.Left, $Rect.Top, 0, 0, $bitmap.Size)
        } finally {
            $graphics.Dispose()
        }
        $bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

function Test-DpiPreview([string] $Name, [int] $Page, [string] $State) {
    $reportPath = Join-Path $runDir "$Name.report.json"
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $exe.FullName
    $start.WorkingDirectory = $exe.DirectoryName
    $start.UseShellExecute = $false
    # Keep this native-DPI-only check independent of the user's saved app zoom.
    $start.Arguments = '--dpi-report "{0}" --page {1} --state {2} --theme {3} --lang {4} --dpi-hold --scale 1' -f `
        $reportPath, $Page, $State, $Theme, $Lang
    $window = [IntPtr]::Zero
    $process = [Diagnostics.Process]::Start($start)
    try {
        $image = [Gaius.DpiCheck.Native]::ProcessImagePath($process.Handle)
        if (-not [string]::Equals($image, $exe.FullName, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Started '$image', expected '$($exe.FullName)'. Use the direct path to the builder executable."
        }
        $window = Wait-DpiPreview $process $reportPath
        if (-not [Gaius.DpiCheck.Native]::WindowIsPerMonitorV2($window)) {
            throw "$Name`: the builder window is not PerMonitorV2. Check the executable and Windows compatibility overrides."
        }
        if ($Capture) {
            [Gaius.DpiCheck.Native]::RaiseForCapture($window, $process.Id)
            Start-Sleep -Milliseconds 250 # Let the desktop compositor present the raised window.
        }
        $rect = [Gaius.DpiCheck.Native]::PhysicalOuterRect($window, $process.Id)
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        $dpi = [Gaius.DpiCheck.Native]::GetDpiForWindow($window)
        if ($dpi -eq 0 -or $width -le 0 -or $height -le 0) { throw "$Name`: invalid window DPI or bounds." }
        $report = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($reportPath))
        $expectedPpp = $dpi / 96.0
        if ($report.schema -ne 'gaius-builder-dpi-v1' -or
            [Math]::Abs([double]$report.native_pixels_per_point - $expectedPpp) -gt 0.001 -or
            [Math]::Abs([double]$report.egui_native_pixels_per_point - $expectedPpp) -gt 0.001 -or
            [Math]::Abs([double]$report.effective_pixels_per_point - $expectedPpp) -gt 0.001 -or
            [double]$report.zoom_factor -ne 1.0) {
            throw "$Name`: report disagrees with Win32 DPI ($dpi); check the report and display settings."
        }
        if (-not $report.monitor.work_area -or
            $report.framebuffer_size_physical_px[0] -ne $report.inner.size_physical_px[0] -or
            $report.framebuffer_size_physical_px[1] -ne $report.inner.size_physical_px[1]) {
            throw "$Name`: missing work area or framebuffer size differs from the physical client size."
        }
        $png = $null
        if ($Capture) {
            if (-not [Gaius.DpiCheck.Native]::FitsMonitor($window, $rect)) {
                throw "$Name`: the physical outer rect does not fit on one monitor; use a display that fits the whole preview."
            }
            $png = "$Name.png"
            Save-DpiCapture (Join-Path $runDir $png) $rect $width $height
        }
        $evidence = [ordered]@{
            executable = $image; pid = $process.Id; hwnd = ('0x{0:X}' -f $window.ToInt64())
            page = $Page; state = $State; theme = $Theme; lang = $Lang
            windowAwareness = 'PerMonitorV2'; captureThreadAwareness = 'PerMonitorV2'
            dpi = $dpi; scalePercent = [math]::Round($dpi * 100.0 / 96, 2)
            outerRectPhysicalPixels = [ordered]@{
                left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom
                width = $width; height = $height
            }
            report = "$Name.report.json"; png = $png
        }
        [IO.File]::WriteAllText((Join-Path $runDir "$Name.window.json"), ($evidence | ConvertTo-Json -Depth 3))
        Write-Host "$Name`: PerMonitorV2, $dpi DPI, ${width}x${height} physical pixels"
    } finally {
        try { Stop-DpiPreview $process $window } finally { $process.Dispose() }
    }
}

$oldTemp = @{ TEMP = $env:TEMP; TMP = $env:TMP; TMPDIR = $env:TMPDIR }
$previousContext = [IntPtr]::Zero
# A caller may use the same root for outputs and temporary files; never clean the output directory.
$scratch += '-tmp'
$null = [IO.Directory]::CreateDirectory($scratch)
try {
    $env:TEMP = $env:TMP = $env:TMPDIR = $scratch
    Initialize-DpiInterop
    try {
        [Gaius.DpiCheck.Native]::TrySetProcessPerMonitorV2()
        $previousContext = [Gaius.DpiCheck.Native]::SetThreadDpiAwarenessContext([Gaius.DpiCheck.Native]::PerMonitorV2)
    } catch {
        throw "Could not set PerMonitorV2 (requires Windows 10 version 1703 or newer): $_"
    }
    if ($previousContext -eq [IntPtr]::Zero -or -not [Gaius.DpiCheck.Native]::ThreadIsPerMonitorV2()) {
        throw 'Could not verify PerMonitorV2 on the PowerShell capture thread.'
    }
    if ($Capture) { Add-Type -AssemblyName System.Drawing }
    $null = [IO.Directory]::CreateDirectory($runDir)
    Write-Host "DPI evidence: $runDir"
    foreach ($page in 1..6) { Test-DpiPreview "page-$page" $page 'normal' }
    Test-DpiPreview 'mods' 4 'config-mods'
    Test-DpiPreview 'completion' 6 'build-success'
} finally {
    if ($previousContext -ne [IntPtr]::Zero) {
        if ([Gaius.DpiCheck.Native]::SetThreadDpiAwarenessContext($previousContext) -eq [IntPtr]::Zero) {
            Write-Warning 'Could not restore the PowerShell thread DPI context. Close this PowerShell session.'
        }
    }
    $env:TEMP = $oldTemp.TEMP
    $env:TMP = $oldTemp.TMP
    $env:TMPDIR = $oldTemp.TMPDIR
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}
