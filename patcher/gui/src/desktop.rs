use std::{
    fs, io,
    path::{Path, PathBuf},
};

use eframe::egui;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::dpi::{self, RestoredGeometry, WindowRect, WindowSnapshot};
use crate::ui_scale::UiScale;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub(crate) struct Settings {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) zoom: Option<UiScale>,
    window: Option<RestoredGeometry>,
}

impl Settings {
    pub(crate) fn load(path: &Path) -> Self {
        fs::read(path)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }
}

pub(crate) struct Desktop {
    settings_path: Option<PathBuf>,
    settings: Settings,
    saved: Settings,
    last_window: WindowSnapshot,
    startup_diagnostics: String,
    first_frame: Option<WindowSnapshot>,
}

impl Desktop {
    pub(crate) fn new(
        cc: &eframe::CreationContext<'_>,
        settings: Settings,
        settings_path: Option<PathBuf>,
    ) -> Self {
        // Native previews use the saved zoom, but not the user's window geometry.
        let placement =
            dpi::apply_initial_placement(cc, settings_path.as_ref().and(settings.window));
        let startup_diagnostics = placement
            .as_ref()
            .err()
            .map(|error| format!("Initial window placement: {error}\n"))
            .unwrap_or_default();
        let last_window = dpi::snapshot_at_creation(cc);
        Self {
            settings_path,
            saved: settings.clone(),
            settings,
            last_window,
            startup_diagnostics,
            first_frame: None,
        }
    }

    pub(crate) fn native_size(&self) -> Option<egui::Vec2> {
        let [width, height] = self.last_window.client_size_px?;
        let scale = self.last_window.native_pixels_per_point? as f32;
        Some(egui::vec2(width as f32, height as f32) / scale)
    }

    pub(crate) fn update_zoom(&mut self, zoom: UiScale) {
        if self.settings.zoom != Some(zoom) {
            self.settings.zoom = Some(zoom);
            self.save();
        }
    }

    pub(crate) fn sample(&mut self, frame: &eframe::Frame, ctx: &egui::Context) -> WindowSnapshot {
        let window = dpi::snapshot(frame, ctx);
        if window.maximized != Some(true)
            && window.minimized != Some(true)
            && let (Some(outer), Some(inner)) = (window.outer, window.inner)
        {
            let size = inner.logical.size();
            self.settings.window = Some(RestoredGeometry {
                outer_origin_px: [outer.physical.left, outer.physical.top],
                client_size_logical: [f64::from(size.x), f64::from(size.y)],
            });
        }
        self.first_frame.get_or_insert(window);
        self.last_window = window;
        window
    }

    pub(crate) fn diagnostics(&self) -> String {
        let startup = window_json(self.first_frame.unwrap_or(self.last_window));
        format!(
            "{}Startup DPI: {}\nCurrent DPI: {}\n",
            self.startup_diagnostics,
            startup,
            window_json(self.last_window)
        )
    }

    pub(crate) fn save(&mut self) {
        if let Some(path) = &self.settings_path
            && self.saved != self.settings
        {
            match write_json(path, &self.settings) {
                Ok(()) => self.saved = self.settings.clone(),
                Err(error) => eprintln!("Could not save display settings: {error}"),
            }
        }
    }
}

pub(crate) fn write_json(path: &Path, value: &impl Serialize) -> io::Result<()> {
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("{}.tmp", std::process::id()));
    fs::write(&temporary, serde_json::to_vec_pretty(value)?)?;
    if let Err(error) = fs::rename(&temporary, path) {
        let _ = fs::remove_file(temporary);
        return Err(error);
    }
    Ok(())
}

pub(crate) fn window_json(window: WindowSnapshot) -> Value {
    let rect = |window: WindowRect| {
        json!({
            "origin_physical_px": [window.physical.left, window.physical.top],
            "size_physical_px": window.physical.size(),
            "origin_native_logical": [window.logical.min.x, window.logical.min.y],
            "size_native_logical": [window.logical.width(), window.logical.height()],
        })
    };
    let monitor = window.monitor.map(|monitor| json!({
        "origin_physical_px": [monitor.full_px.left, monitor.full_px.top],
        "size_physical_px": monitor.full_px.size(),
        "native_pixels_per_point": monitor.native_pixels_per_point,
        "work_area": monitor.work_px.map(|work| json!({
            "origin_physical_px": [work.left, work.top],
            "size_physical_px": work.size(),
            "size_native_logical": work.size().map(|[w, h]| [f64::from(w) / monitor.native_pixels_per_point, f64::from(h) / monitor.native_pixels_per_point]),
        })),
    }));
    json!({
        "native_pixels_per_point": window.native_pixels_per_point,
        "monitor": monitor,
        "monitor_size_physical_px": window.monitor_size_px,
        "inner": window.inner.map(rect),
        "outer": window.outer.map(rect),
        "client_size_physical_px": window.client_size_px,
        "maximized": window.maximized,
        "minimized": window.minimized,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persists_requested_zoom_and_signed_desktop_origin() {
        let path =
            std::env::temp_dir().join(format!("gaius-dpi-settings-{}.json", std::process::id()));
        let settings = Settings {
            zoom: Some(UiScale::P250),
            window: Some(RestoredGeometry {
                outer_origin_px: [-1900, 42],
                client_size_logical: [801.0, 540.5],
            }),
        };
        write_json(&path, &settings).unwrap();
        let data = fs::read(&path).unwrap();
        fs::remove_file(path).unwrap();
        assert_eq!(serde_json::from_slice::<Settings>(&data).unwrap(), settings);
        assert_eq!(serde_json::from_str::<Settings>("{}").unwrap().zoom, None);
    }
}
