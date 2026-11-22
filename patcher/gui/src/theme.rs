use std::sync::Arc;

use eframe::egui::{self, Color32, CornerRadius, Margin, Stroke, Theme, ThemePreference, Vec2};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ThemeMode {
    Dark,
    Light,
    System,
}

#[derive(Clone, Copy)]
pub struct Palette {
    pub background: Color32,
    pub panel: Color32,
    pub stroke: Color32,
    pub text: Color32,
    pub secondary: Color32,
    pub accent: Color32,
    pub warning: Color32,
    pub error: Color32,
    pub info: Color32,
}

impl Palette {
    pub const DARK: Self = Self {
        background: Color32::from_rgb(0x14, 0x16, 0x1B),
        panel: Color32::from_rgb(0x1C, 0x1F, 0x26),
        stroke: Color32::from_rgb(0x2A, 0x2E, 0x38),
        text: Color32::from_rgb(0xE7, 0xE9, 0xEE),
        secondary: Color32::from_rgb(0x9A, 0xA1, 0xAE),
        accent: Color32::from_rgb(0x4F, 0xB2, 0x86),
        warning: Color32::from_rgb(0xE0, 0xA8, 0x4A),
        error: Color32::from_rgb(0xE5, 0x64, 0x5A),
        info: Color32::from_rgb(0x6A, 0xA4, 0xF0),
    };

    pub const LIGHT: Self = Self {
        background: Color32::from_rgb(0xF4, 0xF6, 0xF8),
        panel: Color32::from_rgb(0xFF, 0xFF, 0xFF),
        stroke: Color32::from_rgb(0xD7, 0xDC, 0xE3),
        text: Color32::from_rgb(0x1F, 0x25, 0x2D),
        secondary: Color32::from_rgb(0x66, 0x70, 0x7D),
        accent: Color32::from_rgb(0x1B, 0x80, 0x5C),
        warning: Color32::from_rgb(0x9B, 0x61, 0x08),
        error: Color32::from_rgb(0xB8, 0x3D, 0x35),
        info: Color32::from_rgb(0x2C, 0x69, 0xB8),
    };

    pub fn on_accent(self) -> Color32 {
        if self.background.r() < 100 {
            self.background
        } else {
            Color32::WHITE
        }
    }

    pub(crate) fn verification_caption(
        self,
        ui: &egui::Ui,
        text: impl Into<String>,
    ) -> egui::RichText {
        // Use at least 12 points, and keep the pixel floor at sub-100% zoom.
        let size = 12.0 / ui.ctx().pixels_per_point().min(1.0);
        egui::RichText::new(text)
            .size(snap(ui, size))
            .color(self.text)
    }

    pub fn tint(self, color: Color32, amount: f32) -> Color32 {
        let blend = |front: u8, back: u8| {
            (front as f32 * amount + back as f32 * (1.0 - amount)).round() as u8
        };
        Color32::from_rgb(
            blend(color.r(), self.panel.r()),
            blend(color.g(), self.panel.g()),
            blend(color.b(), self.panel.b()),
        )
    }
}

impl ThemeMode {
    pub fn preference(self) -> ThemePreference {
        match self {
            Self::Dark => ThemePreference::Dark,
            Self::Light => ThemePreference::Light,
            Self::System => ThemePreference::System,
        }
    }

    pub fn effective(self, system: Option<Theme>) -> Theme {
        match self {
            Self::Dark => Theme::Dark,
            Self::Light => Theme::Light,
            Self::System => system.unwrap_or(Theme::Dark),
        }
    }

    pub fn palette(self, system: Option<Theme>) -> Palette {
        match self.effective(system) {
            Theme::Dark => Palette::DARK,
            Theme::Light => Palette::LIGHT,
        }
    }
}

pub fn apply(ctx: &egui::Context, mode: ThemeMode) {
    ctx.style_mut_of(Theme::Dark, |style| customize(style, Palette::DARK));
    ctx.style_mut_of(Theme::Light, |style| customize(style, Palette::LIGHT));
    ctx.set_theme(mode.preference());
}

pub(crate) fn snap(ui: &egui::Ui, points: f32) -> f32 {
    snap_points(points, ui.ctx().pixels_per_point())
}

