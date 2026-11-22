//! Initial window placement and diagnostics. Desktop origins and all placement
//! calculations are in physical pixels. Saved client sizes are in OS logical
//! points (native DPI only, independent of egui zoom).

use std::io;

use eframe::egui;

pub const DEFAULT_CLIENT_LOGICAL: [u32; 2] = [960, 620];
pub const MIN_CLIENT_LOGICAL: [u32; 2] = [800, 540];

/// Desktop pixel rectangle with exclusive right/bottom edges.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PhysicalRect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl PhysicalRect {
    pub fn contains(self, [x, y]: [i32; 2]) -> bool {
        x >= self.left && x < self.right && y >= self.top && y < self.bottom
    }

    pub fn size(self) -> Option<[u32; 2]> {
        let width = self.right.checked_sub(self.left)?;
        let height = self.bottom.checked_sub(self.top)?;
        (width > 0 && height > 0).then_some([width as u32, height as u32])
    }

    fn from_origin_size([left, top]: [i32; 2], [width, height]: [u32; 2]) -> Option<Self> {
        Some(Self {
            left,
            top,
            right: left.checked_add(i32::try_from(width).ok()?)?,
            bottom: top.checked_add(i32::try_from(height).ok()?)?,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MonitorGeometry {
    pub full_px: PhysicalRect,
    /// None when the platform/backend doesn't expose reserved desktop space.
    pub work_px: Option<PhysicalRect>,
    pub native_pixels_per_point: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WindowRect {
    pub physical: PhysicalRect,
    /// The physical rectangle divided by the window's own native scale. This is
    /// not a shared desktop coordinate space across monitors with different DPIs.
    pub logical: egui::Rect,
}

impl WindowRect {
    fn new(physical: PhysicalRect, scale: f64) -> Self {
        let point = |x, y| egui::pos2((x as f64 / scale) as f32, (y as f64 / scale) as f32);
        Self {
            physical,
            logical: egui::Rect::from_min_max(
                point(physical.left, physical.top),
                point(physical.right, physical.bottom),
            ),
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct WindowSnapshot {
    pub native_pixels_per_point: Option<f64>,
    pub monitor: Option<MonitorGeometry>,
    /// May be known even if the monitor's desktop origin is unavailable.
    pub monitor_size_px: Option<[u32; 2]>,
    pub inner: Option<WindowRect>,
    pub outer: Option<WindowRect>,
    /// May be known even when the compositor hides the window's origin.
    pub client_size_px: Option<[u32; 2]>,
    pub maximized: Option<bool>,
    pub minimized: Option<bool>,
}

impl WindowSnapshot {
    /// egui reports viewport rectangles in *zoomed* points, not OS points.
    /// Recover pixels using effective PPP, then convert to native logical points.
    pub fn from_viewport_info(info: &egui::ViewportInfo, zoom_factor: f32) -> Self {
        let native = info
            .native_pixels_per_point
            .map(f64::from)
            .filter(|s| valid_scale(*s));
        let rect = |rect: Option<egui::Rect>| {
            let scale = native?;
            let effective = scale * f64::from(zoom_factor);
            let rect = rect?;
            if !valid_scale(effective) || !rect.is_finite() || info.minimized == Some(true) {
                return None;
            }
            let px = |value: f32| (f64::from(value) * effective).round() as i32;
            let physical = PhysicalRect {
                left: px(rect.min.x),
                top: px(rect.min.y),
                right: px(rect.max.x),
                bottom: px(rect.max.y),
            };
            physical.size()?;
            Some(WindowRect::new(physical, scale))
        };
        let inner = rect(info.inner_rect);
        Self {
            native_pixels_per_point: native,
            monitor_size_px: info.monitor_size.and_then(|size| {
                let scale = native? * f64::from(zoom_factor);
                (valid_scale(scale) && size.is_finite() && size.x > 0.0 && size.y > 0.0).then(
                    || {
                        [
                            scaled_px(size.x.into(), scale),
                            scaled_px(size.y.into(), scale),
                        ]
                    },
                )
            }),
            inner,
            outer: rect(info.outer_rect),
            client_size_px: inner.and_then(|rect| rect.physical.size()),
            maximized: info.maximized,
            minimized: info.minimized,
            monitor: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, serde::Deserialize, serde::Serialize)]
pub struct RestoredGeometry {
    pub outer_origin_px: [i32; 2],
    pub client_size_logical: [f64; 2],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InitialPlacement {
    pub outer_px: PhysicalRect,
    pub client_size_px: [u32; 2],
    pub min_client_size_px: [u32; 2],
}

fn valid_scale(scale: f64) -> bool {
    scale.is_finite() && scale > 0.0
}

fn scaled_px(logical: f64, scale: f64) -> u32 {
    ((logical * scale).round() as u32).max(1)
}

fn valid_restore(saved: &RestoredGeometry) -> bool {
    saved.client_size_logical.into_iter().all(valid_scale)
}

// Don't use the nearest monitor for an orphaned saved origin: both the size
// and the position must revert to the default on the current monitor.
#[cfg(any(not(target_arch = "wasm32"), test))]
fn restore_target<M>(
    restored: Option<RestoredGeometry>,
    monitor_at: impl FnOnce([i32; 2]) -> Option<M>,
) -> Option<(M, RestoredGeometry)> {
    let saved = restored.filter(valid_restore)?;
    Some((monitor_at(saved.outer_origin_px)?, saved))
}

fn fit_limits(
    work_px: PhysicalRect,
    scale: f64,
    frame_px: [u32; 2],
) -> Option<([u32; 2], [u32; 2])> {
    if !valid_scale(scale) {
        return None;
    }
    let [width, height] = work_px.size()?;
    let available = [
        width.checked_sub(frame_px[0])?,
        height.checked_sub(frame_px[1])?,
    ];
    if available.contains(&0) {
        return None;
    }
    let minimum = std::array::from_fn(|i| {
        scaled_px(f64::from(MIN_CLIENT_LOGICAL[i]), scale).min(available[i])
    });
    Some((available, minimum))
}

/// Fit the whole decorated window into one selected work area. The default is
/// 960×620 logical, capped at 90% of the work area (including frame); the
/// effective minimum wins over that soft cap. Restores use the full work area.
/// `frame_px` is total extra width/height (outer minus client), at target DPI.
/// Returns None for an invalid work area/scale or if not even a pixel of client
/// area fits. Invalid saved sizes are treated as no saved geometry.
pub fn initial_placement(
    work_px: PhysicalRect,
    scale: f64,
    frame_px: [u32; 2],
    restored: Option<RestoredGeometry>,
) -> Option<InitialPlacement> {
    let (available, min_client_size_px) = fit_limits(work_px, scale, frame_px)?;
    let work_size = work_px.size()?;
    let restored = restored.filter(valid_restore);
    let client_size_px = std::array::from_fn(|i| {
        let min = min_client_size_px[i];
        let (desired, max) = if let Some(saved) = restored {
            (saved.client_size_logical[i], available[i])
        } else {
            let cap = ((u64::from(work_size[i]) * 9 / 10) as u32).saturating_sub(frame_px[i]);
            (f64::from(DEFAULT_CLIENT_LOGICAL[i]), cap.max(min))
        };
        scaled_px(desired, scale).clamp(min, max)
    });
    let outer_size = std::array::from_fn(|i| client_size_px[i] + frame_px[i]);
    let start = [work_px.left, work_px.top];
    let origin = std::array::from_fn(|i| {
        let room = (work_size[i] - outer_size[i]) as i32;
        restored.map_or(start[i] + room / 2, |saved| {
            saved.outer_origin_px[i].clamp(start[i], start[i] + room)
        })
    });
    Some(InitialPlacement {
        outer_px: PhysicalRect::from_origin_size(origin, outer_size)?,
        client_size_px,
        min_client_size_px,
    })
}

fn viewport_snapshot(ctx: &egui::Context) -> WindowSnapshot {
    let zoom = ctx.zoom_factor();
    ctx.input(|input| WindowSnapshot::from_viewport_info(input.viewport(), zoom))
}

// eframe exposes a winit Window by inference without reexporting its type.
// Share this extraction between CreationContext and Frame without another crate.
#[cfg(all(not(windows), not(target_arch = "wasm32")))]
macro_rules! portable_monitor {
    ($monitor:expr) => {{
        let monitor = $monitor;
        let pos = monitor.position();
        let size = monitor.size();
        PhysicalRect::from_origin_size([pos.x, pos.y], [size.width, size.height]).map(|full_px| {
            MonitorGeometry {
                full_px,
                work_px: None,
                native_pixels_per_point: monitor.scale_factor(),
            }
        })
    }};
}

#[cfg(all(not(windows), not(target_arch = "wasm32")))]
macro_rules! portable_snapshot {
    ($window:expr) => {{
        let window = $window;
        let native = window.scale_factor();
        let minimized = window.is_minimized();
        let client = window.inner_size();
        let monitor = window.current_monitor().and_then(|m| portable_monitor!(m));
        let inner = window.inner_position().ok().and_then(|pos| {
            PhysicalRect::from_origin_size([pos.x, pos.y], [client.width, client.height])
        });
        let outer = window.outer_position().ok().and_then(|pos| {
            let size = window.outer_size();
            PhysicalRect::from_origin_size([pos.x, pos.y], [size.width, size.height])
        });
        let rect = |rect: Option<PhysicalRect>| {
            let rect = rect?;
            rect.size()?;
            (minimized != Some(true)).then(|| WindowRect::new(rect, native))
        };
        WindowSnapshot {
            native_pixels_per_point: Some(native),
            monitor,
            monitor_size_px: monitor.and_then(|monitor| monitor.full_px.size()),
            inner: rect(inner),
            outer: rect(outer),
            client_size_px: (client.width > 0 && client.height > 0)
                .then_some([client.width, client.height]),
            maximized: Some(window.is_maximized()),
            minimized,
        }
    }};
}

/// Query a native window before the first egui frame, if it exists.
pub fn snapshot_at_creation(cc: &eframe::CreationContext<'_>) -> WindowSnapshot {
    #[cfg(windows)]
    if let Ok(hwnd) = win::hwnd(cc) {
        return win::snapshot(hwnd);
    }
    #[cfg(all(not(windows), not(target_arch = "wasm32")))]
    if let Some(window) = cc.winit_window() {
        return portable_snapshot!(window);
    }
    viewport_snapshot(&cc.egui_ctx)
}

/// Query current geometry, with egui viewport data as a headless fallback.
/// Use the context for the same viewport as `frame`.
pub fn snapshot(frame: &eframe::Frame, ctx: &egui::Context) -> WindowSnapshot {
    #[cfg(windows)]
    if let Ok(hwnd) = win::hwnd(frame) {
        return win::snapshot(hwnd);
    }
    #[cfg(all(not(windows), not(target_arch = "wasm32")))]
    if let Some(window) = frame.winit_window() {
        return portable_snapshot!(window);
    }
    let _ = frame;
    viewport_snapshot(ctx)
}

/// Apply initial geometry on the UI thread, before the first show. If restoring
/// a maximized session, start windowed, call this, then request maximization.
/// An already maximized, minimized or fullscreen window is left in that state.
///
/// Don't also supply a fixed `ViewportBuilder::with_min_inner_size` or separate
/// framework window-geometry persistence. Windows installs a monitor-aware
/// native minimum; other platforms get a best-effort initial minimum and size
/// based on full monitor bounds, and compositor-controlled position may not be
/// available. `Ok(None)` means no initial placement was requested.
pub fn apply_initial_placement(
    cc: &eframe::CreationContext<'_>,
    restored: Option<RestoredGeometry>,
) -> io::Result<Option<InitialPlacement>> {
    #[cfg(not(target_arch = "wasm32"))]
    let Some(window) = cc.winit_window() else {
        return Ok(None);
    };
    #[cfg(not(target_arch = "wasm32"))]
    if window.fullscreen().is_some() {
        return Ok(None);
    }
    #[cfg(windows)]
    return win::apply(win::hwnd(cc)?, restored, |bounds| {
        // MonitorHandle has the target monitor's effective DPI even when the
        // HWND is still on a different output. Match physical bounds, not names.
        window.available_monitors().find_map(|monitor| {
            let pos = monitor.position();
            let size = monitor.size();
            (PhysicalRect::from_origin_size([pos.x, pos.y], [size.width, size.height])
                == Some(bounds))
            .then(|| monitor.scale_factor())
        })
    });
    #[cfg(all(not(windows), not(target_arch = "wasm32")))]
    {
        if window.is_maximized() || window.is_minimized() == Some(true) {
            return Ok(None);
        }
        let pos = window.outer_position().ok();
        let monitors = || {
            window
                .available_monitors()
                .filter_map(|m| portable_monitor!(m))
        };
        let selected = restore_target(pos.and(restored), |anchor| {
            monitors().find(|m| m.full_px.contains(anchor))
        });
        let target = selected
            .map(|(target, _)| target)
            .or_else(|| window.current_monitor().and_then(|m| portable_monitor!(m)))
            .or_else(|| window.primary_monitor().and_then(|m| portable_monitor!(m)))
            .or_else(|| {
                let mut monitors = monitors();
                let first = monitors.next()?;
                monitors.next().is_none().then_some(first)
            });
        let Some(target) = target else {
            return Ok(None);
        };
        let mut client = window.inner_size();
        let outer = window.outer_size();
        let ratio = target.native_pixels_per_point / window.scale_factor();
        let frame = [
            outer.width.saturating_sub(client.width),
            outer.height.saturating_sub(client.height),
        ]
        .map(|px| (f64::from(px) * ratio).round() as u32);
        let Some(plan) = initial_placement(
            target.full_px,
            target.native_pixels_per_point,
            frame,
            selected.map(|(_, saved)| saved),
        ) else {
            return Ok(None);
        };
        [client.width, client.height] = plan.min_client_size_px;
        window.set_min_inner_size(Some(client));
        if let Some(mut pos) = pos {
            pos.x = plan.outer_px.left;
            pos.y = plan.outer_px.top;
            window.set_outer_position(pos);
        }
        [client.width, client.height] = plan.client_size_px;
        let _ = window.request_inner_size(client);
        Ok(Some(plan))
    }
    #[cfg(target_arch = "wasm32")]
    {
        let _ = (cc, restored);
        Ok(None)
    }
}

#[cfg(windows)]
mod win {
    use super::*;
    use eframe::wgpu::rwh::{HasWindowHandle, RawWindowHandle};
    use std::{mem::size_of, ptr::null_mut};
    use windows_sys::Win32::{
        Foundation::{HWND, LPARAM, LRESULT, POINT, RECT, WPARAM},
        Graphics::Gdi::{
            ClientToScreen, GetMonitorInfoW, HMONITOR, MONITOR_DEFAULTTONEAREST,
            MONITOR_DEFAULTTONULL, MONITORINFO, MonitorFromPoint, MonitorFromWindow,
        },
        UI::{
            HiDpi::{
                AdjustWindowRectExForDpi, DPI_AWARENESS_PER_MONITOR_AWARE,
                GetAwarenessFromDpiAwarenessContext, GetDpiForWindow, GetThreadDpiAwarenessContext,
                GetWindowDpiAwarenessContext,
            },
            Shell::{DefSubclassProc, RemoveWindowSubclass, SetWindowSubclass},
            WindowsAndMessaging::{
                GWL_EXSTYLE, GWL_STYLE, GetClientRect, GetMenu, GetWindowLongW, GetWindowRect,
                IsIconic, IsZoomed, MINMAXINFO, SWP_NOACTIVATE, SWP_NOOWNERZORDER, SWP_NOZORDER,
                SetWindowPos, WM_DPICHANGED, WM_GETMINMAXINFO, WM_NCDESTROY,
            },
        },
    };

    const BASE_DPI: f64 = 96.0;
    const MINIMUM_SUBCLASS: usize = 1;

    pub(super) fn hwnd(window: &impl HasWindowHandle) -> io::Result<HWND> {
        match window.window_handle().map(|h| h.as_raw()) {
            Ok(RawWindowHandle::Win32(handle)) => Ok(handle.hwnd.get() as HWND),
            _ => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "no Win32 window handle",
            )),
        }
    }

    fn dpi(hwnd: HWND) -> Option<u32> {
        // SAFETY: These queries do not retain the HWND or any caller pointers.
        unsafe {
            let aware = |context| {
                GetAwarenessFromDpiAwarenessContext(context) == DPI_AWARENESS_PER_MONITOR_AWARE
            };
            // Don't label DPI-virtualized coordinates as physical pixels.
            if !aware(GetThreadDpiAwarenessContext()) || !aware(GetWindowDpiAwarenessContext(hwnd))
            {
                return None;
            }
            let dpi = GetDpiForWindow(hwnd);
            (dpi != 0).then_some(dpi)
        }
    }

    fn physical(rect: RECT) -> PhysicalRect {
        PhysicalRect {
            left: rect.left,
            top: rect.top,
            right: rect.right,
            bottom: rect.bottom,
        }
    }

    fn current_monitor(hwnd: HWND) -> HMONITOR {
        // SAFETY: No pointer is dereferenced by the caller; this only queries the HWND.
        unsafe { MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) }
    }

    fn monitor_rects(monitor: HMONITOR) -> Option<(PhysicalRect, PhysicalRect)> {
        let mut info = MONITORINFO {
            cbSize: size_of::<MONITORINFO>() as u32,
            ..Default::default()
        };
        // SAFETY: The requested MONITORINFO fits the initialized out parameter.
        if monitor.is_null() || unsafe { GetMonitorInfoW(monitor, &mut info) } == 0 {
            return None;
        }
        let (full, work) = (physical(info.rcMonitor), physical(info.rcWork));
        full.size()?;
        work.size()?;
        Some((full, work))
    }

    fn outer_rect(hwnd: HWND) -> Option<PhysicalRect> {
        let mut rect = RECT::default();
        // SAFETY: rect is a writable RECT and is not retained.
        (unsafe { GetWindowRect(hwnd, &mut rect) } != 0).then(|| physical(rect))
    }

    fn client_rect(hwnd: HWND) -> Option<PhysicalRect> {
        let mut rect = RECT::default();
        let mut origin = POINT::default();
        // SAFETY: Both out parameters are writable and not retained.
        if unsafe { GetClientRect(hwnd, &mut rect) == 0 || ClientToScreen(hwnd, &mut origin) == 0 }
        {
            return None;
        }
        PhysicalRect::from_origin_size([origin.x, origin.y], physical(rect).size()?)
    }

    fn frame_size_at(hwnd: HWND, dpi: u32) -> Option<[u32; 2]> {
        // Inflate a 1×1 client rectangle to get decoration overhead at target DPI.
        let mut rect = RECT {
            right: 1,
            bottom: 1,
            ..Default::default()
        };
        // SAFETY: All inputs are values or an initialized, writable RECT.
        let ok = unsafe {
            AdjustWindowRectExForDpi(
                &mut rect,
                GetWindowLongW(hwnd, GWL_STYLE) as u32,
                (!GetMenu(hwnd).is_null()).into(),
                GetWindowLongW(hwnd, GWL_EXSTYLE) as u32,
                dpi,
            ) != 0
        };
        ok.then(|| physical(rect).size().map(|dims| dims.map(|n| n - 1)))
            .flatten()
    }

    pub(super) fn snapshot(hwnd: HWND) -> WindowSnapshot {
        // SAFETY: The window handle is live on the eframe UI thread.
        let (maximized, minimized) = unsafe { (IsZoomed(hwnd) != 0, IsIconic(hwnd) != 0) };
        let mut snapshot = WindowSnapshot {
            maximized: Some(maximized),
            minimized: Some(minimized),
            ..WindowSnapshot::default()
        };
        if let Some(dpi) = dpi(hwnd) {
            let scale = f64::from(dpi) / BASE_DPI;
            snapshot.native_pixels_per_point = Some(scale);
            if let Some((full_px, work_px)) = monitor_rects(current_monitor(hwnd)) {
                snapshot.monitor = Some(MonitorGeometry {
                    full_px,
                    work_px: Some(work_px),
                    native_pixels_per_point: scale,
                });
                snapshot.monitor_size_px = full_px.size();
            }
            if !minimized {
                snapshot.inner = client_rect(hwnd).map(|rect| WindowRect::new(rect, scale));
                snapshot.outer = outer_rect(hwnd)
                    .filter(|rect| rect.size().is_some())
                    .map(|rect| WindowRect::new(rect, scale));
                snapshot.client_size_px = snapshot.inner.and_then(|rect| rect.physical.size());
            }
        }
        snapshot
    }

    unsafe extern "system" fn metrics_proc(
        hwnd: HWND,
        msg: u32,
        wparam: WPARAM,
        lparam: LPARAM,
        id: usize,
        _: usize,
    ) -> LRESULT {
        // SAFETY: This is a comctl32 subclass installed on this window's owning
        // thread. Forward every message and unregister on destruction.
        if msg == WM_NCDESTROY {
            unsafe { RemoveWindowSubclass(hwnd, Some(metrics_proc), id) };
        }
        let result = unsafe { DefSubclassProc(hwnd, msg, wparam, lparam) };
        if msg == WM_GETMINMAXINFO
            && lparam != 0
            && let Some(dpi) = dpi(hwnd)
            && let Some((_, work)) = monitor_rects(current_monitor(hwnd))
            && let Some(frame) = frame_size_at(hwnd, dpi)
            && let Some((_, min)) = fit_limits(work, f64::from(dpi) / BASE_DPI, frame)
        {
            // SAFETY: WM_GETMINMAXINFO supplies a writable MINMAXINFO for this
            // callback. Winit has already handled it; only change its minimum.
            let info = unsafe { &mut *(lparam as *mut MINMAXINFO) };
            let track = |i, max: i32| {
                let outer = (min[i] + frame[i]) as i32;
                if max > 0 { outer.min(max) } else { outer }
            };
            info.ptMinTrackSize = POINT {
                x: track(0, info.ptMaxTrackSize.x),
                y: track(1, info.ptMaxTrackSize.y),
            };
        }
        if msg == WM_DPICHANGED {
            fit_after_dpi_change(hwnd);
        }
        result
    }

    fn fit_after_dpi_change(hwnd: HWND) {
        // winit has already preserved the OS-logical size and moved the title
        // bar relative to the mouse. Leave it alone unless the new work area
        // is too small to show the window. Don't resize a maximized session.
        // SAFETY: Invoked synchronously for a live HWND on its owning thread.
        if unsafe { IsIconic(hwnd) != 0 || IsZoomed(hwnd) != 0 } {
            return;
        }
        if let (Some(dpi), Some((_, work)), Some(rect), Some(client)) = (
            dpi(hwnd),
            monitor_rects(current_monitor(hwnd)),
            outer_rect(hwnd),
            client_rect(hwnd),
        ) && let (Some([w, h]), Some([cw, ch]), Some([ww, wh])) =
            (rect.size(), client.size(), work.size())
            && (w > ww || h > wh)
        {
            let scale = f64::from(dpi) / BASE_DPI;
            let restore = RestoredGeometry {
                outer_origin_px: [rect.left, rect.top],
                client_size_logical: [f64::from(cw) / scale, f64::from(ch) / scale],
            };
            if let Some(plan) = initial_placement(
                work,
                scale,
                [w.saturating_sub(cw), h.saturating_sub(ch)],
                Some(restore),
            ) {
                let _ = set_outer_rect(hwnd, plan.outer_px);
            }
        }
    }

    fn set_outer_rect(hwnd: HWND, rect: PhysicalRect) -> io::Result<()> {
        let [width, height] = rect
            .size()
            .ok_or_else(|| io::Error::other("invalid window size"))?;
        // SAFETY: The window is normal and on the owning UI thread; the
        // coordinates and extents are bounded physical pixels. Don't show,
        // activate, or change the z order of eframe's hidden startup window.
        if unsafe {
            SetWindowPos(
                hwnd,
                null_mut(),
                rect.left,
                rect.top,
                width as i32,
                height as i32,
                SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_NOZORDER,
            )
        } == 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    pub(super) fn apply(
        hwnd: HWND,
        restored: Option<RestoredGeometry>,
        monitor_scale: impl FnOnce(PhysicalRect) -> Option<f64>,
    ) -> io::Result<Option<InitialPlacement>> {
        let Some(window_dpi) = dpi(hwnd) else {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "expected a per-monitor-DPI-aware HWND",
            ));
        };
        // SAFETY: The caller is on the owning UI thread. The subclass has no
        // borrowed state and does not outlive the HWND.
        if unsafe { SetWindowSubclass(hwnd, Some(metrics_proc), MINIMUM_SUBCLASS, 0) } == 0 {
            // SetWindowSubclass does not document GetLastError diagnostics.
            return Err(io::Error::other(
                "could not install the DPI-aware window minimum",
            ));
        }
        // Never move/resize the live rectangle of a minimized or maximized window.
        if unsafe { IsIconic(hwnd) != 0 || IsZoomed(hwnd) != 0 } {
            return Ok(None);
        }
        let current = current_monitor(hwnd);
        let selected = restore_target(restored, |[x, y]| {
            // SAFETY: The point is passed by value in physical desktop pixels.
            let monitor = unsafe { MonitorFromPoint(POINT { x, y }, MONITOR_DEFAULTTONULL) };
            (!monitor.is_null()).then_some(monitor)
        });
        let target = selected.map_or(current, |(target, _)| target);
        let restored = selected.map(|(_, saved)| saved);
        let (full, work) =
            monitor_rects(target).ok_or_else(|| io::Error::other("no monitor work area"))?;
        let mut target_dpi = if target == current {
            window_dpi
        } else {
            let scale = monitor_scale(full)
                .filter(|s| valid_scale(*s))
                .ok_or_else(|| io::Error::other("no native scale for the restore monitor"))?;
            scaled_px(BASE_DPI, scale)
        };
        let mut frame = frame_size_at(hwnd, target_dpi)
            .ok_or_else(|| io::Error::other("could not estimate the native window frame"))?;
        // Winit may resize synchronously during WM_DPICHANGED. Reconcile once
        // with the actual destination DPI and non-client size before first show.
        for _ in 0..2 {
            let Some(plan) =
                initial_placement(work, f64::from(target_dpi) / BASE_DPI, frame, restored)
            else {
                return Ok(None);
            };
            let rect = plan.outer_px;
            set_outer_rect(hwnd, rect)?;
            if current_monitor(hwnd) != target {
                break;
            }
            let actual_outer =
                outer_rect(hwnd).ok_or_else(|| io::Error::other("no native outer rect"))?;
            let outer_size = actual_outer
                .size()
                .ok_or_else(|| io::Error::other("invalid native outer rect"))?;
            let actual_client = client_rect(hwnd)
                .and_then(PhysicalRect::size)
                .ok_or_else(|| io::Error::other("no native client rect"))?;
            let actual_dpi = dpi(hwnd).ok_or_else(|| io::Error::other("no native window DPI"))?;
            if actual_dpi == target_dpi
                && actual_outer == rect
                && actual_client == plan.client_size_px
            {
                return Ok(Some(plan));
            }
            target_dpi = actual_dpi;
            frame = std::array::from_fn(|i| outer_size[i].saturating_sub(actual_client[i]));
        }
        Err(io::Error::other(
            "native window did not accept the initial placement",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rect(left: i32, top: i32, right: i32, bottom: i32) -> PhysicalRect {
        PhysicalRect {
            left,
            top,
            right,
            bottom,
        }
    }

    #[test]
    fn centers_the_default_client_plus_frame_at_native_dpi() {
        let work = rect(-2560, -1400, 0, 0);
        let plan = initial_placement(work, 1.5, [24, 60], None).unwrap();
        assert_eq!(plan.client_size_px, [1440, 930]);
        assert_eq!(plan.min_client_size_px, [1200, 810]);
        assert_eq!(plan.outer_px, rect(-2012, -1195, -548, -205));
    }

    #[test]
    fn default_cap_includes_frame_and_minimum_can_override_it() {
        let work = rect(40, 20, 1064, 720);
        let plan = initial_placement(work, 1.0, [16, 40], None).unwrap();
        assert_eq!(plan.client_size_px, [905, 590]);
        assert_eq!(plan.outer_px, rect(91, 55, 1012, 685));

        let work = rect(0, 0, 910, 600);
        let plan = initial_placement(work, 1.0, [20, 40], None).unwrap();
        assert_eq!(plan.client_size_px, [800, 540]);
        assert_eq!(plan.outer_px, rect(45, 10, 865, 590));
    }

    #[test]
    fn minimum_shrinks_per_axis_using_work_area_not_full_bounds() {
        let work = rect(-640, 30, 0, 480);
        let small = initial_placement(work, 1.25, [20, 50], None).unwrap();
        assert_eq!(small.min_client_size_px, [620, 400]);
        assert_eq!(small.client_size_px, small.min_client_size_px);
        assert_eq!(small.outer_px, work);

        let ultrawide = initial_placement(rect(-2400, 30, 0, 480), 1.25, [20, 50], None).unwrap();
        assert_eq!(ultrawide.min_client_size_px, [1000, 400]);
    }

    #[test]
    fn restores_using_target_scale_and_clamps_to_all_work_edges() {
        let work = rect(-1600, -1280, 0, -40);
        let saved = RestoredGeometry {
            outer_origin_px: [-9000, 2400],
            client_size_logical: [970.0, 580.0],
        };
        let plan = initial_placement(work, 1.25, [20, 50], Some(saved)).unwrap();
        assert_eq!(plan.client_size_px, [1213, 725]);
        assert_eq!(plan.outer_px, rect(-1600, -815, -367, -40));

        let saved = RestoredGeometry {
            outer_origin_px: [-1, -9000],
            client_size_logical: [100_000.0, 1.0],
        };
        let plan = initial_placement(work, 1.25, [20, 50], Some(saved)).unwrap();
        assert_eq!(plan.client_size_px, [1580, 675]);
        assert_eq!(plan.outer_px, rect(-1600, -1280, 0, -555));
    }

    #[test]
    fn disconnected_anchor_discards_saved_size_and_position() {
        let full = rect(-1200, 0, 0, 1000);
        let work = rect(-1200, 32, 0, 1000);
        let saved = RestoredGeometry {
            outer_origin_px: [10, 100],
            client_size_logical: [801.0, 541.0],
        };
        let monitor_at = |anchor| full.contains(anchor).then_some(work);
        let selected = restore_target(Some(saved), monitor_at);
        assert_eq!(selected, None);
        let fallback = initial_placement(work, 1.0, [0, 0], selected.map(|(_, saved)| saved));
        assert_eq!(fallback, initial_placement(work, 1.0, [0, 0], None));

        // An anchor under a taskbar is still on a connected display; its
        // position (but not its size) should be clamped into the work area.
        let saved = RestoredGeometry {
            outer_origin_px: [-1200, 0],
            ..saved
        };
        let (work, saved) = restore_target(Some(saved), monitor_at).unwrap();
        let plan = initial_placement(work, 1.0, [0, 0], Some(saved)).unwrap();
        assert_eq!(plan.client_size_px, [801, 541]);
        assert_eq!(plan.outer_px.top, 32);
        assert!(!full.contains([0, 100]));
    }

    #[test]
    fn saved_geometry_round_trips_through_json() {
        let saved = RestoredGeometry {
            outer_origin_px: [-1580, -22],
            client_size_logical: [960.25, 620.0],
        };
        let json = serde_json::to_string(&saved).unwrap();
        assert_eq!(
            serde_json::from_str::<RestoredGeometry>(&json).unwrap(),
            saved
        );
    }

    #[test]
    fn rejects_invalid_inputs_and_ignores_corrupt_restore() {
        let work = rect(0, 0, 1920, 1040);
        let saved = RestoredGeometry {
            outer_origin_px: [i32::MIN, i32::MAX],
            client_size_logical: [800.0, f64::NAN],
        };
        assert_eq!(
            initial_placement(work, 1.0, [16, 40], Some(saved)),
            initial_placement(work, 1.0, [16, 40], None)
        );
        assert_eq!(initial_placement(work, 0.0, [10, 10], None), None);
        assert_eq!(initial_placement(work, 1.0, [0, 1040], None), None);
        assert_eq!(
            initial_placement(rect(0, 0, 0, 100), 1.0, [0, 0], None),
            None
        );
    }

    #[test]
    fn viewport_fallback_distinguishes_native_scale_and_egui_zoom() {
        let mut info = egui::ViewportInfo {
            native_pixels_per_point: Some(1.25),
            inner_rect: Some(egui::Rect::from_min_max(
                egui::pos2(-400.0, 24.0),
                egui::pos2(-80.0, 264.0),
            )),
            monitor_size: Some(egui::vec2(640.0, 480.0)),
            ..Default::default()
        };
        let snapshot = WindowSnapshot::from_viewport_info(&info, 1.6);
        let inner = snapshot.inner.unwrap();
        assert_eq!(inner.physical, rect(-800, 48, -160, 528));
        assert_eq!(inner.logical.width(), 512.0);
        assert_eq!(snapshot.client_size_px, Some([640, 480]));
        assert_eq!(snapshot.monitor_size_px, Some([1280, 960]));
        assert_eq!(snapshot.monitor, None);
        info.minimized = Some(true);
        assert_eq!(WindowSnapshot::from_viewport_info(&info, 1.6).inner, None);
    }

    #[test]
    fn headless_creation_is_supported() {
        let cc = eframe::CreationContext::_new_kittest(egui::Context::default());
        assert_eq!(snapshot_at_creation(&cc).outer, None);
        assert_eq!(apply_initial_placement(&cc, None).unwrap(), None);
    }
}