pub(crate) fn scroll_end_padding(ui: &mut egui::Ui) {
    // Cover the largest UI text (including runtime CJK fallback) at this DPI.
    // Row height minus ascent is a conservative descender bound (it includes line gap).
    let size = ui
        .style()
        .text_styles
        .values()
        .map(|font| font.size)
        .fold(snap(ui, 22.0), f32::max);
    let descender = ui.fonts_mut(|fonts| {
        [
            egui::FontId::proportional(size),
            egui::FontId::monospace(size),
        ]
        .into_iter()
        .map(|font| {
            let galley = fonts.layout_no_wrap("gjpqy国語".to_owned(), font, Color32::WHITE);
            galley
                .rows
                .iter()
                .flat_map(|row| &row.glyphs)
                .map(|glyph| glyph.font_face_height - glyph.font_face_ascent)
                .fold(0.0_f32, f32::max)
        })
        .fold(0.0_f32, f32::max)
    });
    let ppp = ui.ctx().pixels_per_point();
    ui.add_space(((descender * ppp).ceil() + 8.0) / ppp);
}

fn snap_points(points: f32, ppp: f32) -> f32 {
    (points * ppp).round().max(1.0) / ppp
}

pub(crate) fn configure_text(ctx: &egui::Context) {
    ctx.options_mut(|options| {
        for style in [&mut options.dark_style, &mut options.light_style] {
            let text = &mut Arc::make_mut(style).visuals.text_options;
            text.font_hinting = true;
            text.subpixel_binning = false;
        }
        options.tessellation_options.round_text_to_pixels = true;
    });
}

pub(crate) fn snap_styles(ctx: &egui::Context, ppp: f32) {
    ctx.all_styles_mut(|style| {
        for font in style.text_styles.values_mut() {
            font.size = snap_points(font.size, ppp);
        }
    });
}

pub(crate) fn focus_ring(ui: &egui::Ui, response: &egui::Response, color: Color32) {
    if response.has_focus() {
        ui.painter().rect_stroke(
            response.rect.shrink(2.0),
            CornerRadius::same(6),
            Stroke::new(2.0, color),
            egui::StrokeKind::Inside,
        );
    }
}

fn customize(style: &mut egui::Style, palette: Palette) {
    // Start with unsnapped defaults on each DPI change; never re-round old values.
    style.text_styles = egui::Style::default().text_styles;
    style.spacing.interact_size = Vec2::splat(24.0);
    style.spacing.item_spacing = Vec2::splat(8.0);
    style.spacing.button_padding = Vec2::new(16.0, 8.0);
    style.spacing.window_margin = Margin::same(16);
    style.visuals.dark_mode = palette.background.r() < 100;
    style.visuals.override_text_color = Some(palette.text);
    style.visuals.panel_fill = palette.background;
    style.visuals.window_fill = palette.panel;
    style.visuals.extreme_bg_color = palette.background;
    style.visuals.faint_bg_color = palette.panel;
    style.visuals.selection.bg_fill = palette.accent.linear_multiply(0.25);
    style.visuals.selection.stroke = Stroke::new(1.0, palette.accent);
    style.visuals.widgets.noninteractive.bg_fill = palette.background;
    style.visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.inactive.bg_fill = palette.panel;
    style.visuals.widgets.inactive.weak_bg_fill = palette.panel;
    style.visuals.widgets.inactive.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.inactive.bg_stroke = Stroke::new(1.0, palette.stroke);
    style.visuals.widgets.hovered.bg_fill = palette.panel;
    style.visuals.widgets.hovered.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.hovered.bg_stroke = Stroke::new(1.0, palette.accent);
    style.visuals.widgets.active.bg_fill = palette.accent.linear_multiply(0.20);
    style.visuals.widgets.active.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.active.bg_stroke = Stroke::new(2.0, palette.accent);
    style.visuals.widgets.open.bg_fill = palette.panel;
    style.visuals.widgets.open.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.open.bg_stroke = Stroke::new(1.0, palette.accent);
    style.visuals.widgets.noninteractive.corner_radius = CornerRadius::same(8);
    style.visuals.widgets.inactive.corner_radius = CornerRadius::same(8);
    style.visuals.widgets.hovered.corner_radius = CornerRadius::same(8);
    style.visuals.widgets.active.corner_radius = CornerRadius::same(8);
    style.visuals.widgets.open.corner_radius = CornerRadius::same(8);
    style.visuals.window_corner_radius = CornerRadius::same(8);
    style.visuals.menu_corner_radius = CornerRadius::same(8);
    style.visuals.window_stroke = Stroke::new(1.0, palette.stroke);
    style.visuals.widgets.noninteractive.bg_stroke = Stroke::new(0.0, palette.stroke);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn luminance(color: Color32) -> f32 {
        let linear = |channel: u8| {
            let value = channel as f32 / 255.0;
            if value <= 0.04045 {
                value / 12.92
            } else {
                ((value + 0.055) / 1.055).powf(2.4)
            }
        };
        0.2126 * linear(color.r()) + 0.7152 * linear(color.g()) + 0.0722 * linear(color.b())
    }

    fn contrast(foreground: Color32, background: Color32) -> f32 {
        let (lighter, darker) = {
            let first = luminance(foreground);
            let second = luminance(background);
            (first.max(second), first.min(second))
        };
        (lighter + 0.05) / (darker + 0.05)
    }

    #[test]
    fn official_file_check_captions_meet_pixel_and_contrast_minima() {
        for (mode, system) in [
            (ThemeMode::Dark, None),
            (ThemeMode::Light, None),
            (ThemeMode::System, None),
            (ThemeMode::System, Some(Theme::Dark)),
            (ThemeMode::System, Some(Theme::Light)),
        ] {
            for scale in [0.75, 1.0, 1.25, 1.5, 2.0, 4.0] {
                let ctx = egui::Context::default();
                apply(&ctx, mode);
                ctx.set_zoom_factor(scale);
                let input = egui::RawInput {
                    system_theme: system,
                    ..Default::default()
                };
                let mut output = ctx.run_ui(input, |ui| {
                    assert_eq!(ctx.theme(), mode.effective(system));
                    let palette = mode.palette(ctx.system_theme());
                    let status = palette.verification_caption(ui, "Asset index: Failed");
                    let disclosure = status.clone().color(palette.error);
                    let path = palette.verification_caption(ui, "assets/indexes/34.json (Missing)");
                    let hint = palette.verification_caption(ui, "Use local mappings");
                    let more = palette.verification_caption(ui, "2 more files");
                    let repair = palette.verification_caption(ui, "Repair official files");
                    for (caption, surface) in [
                        (status, palette.background),
                        (disclosure, palette.background),
                        (path, palette.background),
                        (hint, palette.background),
                        (more, palette.background),
                        (repair, palette.tint(palette.error, 0.08)),
                    ] {
                        let job = egui::WidgetText::from(caption).into_layout_job(
                            ui.style(),
                            egui::TextStyle::Body.into(),
                            egui::Align::Center,
                        );
                        let format = &job.sections[0].format;
                        let pixels = format.font_id.size * ui.ctx().pixels_per_point();
                        let ratio = contrast(format.color, surface);
                        assert_eq!(format.color.a(), 255);
                        assert!(
                            pixels >= 12.0 && ratio >= 4.5,
                            "{mode:?}/{system:?} at {scale}×: {} is {pixels:.2} px at {ratio:.2}:1",
                            job.text,
                        );
                    }
                });
                output.textures_delta.clear();
                assert_eq!(output.pixels_per_point, scale);
            }
        }
    }

    #[test]
    fn palette_text_and_status_colors_meet_wcag_aa_on_surfaces() {
        for (theme, palette) in [("dark", Palette::DARK), ("light", Palette::LIGHT)] {
            for (name, foreground) in [
                ("primary", palette.text),
                ("secondary", palette.secondary),
                ("accent", palette.accent),
                ("warning", palette.warning),
                ("error", palette.error),
                ("info", palette.info),
            ] {
                for (surface, background) in
                    [("background", palette.background), ("panel", palette.panel)]
                {
                    let ratio = contrast(foreground, background);
                    assert!(ratio >= 4.5, "{theme} {name} on {surface} is {ratio:.2}:1");
                }
            }
            let accent_ratio = contrast(palette.on_accent(), palette.accent);
            assert!(
                accent_ratio >= 4.5,
                "{theme} primary button text on accent is {accent_ratio:.2}:1"
            );
            for status in [palette.accent, palette.warning, palette.error, palette.info] {
                let ratio = contrast(status, palette.tint(status, 0.05));
                assert!(
                    ratio >= 4.5,
                    "{theme} status text on its tint is {ratio:.2}:1"
                );
            }
        }
    }
}
