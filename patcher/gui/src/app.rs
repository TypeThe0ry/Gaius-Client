use std::collections::VecDeque;
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use eframe::egui::{
    self, Align, Button, CollapsingHeader, ComboBox, CornerRadius, Frame, Layout, Rect, RichText,
    ScrollArea, Stroke, TextStyle, Theme, Ui, UiBuilder, Vec2,
};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::i18n::Language;
use crate::icons::{self, Icon};
use crate::patcher_client::{
    BuildLogLine, BuildProgress, BuildRequest, BuildSettings, BuildTask, CheckState, DoctorReport,
    DoctorTask, HashComparison, HashState, MockBackend, PatcherBackend, PatcherClient,
    ProcessControl, VersionBuildResult,
};
use crate::theme::{self, Palette, ThemeMode};

const STEP_KEYS: [&str; 6] = [
    "step.select",
    "step.detect",
    "step.terms",
    "step.configure",
    "step.build",
    "step.complete",
];
const STEP_SHORT_KEYS: [&str; 6] = [
    "step.short.select",
    "step.short.detect",
    "step.short.terms",
    "step.short.configure",
    "step.short.build",
    "step.short.complete",
];
const VERSION_IDS: [&str; 3] = ["26.3", "26.2", "1.21.11"];
const BUILD_LOG_LIMIT: usize = 500;
const BUILD_PROGRESS_LIMIT: usize = 300;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ScreenTheme {
    Dark,
    Light,
}

impl ScreenTheme {
    pub fn mode(self) -> ThemeMode {
        match self {
            Self::Dark => ThemeMode::Dark,
            Self::Light => ThemeMode::Light,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReproStatus {
    Identical,
    Custom,
    Diverged,
    Unverified,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum BuildState {
    Idle,
    Running,
    Succeeded,
    Failed,
    Cancelled,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ScreenshotState {
    Normal,
    TermsAccepted,
    DetectFail,
    HashError,
    BuildFail,
    BuildSuccess,
    Cancelled,
    Running,
    UnsupportedMod,
    ModsScrolled,
    BadgeGreen,
    BadgeYellow,
    BadgeRed,
    ToolchainField,
    PreflightError,
}

#[derive(Clone, Copy, Debug)]
#[cfg(test)]
struct TextAudit {
    name: &'static str,
    rect: Rect,
    clip: Rect,
}

#[derive(Clone, Copy, Debug)]
struct LayoutRects {
    viewport: Rect,
    #[cfg(test)]
    top_bar: Rect,
    #[cfg(test)]
    top_bar_content: Rect,
    rail: Rect,
    content: Rect,
    content_used: Rect,
    content_scroll_viewport: Option<Rect>,
    content_scroll_present: bool,
    content_scroll_needed: bool,
    config_panel: Option<Rect>,
    config_scroll_viewport: Option<Rect>,
    config_add_mod: Option<Rect>,
    config_scroll_needed: bool,
    mod_first_row: Option<Rect>,
    terms_checkbox: Option<Rect>,
    terms_checkbox_mark: Option<Rect>,
    terms_checkbox_label: Option<Rect>,
    terms_hash_label: Option<Rect>,
    terms_hash_copy: Option<Rect>,
    #[cfg(test)]
    text_audits: [Option<TextAudit>; 32],
    completion_labels: [Option<Rect>; 4],
    completion_values: [Option<Rect>; 4],
    completion_copy: Option<Rect>,
    completion_artifacts: Option<Rect>,
    action_row: Rect,
    action_widgets: [Option<Rect>; 5],
    action_widget_count: usize,
    nav_next: Option<Rect>,
    nav_back: Option<Rect>,
    bottom_bar: Rect,
    footer: Rect,
}

impl Default for LayoutRects {
    fn default() -> Self {
        Self {
            viewport: Rect::NOTHING,
            #[cfg(test)]
            top_bar: Rect::NOTHING,
            #[cfg(test)]
            top_bar_content: Rect::NOTHING,
            rail: Rect::NOTHING,
            content: Rect::NOTHING,
            content_used: Rect::NOTHING,
            content_scroll_viewport: None,
            content_scroll_present: false,
            content_scroll_needed: false,
            config_panel: None,
            config_scroll_viewport: None,
            config_add_mod: None,
            config_scroll_needed: false,
            mod_first_row: None,
            terms_checkbox: None,
            terms_checkbox_mark: None,
            terms_checkbox_label: None,
            terms_hash_label: None,
            terms_hash_copy: None,
            #[cfg(test)]
            text_audits: [None; 32],
            completion_labels: [None; 4],
            completion_values: [None; 4],
            completion_copy: None,
            completion_artifacts: None,
            action_row: Rect::NOTHING,
            action_widgets: [None; 5],
            action_widget_count: 0,
            nav_next: None,
            nav_back: None,
            bottom_bar: Rect::NOTHING,
            footer: Rect::NOTHING,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
struct ModRow {
    path: String,
    supported: CompatGrade,
    licensed: bool,
}

#[derive(Clone, Debug, Serialize)]
struct LocalAsset {
    path: String,
    licensed: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
enum CompatGrade {
    Supported,
    Experimental,
    Unsupported,
}

#[derive(Clone, Debug)]
struct BuilderConfig {
    backend: usize,
    quality: usize,
    distance: u32,
    builtin_pack: bool,
    builtin_license: bool,
    resource_path: String,
    resource_paths: Vec<LocalAsset>,
    loader: usize,
    loader_license: bool,
    mods: Vec<ModRow>,
    mod_path: String,
    mappings_path: String,
    scale: f32,
    reduce_motion: bool,
}

impl Default for BuilderConfig {
    fn default() -> Self {
        Self {
            backend: 0,
            quality: 1,
            distance: 12,
            builtin_pack: false,
            builtin_license: false,
            resource_path: String::new(),
            resource_paths: Vec::new(),
            loader: 0,
            loader_license: false,
            mods: Vec::new(),
            mod_path: String::new(),
            mappings_path: String::new(),
            scale: 1.0,
            reduce_motion: system_reduce_motion(),
        }
    }
}

pub struct BuilderApp {
    language: Language,
    theme_mode: ThemeMode,
    page: usize,
    selected_versions: Vec<String>,
    doctor_report: Option<DoctorReport>,
    doctor_error: Option<String>,
    doctor_cancelled: bool,
    doctor_receiver: Option<DoctorTask>,
    doctor_control: Option<ProcessControl>,
    terms_scrolled: bool,
    terms_checked: bool,
    terms_hash: String,
    persist_acceptance: bool,
    config_confirmed: bool,
    config_tab: usize,
    config: BuilderConfig,
    build_state: BuildState,
    build_task: Option<BuildTask>,
    build_control: Option<ProcessControl>,
    build_progress: Vec<BuildProgress>,
    build_logs: VecDeque<String>,
    build_results: Vec<VersionBuildResult>,
    hash_mismatches: Vec<String>,
    build_error: Option<String>,
    build_started: Option<SystemTime>,
    build_elapsed: Option<Duration>,
    build_generation: u64,
    output_dir: String,
    shader_toolchain_path: String,
    minecraft_dir: String,
    output_bytes: u64,
    repro_status: ReproStatus,
    cancel_confirm: bool,
    cleanup_confirm: bool,
    cleanup_candidates: Vec<PathBuf>,
    mod_settings_open: Option<usize>,
    about_open: bool,
    theme_applied: Option<(ThemeMode, Option<Theme>)>,
    layout_rects: LayoutRects,
    content_has_focus: bool,
    page_scroll_offset: f32,
    scroll_page: usize,
}

impl BuilderApp {
    pub fn new(
        context: &egui::Context,
        language: Language,
        theme_mode: ThemeMode,
        page: usize,
        run_live_doctor: bool,
    ) -> Self {
        Self::with_state(
            context,
            language,
            theme_mode,
            page,
            run_live_doctor,
            ScreenshotState::Normal,
        )
    }

    fn with_state(
        context: &egui::Context,
        language: Language,
        theme_mode: ThemeMode,
        page: usize,
        run_live_doctor: bool,
        screenshot_state: ScreenshotState,
    ) -> Self {
        Self::with_state_font_mode(
            context,
            language,
            theme_mode,
            page,
            run_live_doctor,
            screenshot_state,
            true,
        )
    }

    #[cfg(test)]
    fn with_state_without_system_fonts(
        context: &egui::Context,
        language: Language,
        theme_mode: ThemeMode,
        page: usize,
        screenshot_state: ScreenshotState,
    ) -> Self {
        Self::with_state_font_mode(
            context,
            language,
            theme_mode,
            page,
            false,
            screenshot_state,
            false,
        )
    }

    fn with_state_font_mode(
        context: &egui::Context,
        language: Language,
        theme_mode: ThemeMode,
        page: usize,
        run_live_doctor: bool,
        screenshot_state: ScreenshotState,
        use_system_fonts: bool,
    ) -> Self {
        install_cjk_font(context, use_system_fonts);
        theme::apply(context, theme_mode);
        let terms_hash = terms_text(language).sha256();
        let mut app = Self {
            language,
            theme_mode,
            page: page.clamp(1, STEP_KEYS.len()),
            selected_versions: vec!["26.3".to_owned()],
            doctor_report: None,
            doctor_error: None,
            doctor_cancelled: false,
            doctor_receiver: None,
            doctor_control: None,
            terms_scrolled: false,
            terms_checked: false,
            terms_hash,
            persist_acceptance: false,
            config_confirmed: false,
            config_tab: usize::from(matches!(
                screenshot_state,
                ScreenshotState::UnsupportedMod | ScreenshotState::ModsScrolled
            )) * 2,
            config: BuilderConfig::default(),
            build_state: BuildState::Idle,
            build_task: None,
            build_control: None,
            build_progress: Vec::new(),
            build_logs: VecDeque::new(),
            build_results: Vec::new(),
            hash_mismatches: Vec::new(),
            build_error: None,
            build_started: None,
            build_elapsed: None,
            build_generation: 0,
            output_dir: String::new(),
            shader_toolchain_path: String::new(),
            minecraft_dir: String::new(),
            output_bytes: 0,
            repro_status: ReproStatus::Unverified,
            cancel_confirm: false,
            cleanup_confirm: false,
            cleanup_candidates: Vec::new(),
            mod_settings_open: None,
            about_open: false,
            theme_applied: None,
            layout_rects: LayoutRects::default(),
            content_has_focus: false,
            page_scroll_offset: 0.0,
            scroll_page: page,
        };
        if run_live_doctor {
            app.start_doctor();
        } else if let Ok(report) = MockBackend.doctor() {
            app.doctor_report = Some(report);
        }
        if screenshot_state == ScreenshotState::DetectFail {
            app.doctor_error = Some(app.language.text("doctor.mock.java_missing").to_owned());
        }
        if screenshot_state == ScreenshotState::HashError {
            app.doctor_report = Some(failed_hash_report());
        }
        if screenshot_state == ScreenshotState::UnsupportedMod {
            app.config.loader = 1;
            app.config.mods.push(ModRow {
                path: "local/example-mod.jar".to_owned(),
                supported: CompatGrade::Unsupported,
                licensed: true,
            });
        }
        if screenshot_state == ScreenshotState::ModsScrolled {
            app.config.loader = 1;
            app.config.mods = (1..=6)
                .map(|index| ModRow {
                    path: format!("local/example-mod-{index}.jar"),
                    supported: CompatGrade::Unsupported,
                    licensed: true,
                })
                .collect();
        }
        if matches!(
            screenshot_state,
            ScreenshotState::BadgeGreen | ScreenshotState::BuildSuccess
        ) {
            app.mock_completed(ReproStatus::Identical);
        } else if screenshot_state == ScreenshotState::BadgeYellow {
            app.mock_completed(ReproStatus::Custom);
        } else if screenshot_state == ScreenshotState::BadgeRed {
            app.mock_completed(ReproStatus::Diverged);
        } else if screenshot_state == ScreenshotState::BuildFail {
            app.mock_failed(
                app.language.text("build.stage.compile"),
                app.language.text("build.mock.compile_failed"),
            );
        } else if screenshot_state == ScreenshotState::PreflightError {
            app.mock_failed(
                app.language.text("build.stage.verify"),
                "26.3 requires a prebuilt shader toolchain; use --shader-toolchain <dir> or place it at <work-root>/tools/shader-toolchain",
            );
            app.build_progress.push(BuildProgress {
                schema: "gaius-patcher-progress-v1".to_owned(),
                phase: "error".to_owned(),
                step: "failed".to_owned(),
                pct: 100,
                message: "26.3 requires a prebuilt shader toolchain".to_owned(),
                error: Some("26.3 requires a prebuilt shader toolchain; use --shader-toolchain <dir> or place it at <work-root>/tools/shader-toolchain".to_owned()),
                version: Some("26.3".to_owned()),
            });
        } else if screenshot_state == ScreenshotState::Cancelled {
            app.build_state = BuildState::Cancelled;
            app.build_progress.push(mock_progress(
                "stage",
                42,
                app.language.text("build.mock.cancelled"),
            ));
        } else if screenshot_state == ScreenshotState::Running {
            app.build_state = BuildState::Running;
            app.build_progress.push(mock_progress(
                "compile",
                68,
                app.language.text("build.mock.compiling"),
            ));
        }
        app
    }

    pub fn native(context: &eframe::CreationContext<'_>) -> Self {
        let language = std::env::var("GAIUS_BUILDER_LANG")
            .ok()
            .and_then(|value| Language::parse(&value))
            .unwrap_or(Language::Chinese);
        let mut app = Self::new(&context.egui_ctx, language, ThemeMode::Dark, 1, true);
        app.persist_acceptance = true;
        app
    }

    fn start_doctor(&mut self) {
        let task = PatcherClient.start_doctor();
        self.doctor_control = Some(task.control.clone());
        self.doctor_receiver = Some(task);
        self.doctor_report = None;
        self.doctor_error = None;
        self.doctor_cancelled = false;
    }

    fn poll_doctor(&mut self) {
        let result =
            self.doctor_receiver
                .as_ref()
                .and_then(|task| match task.receiver.try_recv() {
                    Ok(result) => Some(result),
                    Err(std::sync::mpsc::TryRecvError::Empty) => None,
                    Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                        Some(Err(crate::patcher_client::PatcherError(
                            "doctor worker stopped unexpectedly".to_owned(),
                        )))
                    }
                });
        if let Some(result) = result {
            self.doctor_receiver = None;
            self.doctor_control = None;
            match result {
                Ok(report) => self.doctor_report = Some(report),
                Err(error) => self.doctor_error = Some(error.to_string()),
            }
        }
    }

    fn poll_build(&mut self) {
        let mut terminal = None;
        if let Some(task) = &self.build_task {
            while let Ok(progress) = task.progress.try_recv() {
                self.build_progress.push(progress);
                if self.build_progress.len() > BUILD_PROGRESS_LIMIT {
                    self.build_progress.remove(0);
                }
            }
            while let Ok(BuildLogLine { version, line }) = task.logs.try_recv() {
                self.build_logs
                    .push_back(format!("[{version}] {}", line.trim_end()));
                while self.build_logs.len() > BUILD_LOG_LIMIT {
                    self.build_logs.pop_front();
                }
            }
            match task.result.try_recv() {
                Ok(result) => terminal = Some(result),
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    terminal = Some(Err(crate::patcher_client::PatcherError(
                        "build worker stopped unexpectedly".to_owned(),
                    )));
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => {}
            }
        }
        if let Some(result) = terminal {
            let cancelled = self
                .build_control
                .as_ref()
                .is_some_and(ProcessControl::is_cancelled);
            self.build_task = None;
            self.build_control = None;
            self.build_elapsed = self
                .build_started
                .and_then(|started| started.elapsed().ok());
            match result {
                Ok(output) => {
                    retain_partial_outputs(&mut self.cleanup_candidates, &output.versions);
                    self.build_results = output.versions;
                    self.output_bytes = directory_size(Path::new(&self.output_dir));
                    self.refresh_hash_status();
                    if cancelled {
                        self.build_state = BuildState::Cancelled;
                    } else if output.exit_code == 0 {
                        self.build_state = BuildState::Succeeded;
                        show_notification(self.language.text("build.notification"));
                    } else {
                        self.build_state = BuildState::Failed;
                        self.build_error = self
                            .build_progress
                            .iter()
                            .rev()
                            .find(|progress| progress.phase == "error")
                            .map(|progress| localized_build_failure(self.language, progress))
                            .or_else(|| {
                                Some(localized_last_error(
                                    self.language,
                                    &output.stderr,
                                    &output.stdout,
                                ))
                            });
                    }
                }
                Err(error) => {
                    self.build_state = if cancelled {
                        BuildState::Cancelled
                    } else {
                        BuildState::Failed
                    };
                    self.build_error = Some(error.to_string());
                }
            }
        }
    }

    fn refresh_hash_status(&mut self) {
        let mut all_match = !self.build_results.is_empty();
        let mut mismatch = false;
        self.hash_mismatches.clear();
        for result in &self.build_results {
            match result.hashes.state {
                HashState::Match => {}
                HashState::Unknown => all_match = false,
                HashState::Mismatch => {
                    mismatch = true;
                    all_match = false;
                    self.hash_mismatches.extend(
                        result
                            .hashes
                            .mismatches
                            .iter()
                            .map(|path| format!("{}: {path}", result.version)),
                    );
                }
            }
        }
        self.repro_status = if mismatch {
            ReproStatus::Diverged
        } else if !all_match {
            ReproStatus::Unverified
        } else if self.config_is_custom() {
            ReproStatus::Custom
        } else {
            ReproStatus::Identical
        };
    }

    fn handle_keys(&mut self, context: &egui::Context) {
        let popup_open = context.any_popup_open();
        let scroll_step = if self.page == 4 {
            self.layout_rects
                .config_scroll_viewport
                .map(|rect| rect.height())
                .unwrap_or(self.layout_rects.content.height())
        } else {
            self.layout_rects.content.height()
        }
        .mul_add(0.85, 0.0)
        .max(36.0);
        let can_scroll_by_key =
            self.content_has_focus && !popup_open && !context.egui_wants_keyboard_input();
        let mut scroll_delta = 0.0;
        context.input_mut(|input| {
            let back = input.consume_key(egui::Modifiers::CTRL, egui::Key::ArrowLeft)
                || input.consume_key(egui::Modifiers::ALT, egui::Key::ArrowLeft);
            let next = input.consume_key(egui::Modifiers::CTRL, egui::Key::ArrowRight)
                || input.consume_key(egui::Modifiers::ALT, egui::Key::ArrowRight);
            if self.cancel_confirm || self.cleanup_confirm || self.about_open {
                if input.key_pressed(egui::Key::Escape) {
                    self.cancel_confirm = false;
                    self.cleanup_confirm = false;
                    self.about_open = false;
                }
            } else if !popup_open && (back || next) {
                if back {
                    self.page = self.page.saturating_sub(1).max(1);
                } else {
                    self.advance();
                }
            } else if can_scroll_by_key {
                for (key, delta) in [
                    (egui::Key::PageUp, -scroll_step),
                    (egui::Key::PageDown, scroll_step),
                    (egui::Key::ArrowUp, -36.0),
                    (egui::Key::ArrowDown, 36.0),
                ] {
                    if input.consume_key(egui::Modifiers::NONE, key) {
                        scroll_delta += delta;
                    }
                }
            }
        });
        if scroll_delta != 0.0 {
            self.page_scroll_offset = (self.page_scroll_offset + scroll_delta).max(0.0);
        }
    }

    fn record_action(&mut self, index: usize, response: &egui::Response) {
        if let Some(slot) = self.layout_rects.action_widgets.get_mut(index) {
            *slot = Some(response.rect);
            self.layout_rects.action_widget_count =
                self.layout_rects.action_widget_count.max(index + 1);
        }
    }

    fn audited_label(
        &mut self,
        ui: &mut Ui,
        name: &'static str,
        widget: impl egui::Widget,
    ) -> egui::Response {
        let clip = ui.clip_rect();
        let response = ui.add(widget);
        self.record_text_audit(name, response.rect, clip);
        response
    }

    fn record_text_audit(&mut self, name: &'static str, rect: Rect, clip: Rect) {
        #[cfg(not(test))]
        let _ = (name, rect, clip);
        #[cfg(test)]
        if let Some(slot) = self
            .layout_rects
            .text_audits
            .iter_mut()
            .find(|slot| slot.is_none())
        {
            *slot = Some(TextAudit { name, rect, clip });
        }
    }

    fn render_top_bar(&mut self, ui: &mut Ui, palette: Palette) -> (Rect, Rect) {
        let width = ui.available_width();
        let compact = self.config.scale >= 1.5 || width < 660.0;
        let overflow = width < 460.0;
        let margin = egui::Margin::symmetric(if compact { 8 } else { 16 }, 6);
        let stroke_width = 1.0;
        let content_height = ui
            .spacing()
            .interact_size
            .y
            .max(ui.text_style_height(&TextStyle::Heading));
        let bar_height =
            content_height + margin.top as f32 + margin.bottom as f32 + stroke_width * 2.0;
        let bar = ui.allocate_ui_with_layout(
            Vec2::new(width, bar_height),
            Layout::top_down(Align::Min),
            |ui| {
                Frame::new()
                    .fill(palette.panel)
                    .stroke(Stroke::new(stroke_width, palette.stroke))
                    .corner_radius(CornerRadius::same(8))
                    .inner_margin(margin)
                    .show(ui, |ui| {
                        let available = ui.available_size();
                        ui.set_width(available.x);
                        ui.with_layout(Layout::left_to_right(Align::Center), |ui| {
                            let logo = ui.label(
                                RichText::new("G").strong().color(palette.accent).size(20.0),
                            );
                            let mut content = logo.rect;
                            if width >= 180.0 {
                                let name =
                                    ui.label(
                                        RichText::new(self.language.text(if compact {
                                            "app.short_name"
                                        } else {
                                            "app.name"
                                        }))
                                        .strong()
                                        .size(if compact { 14.0 } else { 16.0 }),
                                    );
                                content = content.union(name.rect);
                            }
                            let controls =
                                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                    if compact {
                                        ui.spacing_mut().item_spacing.x = 4.0;
                                    }
                                    if overflow {
                                        let menu = ui.menu_button("", |ui| {
                                            ui.set_min_width(190.0);
                                            ui.label(self.language.text("language.label"));
                                            let menu_language = self.language;
                                            for (language, key) in [
                                                (Language::Chinese, "language.zh"),
                                                (Language::English, "language.en"),
                                            ] {
                                                ui.selectable_value(
                                                    &mut self.language,
                                                    language,
                                                    menu_language.text(key),
                                                );
                                            }
                                            ui.separator();
                                            ui.label(self.language.text("theme.label"));
                                            for (theme, key) in [
                                                (ThemeMode::System, "theme.system"),
                                                (ThemeMode::Light, "theme.light"),
                                                (ThemeMode::Dark, "theme.dark"),
                                            ] {
                                                ui.selectable_value(
                                                    &mut self.theme_mode,
                                                    theme,
                                                    self.language.text(key),
                                                );
                                            }
                                            ui.separator();
                                            ui.label(self.language.text("scale.label"));
                                            for (scale, key) in [
                                                (1.0, "scale.100"),
                                                (1.25, "scale.125"),
                                                (1.5, "scale.150"),
                                                (2.0, "scale.200"),
                                            ] {
                                                ui.selectable_value(
                                                    &mut self.config.scale,
                                                    scale,
                                                    self.language.text(key),
                                                );
                                            }
                                            ui.separator();
                                            if ui.button(self.language.text("app.about")).clicked()
                                            {
                                                self.about_open = true;
                                            }
                                        });
                                        let menu = menu
                                            .response
                                            .on_hover_text(self.language.text("app.settings.tip"));
                                        menu.widget_info(|| {
                                            egui::WidgetInfo::labeled(
                                                egui::Role::Button,
                                                true,
                                                self.language.text("app.settings"),
                                            )
                                        });
                                        icons::paint(
                                            ui.painter(),
                                            menu.rect.shrink(7.0),
                                            Icon::More,
                                            palette.text,
                                        );
                                        menu.rect
                                    } else {
                                        let about = if compact {
                                            ui.add_sized([28.0, 30.0], Button::new("i"))
                                                .on_hover_text(self.language.text("app.about.tip"))
                                        } else {
                                            ui.button(self.language.text("app.about"))
                                                .on_hover_text(self.language.text("app.about.tip"))
                                        };
                                        about.widget_info(|| {
                                            egui::WidgetInfo::labeled(
                                                egui::Role::Button,
                                                true,
                                                self.language.text("app.about"),
                                            )
                                        });
                                        if about.clicked() {
                                            self.about_open = true;
                                        }
                                        about.rect.union(self.setting_combos(ui, compact))
                                    }
                                });
                            content.union(controls.inner)
                        })
                        .inner
                    })
            },
        );
        let frame = bar.inner;
        (bar.response.rect, frame.inner)
    }

    fn setting_combos(&mut self, ui: &mut Ui, compact: bool) -> Rect {
        let theme = ComboBox::from_id_salt("theme-mode")
            .selected_text(if compact {
                self.language.text(match self.theme_mode {
                    ThemeMode::System => "theme.system.short",
                    ThemeMode::Light => "theme.light",
                    ThemeMode::Dark => "theme.dark",
                })
            } else {
                theme_label(self.language, self.theme_mode)
            })
            .width(if compact { 58.0 } else { 100.0 })
            .show_ui(ui, |ui| {
                ui.selectable_value(
                    &mut self.theme_mode,
                    ThemeMode::System,
                    self.language.text("theme.system"),
                );
                ui.selectable_value(
                    &mut self.theme_mode,
                    ThemeMode::Light,
                    self.language.text("theme.light"),
                );
                ui.selectable_value(
                    &mut self.theme_mode,
                    ThemeMode::Dark,
                    self.language.text("theme.dark"),
                );
            })
            .response
            .on_hover_text(self.language.text("theme.label"));
        let current_language = self.language;
        let language = ComboBox::from_id_salt("language")
            .selected_text(self.language.text(match self.language {
                Language::Chinese if compact => "language.short.zh",
                Language::Chinese => "language.zh",
                Language::English if compact => "language.short.en",
                Language::English => "language.en",
            }))
            .width(if compact { 54.0 } else { 92.0 })
            .show_ui(ui, |ui| {
                ui.selectable_value(
                    &mut self.language,
                    Language::Chinese,
                    current_language.text("language.zh"),
                );
                ui.selectable_value(
                    &mut self.language,
                    Language::English,
                    current_language.text("language.en"),
                );
            })
            .response
            .on_hover_text(self.language.text("language.label"));
        let scale = ComboBox::from_id_salt("ui-scale")
            .selected_text(self.language.text(match self.config.scale {
                scale if scale >= 1.9 => "scale.200",
                scale if scale >= 1.45 => "scale.150",
                scale if scale >= 1.2 => "scale.125",
                _ => "scale.100",
            }))
            .width(if compact { 50.0 } else { 64.0 })
            .show_ui(ui, |ui| {
                ui.selectable_value(&mut self.config.scale, 1.0, self.language.text("scale.100"));
                ui.selectable_value(
                    &mut self.config.scale,
                    1.25,
                    self.language.text("scale.125"),
                );
                ui.selectable_value(&mut self.config.scale, 1.5, self.language.text("scale.150"));
                ui.selectable_value(&mut self.config.scale, 2.0, self.language.text("scale.200"));
            })
            .response
            .on_hover_text(self.language.text("scale.tip"));
        let mut controls = theme.rect.union(language.rect).union(scale.rect);
        if !compact {
            controls = controls.union(ui.label(self.language.text("scale.label")).rect);
        }
        controls
    }

    fn render_steps(&mut self, ui: &mut Ui, palette: Palette) -> Rect {
        let row_height = if self.config.scale >= 1.45 {
            26.0
        } else {
            38.0
        };
        let row_gap = if self.config.scale >= 1.45 { 2.0 } else { 4.0 };
        Frame::new()
            .fill(palette.panel)
            .stroke(Stroke::new(1.0, palette.stroke))
            .corner_radius(CornerRadius::same(8))
            .inner_margin(8.0)
            .show(ui, |ui| {
                let available = ui.available_size();
                ui.set_width(available.x);
                ui.set_height(available.y);
                ScrollArea::vertical()
                    .id_salt("wizard-step-list")
                    .max_height(available.y)
                    .auto_shrink([false, false])
                    .scroll_bar_visibility(
                        egui::containers::scroll_area::ScrollBarVisibility::VisibleWhenNeeded,
                    )
                    .show(ui, |ui| {
                        ui.label(
                            RichText::new(self.language.text("step.header"))
                                .size(12.0)
                                .color(palette.secondary),
                        );
                        ui.add_space(8.0);
                        for (index, key) in STEP_KEYS.iter().enumerate() {
                            let step = index + 1;
                            let current = self.page == step;
                            let done = self.step_complete(step);
                            let visited = step <= self.page;
                            let step_label = if self.config.scale >= 1.5 {
                                self.language.text(STEP_SHORT_KEYS[index])
                            } else {
                                self.language.text(key)
                            };
                            let label = format!("{:02} {step_label}", step);
                            let size = Vec2::new(ui.available_width(), row_height);
                            let response = if visited {
                                ui.add_sized(
                                    size,
                                    Button::new("")
                                        .fill(if current {
                                            palette.tint(palette.accent, 0.05)
                                        } else {
                                            palette.panel
                                        })
                                        .stroke(Stroke::new(
                                            if current { 1.0 } else { 0.0 },
                                            if current {
                                                palette.accent
                                            } else {
                                                palette.panel
                                            },
                                        )),
                                )
                            } else {
                                let (rect, response) =
                                    ui.allocate_exact_size(size, egui::Sense::hover());
                                ui.painter().rect_filled(rect, 8.0, palette.panel);
                                response
                            };
                            let icon = if done { Icon::Check } else { Icon::Ring };
                            let icon_color = if done || current {
                                palette.accent
                            } else {
                                palette.secondary
                            };
                            let text_color = if current {
                                palette.text
                            } else {
                                palette.secondary
                            };
                            let text_pos = response.rect;
                            icons::paint(
                                ui.painter(),
                                Rect::from_center_size(
                                    egui::pos2(text_pos.left() + 18.0, text_pos.center().y),
                                    Vec2::splat(14.0),
                                ),
                                icon,
                                icon_color,
                            );
                            ui.painter().text(
                                egui::pos2(text_pos.left() + 34.0, text_pos.center().y),
                                egui::Align2::LEFT_CENTER,
                                format!("{step:02}"),
                                egui::FontId::monospace(14.0),
                                text_color,
                            );
                            ui.painter().text(
                                egui::pos2(text_pos.left() + 62.0, text_pos.center().y),
                                egui::Align2::LEFT_CENTER,
                                step_label,
                                egui::FontId::proportional(14.0),
                                text_color,
                            );
                            if visited {
                                response.widget_info(|| {
                                    egui::WidgetInfo::selected(
                                        egui::Role::Button,
                                        true,
                                        current,
                                        label.clone(),
                                    )
                                });
                            }
                            if visited && response.clicked() {
                                self.page = step;
                            }
                            ui.add_space(row_gap);
                        }
                    });
            })
            .response
            .rect
    }

    fn step_complete(&self, step: usize) -> bool {
        match step {
            1 => !self.selected_versions.is_empty(),
            2 => self.doctor_report.is_some(),
            3 => self.terms_checked,
            4 => self.config_confirmed,
            5 => {
                self.build_state == BuildState::Succeeded
                    || (!self.build_results.is_empty()
                        && matches!(self.build_state, BuildState::Failed | BuildState::Cancelled))
            }
            _ => false,
        }
    }

    fn render_content(&mut self, ui: &mut Ui, palette: Palette) {
        match self.page {
            1 => self.render_versions(ui, palette),
            2 => self.render_detect(ui, palette),
            3 => self.render_terms(ui, palette),
            4 => self.render_config(ui, palette),
            5 => self.render_build(ui, palette),
            _ => self.render_complete(ui, palette),
        }
    }

    fn render_versions(&mut self, ui: &mut Ui, palette: Palette) {
        self.audited_label(
            ui,
            "version.title",
            egui::Label::new(egui::RichText::new(self.language.text("version.title")).heading()),
        );
        self.audited_label(
            ui,
            "version.subtitle",
            egui::Label::new(
                RichText::new(self.language.text("version.subtitle")).color(palette.secondary),
            )
            .wrap(),
        );
        ui.add_space(4.0);
        for (index, id) in VERSION_IDS.iter().enumerate() {
            let installed = self
                .doctor_report
                .as_ref()
                .is_some_and(|report| version_detected(report, id));
            let selected = self.selected_versions.iter().any(|version| version == id);
            let card = Frame::new()
                .fill(palette.panel)
                .stroke(Stroke::new(
                    if selected { 1.5 } else { 1.0 },
                    if selected {
                        palette.accent
                    } else {
                        palette.stroke
                    },
                ))
                .corner_radius(CornerRadius::same(8))
                .inner_margin(8.0);
            let card_response = card.show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    let (checkbox, _) =
                        ui.allocate_exact_size(Vec2::splat(18.0), egui::Sense::hover());
                    ui.painter().rect_stroke(
                        checkbox.shrink(2.0),
                        CornerRadius::same(2),
                        Stroke::new(
                            1.5,
                            if selected {
                                palette.accent
                            } else {
                                palette.secondary
                            },
                        ),
                        egui::StrokeKind::Inside,
                    );
                    if selected {
                        ui.painter().add(egui::Shape::line(
                            vec![
                                egui::pos2(checkbox.left() + 4.0, checkbox.center().y),
                                egui::pos2(checkbox.center().x - 1.0, checkbox.bottom() - 4.0),
                                egui::pos2(checkbox.right() - 3.0, checkbox.top() + 4.0),
                            ],
                            Stroke::new(1.7, palette.accent),
                        ));
                    }
                    ui.vertical(|ui| {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new(*id).strong().size(16.0));
                            let (icon, key, color) = if installed {
                                (Icon::Check, "version.detected", palette.accent)
                            } else {
                                (Icon::Warning, "version.missing", palette.warning)
                            };
                            Frame::new()
                                .fill(palette.tint(color, 0.05))
                                .stroke(Stroke::new(1.0, color))
                                .corner_radius(CornerRadius::same(12))
                                .inner_margin(egui::Margin::symmetric(8, 2))
                                .show(ui, |ui| {
                                    ui.horizontal(|ui| {
                                        icons::show(ui, icon, color, 14.0);
                                        ui.label(
                                            RichText::new(self.language.text(key))
                                                .size(12.0)
                                                .color(color),
                                        );
                                    });
                                });
                        });
                        ui.label(
                            RichText::new(self.language.text(if index == 2 {
                                "version.estimate.121"
                            } else {
                                "version.estimate.modern"
                            }))
                            .size(12.0)
                            .color(palette.secondary),
                        );
                    });
                });
            });
            let label = format!(
                "{} {}",
                id,
                self.language.text(if installed {
                    "version.detected"
                } else {
                    "version.missing"
                })
            );
            let response = keep_focused_visible(
                &mut self.content_has_focus,
                ui.interact(
                    card_response.response.rect,
                    ui.make_persistent_id(("version-card", id)),
                    egui::Sense::click(),
                ),
            );
            response.widget_info(|| {
                egui::WidgetInfo::selected(egui::Role::Button, true, selected, label.clone())
            });
            if response.clicked() {
                if selected {
                    self.selected_versions.retain(|version| version != id);
                } else {
                    self.selected_versions.push((*id).to_owned());
                }
            }
            ui.add_space(4.0);
        }
        Frame::new()
            .fill(palette.panel)
            .corner_radius(CornerRadius::same(8))
            .inner_margin(8.0)
            .show(ui, |ui| {
                ui.label(RichText::new(self.language.text("version.launcher")).strong());
                ui.label(
                    RichText::new(self.language.text("version.launcher.body"))
                        .color(palette.secondary),
                );
                let redetect = ui.add_sized(
                    [ui.available_width(), 32.0],
                    Button::new(self.language.text("version.redetect")),
                );
                keep_focused_visible(&mut self.content_has_focus, redetect.clone());
                if redetect.clicked() {
                    self.start_doctor();
                }
            });
    }

    fn render_detect(&mut self, ui: &mut Ui, palette: Palette) {
        self.audited_label(
            ui,
            "doctor.title",
            egui::Label::new(egui::RichText::new(self.language.text("doctor.title")).heading()),
        );
        self.audited_label(
            ui,
            "doctor.subtitle",
            egui::Label::new(
                RichText::new(self.language.text("doctor.subtitle")).color(palette.secondary),
            )
            .wrap(),
        );
        ui.add_space(4.0);
        ScrollArea::vertical()
            .auto_shrink([false, false])
            .show(ui, |ui| {
                if self.doctor_cancelled {
                    ui.label(self.language.text("doctor.cancelled"));
                } else if let Some(error) = &self.doctor_error {
                    let error = error.clone();
                    let title = self.language.text("doctor.failed").to_owned();
                    let fix = self.language.text("doctor.fix.java").to_owned();
                    self.error_banner(ui, palette, &title, &error, &fix);
                } else if let Some(report) = &self.doctor_report {
                    let java = report.java.as_ref();
                    render_check(
                        ui,
                        self.language.text("doctor.java"),
                        &java
                            .map(|item| display_doctor_value(self.language, &item.value))
                            .unwrap_or_else(|| {
                                self.language.text("doctor.value.java_missing").to_owned()
                            }),
                        java.map(|item| item.state.clone())
                            .unwrap_or(CheckState::Missing),
                        palette,
                        self.language,
                    );
                    let memory = report.memory.as_ref();
                    render_check(
                        ui,
                        self.language.text("doctor.memory"),
                        &memory
                            .map(|item| display_doctor_value(self.language, &item.value))
                            .unwrap_or_else(|| {
                                self.language.text("doctor.value.unknown").to_owned()
                            }),
                        memory
                            .map(|item| item.state.clone())
                            .unwrap_or(CheckState::Warning),
                        palette,
                        self.language,
                    );
                    let disk = report.work_dir.as_ref();
                    render_check(
                        ui,
                        self.language.text("doctor.disk"),
                        &disk
                            .map(|item| display_doctor_value(self.language, &item.value))
                            .unwrap_or_else(|| {
                                self.language.text("doctor.value.unknown").to_owned()
                            }),
                        disk.map(|item| item.state.clone())
                            .unwrap_or(CheckState::Warning),
                        palette,
                        self.language,
                    );
                    let launcher = report.game_dir.as_ref();
                    render_check(
                        ui,
                        self.language.text("doctor.game_dir"),
                        &launcher
                            .map(|item| display_doctor_value(self.language, &item.value))
                            .unwrap_or_else(|| {
                                self.language.text("doctor.value.not_found").to_owned()
                            }),
                        launcher
                            .map(|item| item.state.clone())
                            .unwrap_or(CheckState::Missing),
                        palette,
                        self.language,
                    );
                    for version in &report.versions {
                        let state = if version.verified {
                            CheckState::Ready
                        } else if version.installed {
                            CheckState::Warning
                        } else {
                            CheckState::Missing
                        };
                        let value = version
                            .reason
                            .as_deref()
                            .map(|reason| display_doctor_reason(self.language, &version.id, reason))
                            .unwrap_or_else(|| self.language.text("version.verified").to_owned());
                        render_check(
                            ui,
                            &format!("{} · {}", self.language.text("doctor.hash"), version.id),
                            &value,
                            state,
                            palette,
                            self.language,
                        );
                    }
                    let raw = CollapsingHeader::new(self.language.text("doctor.raw"))
                        .id_salt("doctor-output")
                        .show(ui, |ui| {
                            ui.label(report.raw_output.as_str());
                            if !report.stderr.is_empty() {
                                ui.label(report.stderr.as_str());
                            }
                        });
                    keep_focused_visible(&mut self.content_has_focus, raw.header_response);
                } else if self.doctor_receiver.is_some() {
                    ui.spinner();
                    ui.label(self.language.text("doctor.checking"));
                    if let Some(pid) = self.doctor_control.as_ref().and_then(ProcessControl::pid) {
                        ui.label(format!("PID {pid}"));
                    }
                } else {
                    ui.label(self.language.text("doctor.no_data"));
                }
                if self
                    .selected_versions
                    .iter()
                    .any(|version| version == "1.21.11")
                {
                    ui.add_space(12.0);
                    ui.separator();
                    ui.label(RichText::new(self.language.text("doctor.mapping")).strong());
                    ui.label(
                        RichText::new(self.language.text("doctor.mapping.hint"))
                            .size(12.0)
                            .color(palette.secondary),
                    );
                    ui.horizontal(|ui| {
                        let field_width = (ui.available_width() - 92.0).max(120.0);
                        let field = ui.add_sized(
                            [field_width, 32.0],
                            egui::TextEdit::singleline(&mut self.config.mappings_path)
                                .hint_text(self.language.text("doctor.mapping.placeholder")),
                        );
                        keep_focused_visible(&mut self.content_has_focus, field);
                        let browse = ui.button(self.language.text("path.browse"));
                        keep_focused_visible(&mut self.content_has_focus, browse.clone());
                        if browse.clicked()
                            && let Some(path) = rfd::FileDialog::new().pick_file()
                        {
                            self.config.mappings_path = path.display().to_string();
                        }
                    });
                }
                ui.add_space(12.0);
                ui.separator();
                ui.label(RichText::new(self.language.text("doctor.minecraft_dir")).strong());
                ui.label(
                    RichText::new(self.language.text("doctor.minecraft_dir.hint"))
                        .size(12.0)
                        .color(palette.secondary),
                );
                ui.horizontal(|ui| {
                    let field_width = (ui.available_width() - 92.0).max(120.0);
                    let field = ui.add_sized(
                        [field_width, 32.0],
                        egui::TextEdit::singleline(&mut self.minecraft_dir)
                            .hint_text(self.language.text("doctor.minecraft_dir.placeholder")),
                    );
                    keep_focused_visible(&mut self.content_has_focus, field);
                    let browse = ui.button(self.language.text("path.browse"));
                    keep_focused_visible(&mut self.content_has_focus, browse.clone());
                    if browse.clicked()
                        && let Some(path) = rfd::FileDialog::new().pick_folder()
                    {
                        self.minecraft_dir = path.display().to_string();
                    }
                });
                if self
                    .selected_versions
                    .iter()
                    .any(|version| version == "26.3")
                {
                    ui.add_space(12.0);
                    ui.separator();
                    ui.label(RichText::new(self.language.text("doctor.toolchain")).strong());
                    ui.label(
                        RichText::new(self.language.text("doctor.toolchain.hint"))
                            .size(12.0)
                            .color(palette.secondary),
                    );
                    ui.horizontal(|ui| {
                        let field_width = (ui.available_width() - 92.0).max(120.0);
                        let field = ui.add_sized(
                            [field_width, 32.0],
                            egui::TextEdit::singleline(&mut self.shader_toolchain_path)
                                .hint_text(self.language.text("doctor.toolchain.placeholder")),
                        );
                        keep_focused_visible(&mut self.content_has_focus, field);
                        let browse = ui.button(self.language.text("path.browse"));
                        keep_focused_visible(&mut self.content_has_focus, browse.clone());
                        if browse.clicked()
                            && let Some(path) = rfd::FileDialog::new().pick_folder()
                        {
                            self.shader_toolchain_path = path.display().to_string();
                        }
                    });
                    if !self.shader_toolchain_path.trim().is_empty()
                        && let Err(_) =
                            validate_shader_toolchain(Path::new(self.shader_toolchain_path.trim()))
                    {
                        ui.label(
                            RichText::new(self.language.text("doctor.toolchain.invalid"))
                                .color(palette.error),
                        );
                    }
                }
            });
    }

    fn error_banner(&mut self, ui: &mut Ui, palette: Palette, title: &str, cause: &str, fix: &str) {
        let compact = self.config.scale >= 1.4;
        Frame::new()
            .fill(palette.tint(palette.error, 0.05))
            .stroke(Stroke::new(1.0, palette.error))
            .corner_radius(CornerRadius::same(8))
            .inner_margin(if compact { 6.0 } else { 12.0 })
            .show(ui, |ui| {
                if compact {
                    ui.spacing_mut().item_spacing.y = 4.0;
                    let summary = format!("{title}: {cause}");
                    let response = self.audited_label(
                        ui,
                        "error.summary",
                        egui::Label::new(
                            RichText::new(summary.as_str())
                                .strong()
                                .color(palette.error),
                        )
                        .truncate(),
                    );
                    response.on_hover_text(format!(
                        "{cause}\n{}: {fix}",
                        self.language.text("error.fix")
                    ));
                    return;
                }
                ui.horizontal(|ui| {
                    icons::show(ui, Icon::Cross, palette.error, 16.0);
                    self.audited_label(
                        ui,
                        "error.title",
                        egui::Label::new(RichText::new(title).strong().color(palette.error)),
                    );
                });
                if compact {
                    let detail = format!("{cause} · {}: {fix}", self.language.text("error.fix"));
                    let response = self.audited_label(
                        ui,
                        "error.detail",
                        egui::Label::new(RichText::new(detail.as_str()).color(palette.secondary))
                            .truncate(),
                    );
                    response.on_hover_text(detail);
                } else {
                    self.audited_label(ui, "error.cause", egui::Label::new(cause).wrap());
                    self.audited_label(
                        ui,
                        "error.fix",
                        egui::Label::new(
                            RichText::new(format!("{}: {fix}", self.language.text("error.fix")))
                                .color(palette.secondary),
                        )
                        .wrap(),
                    );
                }
            });
    }

    fn render_terms(&mut self, ui: &mut Ui, palette: Palette) {
        self.audited_label(
            ui,
            "terms.title",
            egui::Label::new(egui::RichText::new(self.language.text("terms.title")).heading()),
        );
        self.audited_label(
            ui,
            "terms.subtitle",
            egui::Label::new(
                RichText::new(self.language.text("terms.subtitle")).color(palette.secondary),
            )
            .wrap(),
        );
        ui.add_space(8.0);
        let content = terms_text(self.language);
        self.terms_hash = content.sha256();
        let output = ScrollArea::vertical()
            .id_salt("terms-scroll")
            .auto_shrink([false, false])
            .scroll_bar_visibility(
                egui::containers::scroll_area::ScrollBarVisibility::VisibleWhenNeeded,
            )
            .show(ui, |ui| {
                ui.add_space(8.0);
                for paragraph in content.split("\n\n") {
                    ui.label(RichText::new(paragraph).size(14.0));
                    ui.add_space(12.0);
                }
            });
        let max_scroll = (output.content_size.y - output.inner_rect.height()).max(0.0);
        if output.state.clamped_offset().y >= max_scroll - 2.0 || max_scroll <= 2.0 {
            self.terms_scrolled = true;
        }
    }

    fn persist_terms_acceptance(&self) -> bool {
        if !self.persist_acceptance {
            return true;
        }
        #[derive(Serialize)]
        struct Record<'a> {
            terms_sha256: &'a str,
            accepted_at_unix: u64,
        }
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_secs())
            .unwrap_or_default();
        let record = Record {
            terms_sha256: &self.terms_hash,
            accepted_at_unix: now,
        };
        let path = work_dir().join("terms-acceptance.json");
        if let Some(parent) = path.parent()
            && fs::create_dir_all(parent).is_err()
        {
            return false;
        }
        fs::write(path, serde_json::to_vec_pretty(&record).unwrap_or_default()).is_ok()
    }

    fn render_config(&mut self, ui: &mut Ui, palette: Palette) {
        let viewport = ui.clip_rect();
        let content_width = ui.available_width().min(viewport.width()).max(0.0);
        let panel_inner_width = (content_width - 24.0).max(0.0);
        let compact = content_width < 360.0;
        ui.set_width(content_width);
        ui.set_max_width(content_width);
        ui.horizontal_wrapped(|ui| {
            self.audited_label(
                ui,
                "config.title",
                egui::Label::new(egui::RichText::new(self.language.text("config.title")).heading()),
            );
            if !compact {
                ui.add_space(8.0);
            }
            Frame::new()
                .fill(palette.tint(palette.info, 0.05))
                .stroke(Stroke::new(1.0, palette.info))
                .corner_radius(CornerRadius::same(6))
                .inner_margin(egui::Margin::symmetric(9, 5))
                .show(ui, |ui| {
                    self.audited_label(
                        ui,
                        "config.not_applied_chip",
                        egui::Label::new(
                            RichText::new(self.language.text("config.not_applied_chip"))
                                .color(palette.info),
                        )
                        .wrap(),
                    );
                });
        });
        let panel = Frame::new()
            .fill(palette.panel)
            .stroke(Stroke::new(1.0, palette.stroke))
            .corner_radius(CornerRadius::same(8))
            .inner_margin(12.0)
            .show(ui, |ui| {
                ui.set_width(panel_inner_width);
                ui.set_max_width(panel_inner_width);
                ui.horizontal_wrapped(|ui| {
                    for (index, key) in ["config.base", "config.assets", "config.mods"]
                        .iter()
                        .enumerate()
                    {
                        let response = ui.selectable_value(
                            &mut self.config_tab,
                            index,
                            self.language.text(key),
                        );
                        keep_focused_visible(&mut self.content_has_focus, response);
                    }
                });
                ui.separator();
                match self.config_tab {
                    0 => self.render_base_config(ui, palette),
                    1 => self.render_assets_config(ui, palette),
                    _ => self.render_mod_config(ui, palette),
                }
            });
        self.layout_rects.config_panel = Some(panel.response.rect.intersect(viewport));
        self.layout_rects.config_scroll_viewport = Some(viewport);
        self.layout_rects.config_scroll_needed =
            panel.response.rect.height() > viewport.height() + 0.5;
    }

    fn render_base_config(&mut self, ui: &mut Ui, palette: Palette) {
        let form_width = ui
            .available_width()
            .min((ui.clip_rect().width() - 24.0).max(0.0));
        let columns = if form_width < 360.0 { 1 } else { 2 };
        let cell_width = if columns == 1 {
            form_width
        } else {
            (form_width - 16.0) * 0.5
        };
        egui::Grid::new("config-base-grid")
            .num_columns(columns)
            .spacing(Vec2::new(16.0, 10.0))
            .show(ui, |ui| {
                let backend_label = ui.label(self.language.text("config.backend"));
                if columns == 1 {
                    ui.end_row();
                }
                let backend =
                    ComboBox::from_id_salt("config-backend")
                        .selected_text(self.language.text(
                            ["config.webgpu", "config.webgl", "config.js"][self.config.backend],
                        ))
                        .width((cell_width * 0.72).clamp(64.0, 240.0))
                        .show_ui(ui, |ui| {
                            for (index, key) in ["config.webgpu", "config.webgl", "config.js"]
                                .iter()
                                .enumerate()
                            {
                                ui.selectable_value(
                                    &mut self.config.backend,
                                    index,
                                    self.language.text(key),
                                );
                            }
                        })
                        .response
                        .labelled_by(backend_label.id);
                keep_focused_visible(&mut self.content_has_focus, backend);
                ui.end_row();

                let quality_label = ui.label(self.language.text("config.quality"));
                if columns == 1 {
                    ui.end_row();
                }
                let quality = ComboBox::from_id_salt("config-quality")
                    .selected_text(self.language.text(
                        ["config.low", "config.balanced", "config.high"][self.config.quality],
                    ))
                    .width((cell_width * 0.72).clamp(64.0, 240.0))
                    .show_ui(ui, |ui| {
                        for (index, key) in ["config.low", "config.balanced", "config.high"]
                            .iter()
                            .enumerate()
                        {
                            ui.selectable_value(
                                &mut self.config.quality,
                                index,
                                self.language.text(key),
                            );
                        }
                    })
                    .response
                    .labelled_by(quality_label.id);
                keep_focused_visible(&mut self.content_has_focus, quality);
                ui.end_row();

                let distance_label = ui.label(self.language.text("config.distance"));
                if columns == 1 {
                    ui.end_row();
                }
                ui.horizontal(|ui| {
                    let value_width = cell_width.min(68.0);
                    let slider_width =
                        (cell_width - value_width - ui.spacing().item_spacing.x).max(0.0);
                    let response = keep_focused_visible(
                        &mut self.content_has_focus,
                        ui.add_sized(
                            [slider_width, 28.0],
                            egui::Slider::new(&mut self.config.distance, 2..=32).show_value(false),
                        ),
                    );
                    response.clone().labelled_by(distance_label.id);
                    let track = response.rect.shrink2(Vec2::new(9.0, 0.0));
                    let center_y = track.center().y;
                    let fraction = (self.config.distance - 2) as f32 / 30.0;
                    let thumb_x = egui::lerp(track.left()..=track.right(), fraction);
                    let painter = ui.painter();
                    painter.line_segment(
                        [track.left_center(), track.right_center()],
                        Stroke::new(4.0, palette.stroke),
                    );
                    painter.line_segment(
                        [track.left_center(), egui::pos2(thumb_x, center_y)],
                        Stroke::new(4.0, palette.accent),
                    );
                    if response.has_focus() {
                        painter.circle_stroke(
                            egui::pos2(thumb_x, center_y),
                            11.0,
                            Stroke::new(1.5, palette.accent),
                        );
                    }
                    painter.circle_filled(egui::pos2(thumb_x, center_y), 7.0, palette.panel);
                    painter.circle_stroke(
                        egui::pos2(thumb_x, center_y),
                        7.0,
                        Stroke::new(2.0, palette.accent),
                    );
                    ui.add_sized(
                        [value_width, 28.0],
                        egui::Label::new(format!(
                            "{}{}",
                            self.config.distance,
                            self.language.text("config.chunks")
                        ))
                        .halign(Align::RIGHT),
                    );
                });
                ui.end_row();

                ui.label(self.language.text("config.reduce_motion"));
                if columns == 1 {
                    ui.end_row();
                }
                let reduce_motion = ui.checkbox(
                    &mut self.config.reduce_motion,
                    self.language.text("config.state.enabled"),
                );
                keep_focused_visible(&mut self.content_has_focus, reduce_motion);
                ui.end_row();
            });
        ui.add_space(12.0);
        let custom = self.config_is_custom();
        let (icon, message, color) = if custom {
            (
                Icon::Warning,
                self.language.text("config.custom"),
                palette.warning,
            )
        } else {
            (
                Icon::Check,
                self.language.text("config.default"),
                palette.accent,
            )
        };
        Frame::new()
            .fill(palette.tint(color, 0.05))
            .stroke(Stroke::new(1.0, color))
            .corner_radius(CornerRadius::same(6))
            .inner_margin(egui::Margin::symmetric(10, 7))
            .show(ui, |ui| {
                ui.horizontal_wrapped(|ui| {
                    icons::show(ui, icon, color, 16.0);
                    ui.label(RichText::new(message).color(color));
                });
            });
        ui.add_space(if self.config.scale >= 1.9 { 0.0 } else { 8.0 });
        let caption = self.language.text("config.cli_limit").to_owned();
        let response = self.audited_label(
            ui,
            "config.cli_limit",
            egui::Label::new(
                RichText::new(caption.as_str())
                    .size(12.0)
                    .color(palette.secondary),
            )
            .truncate(),
        );
        response.on_hover_text(caption);
    }

    fn render_assets_config(&mut self, ui: &mut Ui, palette: Palette) {
        let form_width = ui
            .available_width()
            .min((ui.clip_rect().width() - 24.0).max(0.0));
        let columns = if form_width < 360.0 { 1 } else { 2 };
        let cell_width = if columns == 1 {
            form_width
        } else {
            (form_width - 16.0) * 0.5
        };
        egui::Grid::new("config-assets-grid")
            .num_columns(columns)
            .spacing(Vec2::new(16.0, 10.0))
            .show(ui, |ui| {
                ui.label(self.language.text("config.builtin_pack"));
                if columns == 1 {
                    ui.end_row();
                }
                let enabled = ui.checkbox(
                    &mut self.config.builtin_pack,
                    self.language.text("config.state.enabled"),
                );
                keep_focused_visible(&mut self.content_has_focus, enabled);
                ui.end_row();

                ui.label(self.language.text("config.license_confirmation"));
                if columns == 1 {
                    ui.end_row();
                }
                let license_label = self.language.text(if columns == 1 {
                    "config.license.short"
                } else {
                    "config.license"
                });
                let license = ui.add_enabled_ui(self.config.builtin_pack, |ui| {
                    wrapped_checkbox(ui, &mut self.config.builtin_license, license_label)
                });
                keep_focused_visible(&mut self.content_has_focus, license.inner.response);
                ui.end_row();

                ui.label(self.language.text("config.resource_path"));
                if columns == 1 {
                    ui.end_row();
                }
                ui.horizontal(|ui| {
                    let add_width = 96.0;
                    let field = ui.add_sized(
                        [
                            (cell_width - add_width - ui.spacing().item_spacing.x - 12.0).max(48.0),
                            32.0,
                        ],
                        egui::TextEdit::singleline(&mut self.config.resource_path)
                            .hint_text(self.language.text("config.resource_path")),
                    );
                    keep_focused_visible(&mut self.content_has_focus, field);
                    let add = ui.add_sized(
                        [add_width, 32.0],
                        Button::new(self.language.text("config.add_path")),
                    );
                    keep_focused_visible(&mut self.content_has_focus, add.clone());
                    if add.clicked() && !self.config.resource_path.trim().is_empty() {
                        self.config.resource_paths.push(LocalAsset {
                            path: self.config.resource_path.trim().to_owned(),
                            licensed: false,
                        });
                        self.config.resource_path.clear();
                    }
                });
                ui.end_row();
            });
        let mut remove = None;
        for (index, asset) in self.config.resource_paths.iter_mut().enumerate() {
            ui.horizontal_wrapped(|ui| {
                ui.add(egui::Label::new(asset.path.as_str()).truncate())
                    .on_hover_text(asset.path.as_str());
                let license = wrapped_checkbox(
                    ui,
                    &mut asset.licensed,
                    self.language.text("config.license.short"),
                );
                keep_focused_visible(&mut self.content_has_focus, license.response);
                let remove_response = ui
                    .small_button("")
                    .on_hover_text(self.language.text("config.remove"));
                keep_focused_visible(&mut self.content_has_focus, remove_response.clone());
                remove_response.widget_info(|| {
                    egui::WidgetInfo::labeled(
                        egui::Role::Button,
                        true,
                        self.language.text("config.remove"),
                    )
                });
                icons::paint(
                    ui.painter(),
                    remove_response.rect.shrink(7.0),
                    Icon::Cross,
                    palette.secondary,
                );
                if remove_response.clicked() {
                    remove = Some(index);
                }
            });
        }
        if let Some(index) = remove {
            self.config.resource_paths.remove(index);
        }
    }

    fn render_mod_config(&mut self, ui: &mut Ui, palette: Palette) {
        let form_width = ui
            .available_width()
            .min((ui.clip_rect().width() - 24.0).max(0.0));
        let columns = if form_width < 360.0 { 1 } else { 2 };
        let compact_layout = form_width < 360.0;
        let cell_width = if columns == 1 {
            form_width
        } else {
            (form_width - 16.0) * 0.5
        };
        egui::Grid::new("config-loader-grid")
            .num_columns(columns)
            .spacing(Vec2::new(16.0, 8.0))
            .show(ui, |ui| {
                let loader_label = ui.label(self.language.text("config.loader"));
                if columns == 1 {
                    ui.end_row();
                }
                let compact = cell_width < 280.0;
                ui.horizontal(|ui| {
                    let badge_width = if self.config.loader == 0 || compact {
                        0.0
                    } else {
                        118.0
                    };
                    let combo_width = ((cell_width - badge_width - ui.spacing().item_spacing.x)
                        * 0.6)
                        .clamp(64.0, 180.0);
                    let loader = ComboBox::from_id_salt("config-loader")
                        .selected_text(self.language.text(
                            [
                                "config.loader.none",
                                "config.loader.fabric",
                                "config.loader.neoforge",
                                "config.loader.forge",
                            ][self.config.loader],
                        ))
                        .width(combo_width)
                        .show_ui(ui, |ui| {
                            for (index, key) in [
                                "config.loader.none",
                                "config.loader.fabric",
                                "config.loader.neoforge",
                                "config.loader.forge",
                            ]
                            .iter()
                            .enumerate()
                            {
                                ui.selectable_value(
                                    &mut self.config.loader,
                                    index,
                                    self.language.text(key),
                                );
                            }
                        })
                        .response
                        .labelled_by(loader_label.id);
                    keep_focused_visible(&mut self.content_has_focus, loader);
                });
                if columns == 1 && self.config.loader != 0 {
                    ui.end_row();
                }
                if self.config.loader != 0 {
                    Frame::new()
                        .fill(palette.tint(palette.warning, 0.05))
                        .stroke(Stroke::new(1.0, palette.warning))
                        .corner_radius(CornerRadius::same(10))
                        .inner_margin(egui::Margin::symmetric(8, 2))
                        .show(ui, |ui| {
                            ui.horizontal_wrapped(|ui| {
                                icons::show(ui, Icon::Warning, palette.warning, 14.0);
                                ui.label(
                                    RichText::new(self.language.text("config.experimental"))
                                        .size(12.0)
                                        .color(palette.warning),
                                );
                            });
                        });
                }
                ui.end_row();
            });
        ui.add_space(4.0);
        ui.separator();
        ui.add_space(4.0);
        egui::Grid::new("config-mod-details-grid")
            .num_columns(columns)
            .spacing(Vec2::new(16.0, 8.0))
            .show(ui, |ui| {
                ui.label(self.language.text("config.license_confirmation"));
                if columns == 1 {
                    ui.end_row();
                }
                let license_label = self.language.text(if columns == 1 {
                    "config.license.short"
                } else {
                    "config.license"
                });
                let license = ui.add_enabled_ui(self.config.loader != 0, |ui| {
                    wrapped_checkbox(ui, &mut self.config.loader_license, license_label)
                });
                keep_focused_visible(&mut self.content_has_focus, license.inner.response);
                ui.end_row();

                ui.label(self.language.text("config.mod_path"));
                if columns == 1 {
                    ui.end_row();
                }
                ui.horizontal(|ui| {
                    let add_width = if self.language == Language::English {
                        112.0
                    } else {
                        96.0
                    };
                    let field_width =
                        (cell_width - add_width - ui.spacing().item_spacing.x - 12.0).max(48.0);
                    let field = ui.add_sized(
                        [field_width, 32.0],
                        egui::TextEdit::singleline(&mut self.config.mod_path)
                            .hint_text(self.language.text("config.mod_path")),
                    );
                    keep_focused_visible(&mut self.content_has_focus, field);
                    let add = ui.add_sized(
                        [add_width, 32.0],
                        Button::new(self.language.text("config.add_mod")),
                    );
                    self.layout_rects.config_add_mod = Some(add.rect);
                    keep_focused_visible(&mut self.content_has_focus, add.clone());
                    if add.clicked() && !self.config.mod_path.trim().is_empty() {
                        let path = self.config.mod_path.trim().to_owned();
                        self.config.mods.push(ModRow {
                            path,
                            supported: if self.config.loader == 0 {
                                CompatGrade::Supported
                            } else {
                                CompatGrade::Experimental
                            },
                            licensed: false,
                        });
                        self.config.mod_path.clear();
                    }
                });
                ui.end_row();
            });
        if self
            .config
            .mods
            .iter()
            .any(|row| row.supported == CompatGrade::Unsupported)
        {
            Frame::new()
                .fill(palette.tint(palette.error, 0.05))
                .stroke(Stroke::new(1.0, palette.error))
                .corner_radius(CornerRadius::same(6))
                .inner_margin(egui::Margin::symmetric(8, 5))
                .show(ui, |ui| {
                    ui.set_width((form_width - 16.0).max(0.0));
                    ui.set_max_width((form_width - 16.0).max(0.0));
                    ui.horizontal_wrapped(|ui| {
                        icons::show(ui, Icon::Warning, palette.error, 16.0);
                        ui.add(
                            egui::Label::new(
                                RichText::new(self.language.text("config.conflict"))
                                    .color(palette.error),
                            )
                            .wrap(),
                        );
                    });
                });
        }
        ui.add_space(6.0);
        let table_width = (form_width - ui.spacing().item_spacing.x).max(0.0);
        let spacing = ui.spacing().item_spacing.x;
        let columns_width = (table_width - spacing * 3.0).max(0.0);
        let remove_width = (columns_width * 0.14).min(48.0);
        let settings_width = (columns_width * 0.29).min(108.0);
        let grade_width = (columns_width * 0.28).min(132.0);
        let name_width = (columns_width - remove_width - settings_width - grade_width).max(0.0);
        if !compact_layout {
            ui.allocate_ui_with_layout(
                Vec2::new(table_width, 22.0),
                Layout::left_to_right(Align::Center),
                |ui| {
                    for (width, key) in [
                        (name_width, "config.table.name"),
                        (grade_width, "config.table.grade"),
                        (settings_width, "config.table.settings"),
                        (remove_width, "config.table.remove"),
                    ] {
                        ui.add_sized(
                            [width, 22.0],
                            egui::Label::new(
                                RichText::new(self.language.text(key)).strong().size(12.0),
                            )
                            .truncate(),
                        );
                    }
                },
            );
            ui.separator();
        }
        let mut remove = None;
        for (index, row) in self.config.mods.iter_mut().enumerate() {
            let row_response = if compact_layout {
                ui.vertical(|ui| {
                    let path = ui.add_sized(
                        [table_width, 26.0],
                        egui::Label::new(row.path.as_str()).truncate(),
                    );
                    path.on_hover_text(row.path.as_str());
                    let (grade, color) = match row.supported {
                        CompatGrade::Supported => ("config.grade.supported", palette.accent),
                        CompatGrade::Experimental => ("config.grade.experimental", palette.warning),
                        CompatGrade::Unsupported => ("config.grade.unsupported", palette.error),
                    };
                    ui.horizontal_wrapped(|ui| {
                        Frame::new()
                            .fill(palette.tint(color, 0.05))
                            .stroke(Stroke::new(1.0, color))
                            .corner_radius(CornerRadius::same(10))
                            .inner_margin(egui::Margin::symmetric(7, 2))
                            .show(ui, |ui| {
                                ui.label(
                                    RichText::new(self.language.text(grade))
                                        .size(12.0)
                                        .color(color),
                                );
                            });
                        let settings = ui
                            .button(self.language.text("config.table.settings"))
                            .on_hover_text(self.language.text("config.mod_settings"));
                        keep_focused_visible(&mut self.content_has_focus, settings.clone());
                        if settings.clicked() {
                            self.mod_settings_open = Some(index);
                        }
                        let remove_response = ui
                            .small_button("")
                            .on_hover_text(self.language.text("config.remove"));
                        keep_focused_visible(&mut self.content_has_focus, remove_response.clone());
                        remove_response.widget_info(|| {
                            egui::WidgetInfo::labeled(
                                egui::Role::Button,
                                true,
                                self.language.text("config.remove"),
                            )
                        });
                        icons::paint(
                            ui.painter(),
                            remove_response.rect.shrink(6.0),
                            Icon::Cross,
                            palette.secondary,
                        );
                        if remove_response.clicked() {
                            remove = Some(index);
                        }
                    });
                })
            } else {
                ui.allocate_ui_with_layout(
                    Vec2::new(table_width, 34.0),
                    Layout::left_to_right(Align::Center),
                    |ui| {
                        let path = ui.add_sized(
                            [name_width, 30.0],
                            egui::Label::new(row.path.as_str()).truncate(),
                        );
                        path.on_hover_text(row.path.as_str());
                        let (grade, color) = match row.supported {
                            CompatGrade::Supported => ("config.grade.supported", palette.accent),
                            CompatGrade::Experimental => {
                                ("config.grade.experimental", palette.warning)
                            }
                            CompatGrade::Unsupported => ("config.grade.unsupported", palette.error),
                        };
                        Frame::new()
                            .fill(palette.tint(color, 0.05))
                            .stroke(Stroke::new(1.0, color))
                            .corner_radius(CornerRadius::same(10))
                            .inner_margin(egui::Margin::symmetric(7, 2))
                            .show(ui, |ui| {
                                ui.add_sized(
                                    [(grade_width - 14.0).max(1.0), 20.0],
                                    egui::Label::new(
                                        RichText::new(self.language.text(grade))
                                            .size(12.0)
                                            .color(color),
                                    )
                                    .truncate(),
                                );
                            });
                        let settings = ui
                            .add_sized(
                                [settings_width, 28.0],
                                Button::new(self.language.text("config.table.settings")),
                            )
                            .on_hover_text(self.language.text("config.mod_settings"));
                        keep_focused_visible(&mut self.content_has_focus, settings.clone());
                        if settings.clicked() {
                            self.mod_settings_open = Some(index);
                        }
                        let remove_response = ui
                            .add_sized([remove_width, 28.0], Button::new(""))
                            .on_hover_text(self.language.text("config.remove"));
                        keep_focused_visible(&mut self.content_has_focus, remove_response.clone());
                        remove_response.widget_info(|| {
                            egui::WidgetInfo::labeled(
                                egui::Role::Button,
                                true,
                                self.language.text("config.remove"),
                            )
                        });
                        icons::paint(
                            ui.painter(),
                            remove_response.rect.shrink(8.0),
                            Icon::Cross,
                            palette.secondary,
                        );
                        if remove_response.clicked() {
                            remove = Some(index);
                        }
                    },
                )
            };
            if index == 0 {
                self.layout_rects.mod_first_row = Some(row_response.response.rect);
            }
        }
        if let Some(index) = remove {
            self.config.mods.remove(index);
        }
    }

    fn render_build(&mut self, ui: &mut Ui, palette: Palette) {
        self.audited_label(
            ui,
            "build.title",
            egui::Label::new(egui::RichText::new(self.language.text("build.title")).heading()),
        );
        self.audited_label(
            ui,
            "build.subtitle",
            egui::Label::new(
                RichText::new(self.language.text("build.subtitle")).color(palette.secondary),
            )
            .wrap(),
        );
        if self.build_state == BuildState::Failed {
            let title = self.language.text("build.failed").to_owned();
            let cause = self
                .build_error
                .clone()
                .unwrap_or_else(|| self.language.text("error.unknown").to_owned());
            let fix = self.language.text("build.fix").to_owned();
            self.error_banner(ui, palette, &title, &cause, &fix);
            ui.add_space(4.0);
        } else if self.build_state == BuildState::Cancelled {
            self.audited_label(
                ui,
                "build.cancelled",
                egui::Label::new(
                    RichText::new(self.language.text("build.cancelled")).color(palette.warning),
                ),
            );
            ui.add_space(4.0);
        }
        if self.output_dir.is_empty() {
            self.output_dir = default_output_dir();
        }
        let content_width = ui.available_width().min(ui.clip_rect().width());
        if content_width < 440.0 {
            ui.label(self.language.text("build.output"));
            let output = ui
                .add_sized(
                    [content_width, 32.0],
                    egui::TextEdit::singleline(&mut self.output_dir)
                        .hint_text(self.language.text("build.output")),
                )
                .on_hover_text(self.output_dir.as_str());
            keep_focused_visible(&mut self.content_has_focus, output);
        } else {
            ui.horizontal(|ui| {
                ui.label(self.language.text("build.output"));
                let field_width = ui.available_width().min(content_width - 80.0).min(440.0);
                let output = ui
                    .add_sized(
                        [field_width, 32.0],
                        egui::TextEdit::singleline(&mut self.output_dir)
                            .hint_text(self.language.text("build.output")),
                    )
                    .on_hover_text(self.output_dir.as_str());
                keep_focused_visible(&mut self.content_has_focus, output);
            });
        }
        ui.add_space(8.0);
        match self.build_state {
            BuildState::Idle => {
                ui.label(self.language.text("build.ready"));
            }
            BuildState::Running => {
                let event = self.build_progress.last();
                let progress = event.map(|item| item.pct as f32 / 100.0).unwrap_or(0.0);
                let phase = event.map(|item| item.phase.as_str()).unwrap_or("fetch");
                let stage_key = display_build_phase_key(phase);
                let available_width = ui.available_width().min(ui.clip_rect().width());
                let compact = available_width < 380.0;
                let group_width = 150.0 + 20.0 + 184.0;
                let progress_height = if compact { 270.0 } else { 144.0 };
                ui.allocate_ui_with_layout(
                    Vec2::new(available_width, progress_height),
                    if compact {
                        Layout::top_down(Align::Center)
                    } else {
                        Layout::left_to_right(Align::Center)
                    },
                    |ui| {
                        if compact {
                            draw_progress_ring(
                                ui,
                                progress,
                                self.language.text(stage_key),
                                palette,
                            );
                            self.render_build_stages(ui, palette, progress, phase);
                        } else {
                            ui.add_space(((available_width - group_width) / 2.0).max(0.0));
                            draw_progress_ring(
                                ui,
                                progress,
                                self.language.text(stage_key),
                                palette,
                            );
                            ui.add_space(20.0);
                            self.render_build_stages(ui, palette, progress, phase);
                        }
                    },
                );
                ui.allocate_ui_with_layout(
                    Vec2::new(available_width, 72.0),
                    Layout::top_down(Align::Center),
                    |ui| {
                        ui.add(
                            egui::Label::new(
                                RichText::new(
                                    event
                                        .map(|event| display_progress_message(self.language, event))
                                        .unwrap_or_else(|| {
                                            self.language.text("build.running").to_owned()
                                        }),
                                )
                                .color(palette.secondary),
                            )
                            .wrap()
                            .halign(Align::Center),
                        );
                        if let Some(version) = event.and_then(|event| event.version.as_deref()) {
                            ui.label(format!(
                                "{} · {version}",
                                self.language.text("complete.version")
                            ));
                        }
                        if let Some(pid) = self.build_control.as_ref().and_then(ProcessControl::pid)
                        {
                            ui.label(format!("PID {pid}"));
                        }
                    },
                );
            }
            BuildState::Succeeded => {
                ui.label(
                    RichText::new(self.language.text("build.succeeded")).color(palette.accent),
                );
                ui.label(format!(
                    "{} · {}",
                    self.language.text("build.output"),
                    self.output_dir
                ));
            }
            BuildState::Failed | BuildState::Cancelled => {}
        }
        if !self.build_progress.is_empty() || !self.build_logs.is_empty() {
            let log = CollapsingHeader::new(self.language.text("build.log"))
                .default_open(false)
                .show(ui, |ui| {
                    for event in &self.build_progress {
                        ui.label(
                            RichText::new(format!(
                                "{:>3}%  [{}]  {}{}",
                                event.pct,
                                display_build_phase(self.language, &event.phase),
                                event
                                    .version
                                    .as_deref()
                                    .map(|version| format!("{version} · "))
                                    .unwrap_or_default(),
                                display_progress_message(self.language, event)
                            ))
                            .monospace()
                            .size(12.0),
                        );
                    }
                    for line in &self.build_logs {
                        ui.label(RichText::new(line).monospace().size(11.0));
                    }
                    let copy = ui.button(self.language.text("build.copy_log"));
                    keep_focused_visible(&mut self.content_has_focus, copy.clone());
                    if copy.clicked() {
                        copy_diagnostics(&self.diagnostics());
                    }
                });
            keep_focused_visible(&mut self.content_has_focus, log.header_response);
        }
    }

    fn render_complete(&mut self, ui: &mut Ui, palette: Palette) {
        if self.build_state != BuildState::Succeeded && !self.build_results.is_empty() {
            self.audited_label(
                ui,
                "complete.title",
                egui::Label::new(
                    egui::RichText::new(self.language.text("complete.title")).heading(),
                ),
            );
            self.audited_label(
                ui,
                "complete.status",
                egui::Label::new(
                    RichText::new(self.language.text(match self.build_state {
                        BuildState::Failed => "build.failed",
                        BuildState::Cancelled => "build.cancelled",
                        _ => "complete.success",
                    }))
                    .color(if self.build_state == BuildState::Failed {
                        palette.error
                    } else {
                        palette.warning
                    }),
                ),
            );
            self.render_version_results(ui, palette);
            self.render_badge(ui, palette, self.repro_status);
            self.render_hash_details(ui);
            self.audited_label(
                ui,
                "complete.options_not_applied",
                egui::Label::new(
                    RichText::new(self.language.text("complete.options_not_applied"))
                        .color(palette.secondary),
                )
                .wrap(),
            );
            if let Some(error) = &self.build_error {
                self.audited_label(
                    ui,
                    "complete.error",
                    egui::Label::new(RichText::new(error).color(palette.error)).wrap(),
                );
            }
            return;
        }
        match self.build_state {
            BuildState::Succeeded => {
                let compact_header = ui.available_width() < 360.0;
                ui.horizontal(|ui| {
                    let animation = if self.config.reduce_motion {
                        1.0
                    } else {
                        ui.ctx().animate_value_with_time(
                            egui::Id::unique(("completion-check", self.build_generation)),
                            1.0,
                            0.55,
                        )
                    };
                    draw_success_check(
                        ui,
                        animation,
                        palette,
                        if compact_header { 28.0 } else { 42.0 },
                    );
                    ui.vertical(|ui| {
                        if compact_header {
                            ui.spacing_mut().item_spacing.y = 2.0;
                            self.audited_label(
                                ui,
                                "complete.success",
                                egui::Label::new(
                                    RichText::new(self.language.text("complete.success"))
                                        .strong()
                                        .size(14.0),
                                ),
                            );
                            self.audited_label(
                                ui,
                                "complete.title",
                                egui::Label::new(
                                    RichText::new(self.language.text("complete.title"))
                                        .size(10.0)
                                        .color(palette.secondary),
                                ),
                            );
                        } else {
                            self.audited_label(
                                ui,
                                "complete.success",
                                egui::Label::new(
                                    egui::RichText::new(self.language.text("complete.success"))
                                        .heading(),
                                ),
                            );
                            self.audited_label(
                                ui,
                                "complete.title",
                                egui::Label::new(
                                    RichText::new(self.language.text("complete.title"))
                                        .color(palette.secondary),
                                ),
                            );
                        }
                    });
                });
                let summary_width = ui.available_width().min(588.0);
                let label_width = (summary_width * 0.25).clamp(112.0, 148.0);
                let summary_row_height = if summary_width < 360.0 { 18.0 } else { 24.0 };
                let column_spacing = ui.spacing().item_spacing.x;
                let value_width = (summary_width - label_width - column_spacing).max(0.0);
                let (label, value) = add_completion_row(
                    ui,
                    summary_width,
                    label_width,
                    value_width,
                    summary_row_height,
                    self.language.text("complete.version"),
                    egui::Label::new(
                        self.selected_versions
                            .first()
                            .map(String::as_str)
                            .unwrap_or("—"),
                    )
                    .halign(Align::Min),
                );
                self.layout_rects.completion_labels[0] = Some(label.rect);
                self.layout_rects.completion_values[0] = Some(value.rect);

                let (location_label, location_path, location_copy) = if summary_width < 360.0 {
                    let compact_label_width = 72.0f32.min(summary_width * 0.38);
                    let compact_value_width =
                        (summary_width - compact_label_width - ui.spacing().item_spacing.x)
                            .max(0.0);
                    let location = ui.allocate_ui_with_layout(
                        Vec2::new(summary_width, 28.0),
                        Layout::left_to_right(Align::Center),
                        |ui| {
                            let label = add_left_cell(
                                ui,
                                Vec2::new(compact_label_width, 28.0),
                                egui::Label::new(self.language.text("complete.location"))
                                    .halign(Align::Min),
                            );
                            let copy_width = 62.0f32.min(compact_value_width);
                            let path_width =
                                (compact_value_width - copy_width - ui.spacing().item_spacing.x)
                                    .max(0.0);
                            let path_font = TextStyle::Body.resolve(ui.style());
                            let display = fit_middle_ellipsis(
                                ui,
                                &self.output_dir,
                                path_width,
                                path_font.clone(),
                                palette.text,
                            );
                            let path_cell_width = ui.fonts_mut(|fonts| {
                                fonts
                                    .layout_no_wrap(display.clone(), path_font, palette.text)
                                    .size()
                                    .x
                            });
                            let value = ui.horizontal(|ui| {
                                let path = add_left_cell(
                                    ui,
                                    Vec2::new(path_cell_width, 28.0),
                                    egui::Label::new(display)
                                        .halign(Align::Min)
                                        .selectable(true),
                                );
                                let path_rect = path.rect;
                                path.on_hover_text(self.output_dir.as_str());
                                let copy = keep_focused_visible(
                                    &mut self.content_has_focus,
                                    ui.add_sized(
                                        [copy_width, 26.0],
                                        Button::new(self.language.text("complete.copy")),
                                    ),
                                );
                                if copy.clicked() {
                                    ui.ctx().copy_text(self.output_dir.clone());
                                }
                                (path_rect, copy.rect)
                            });
                            (label.rect, value.inner.0, value.inner.1)
                        },
                    );
                    location.inner
                } else {
                    let location = ui.allocate_ui_with_layout(
                        Vec2::new(summary_width, 28.0),
                        Layout::left_to_right(Align::Center),
                        |ui| {
                            let label = add_left_cell(
                                ui,
                                Vec2::new(label_width, 28.0),
                                egui::Label::new(self.language.text("complete.location"))
                                    .halign(Align::Min),
                            );
                            let copy_width = 62.0f32.min(value_width);
                            let path_width =
                                (value_width - copy_width - ui.spacing().item_spacing.x).max(0.0);
                            let path_font = TextStyle::Body.resolve(ui.style());
                            let display = fit_middle_ellipsis(
                                ui,
                                &self.output_dir,
                                path_width,
                                path_font.clone(),
                                palette.text,
                            );
                            let path_cell_width = ui.fonts_mut(|fonts| {
                                fonts
                                    .layout_no_wrap(display.clone(), path_font, palette.text)
                                    .size()
                                    .x
                            });
                            let value = ui.horizontal(|ui| {
                                let path = add_left_cell(
                                    ui,
                                    Vec2::new(path_cell_width, 28.0),
                                    egui::Label::new(display)
                                        .halign(Align::Min)
                                        .selectable(true),
                                );
                                let path_rect = path.rect;
                                path.on_hover_text(self.output_dir.as_str());
                                let copy = keep_focused_visible(
                                    &mut self.content_has_focus,
                                    ui.add_sized(
                                        [copy_width, 26.0],
                                        Button::new(self.language.text("complete.copy")),
                                    ),
                                );
                                if copy.clicked() {
                                    ui.ctx().copy_text(self.output_dir.clone());
                                }
                                (path_rect, copy.rect)
                            });
                            (label.rect, value.inner.0, value.inner.1)
                        },
                    );
                    location.inner
                };
                self.layout_rects.completion_labels[1] = Some(location_label);
                self.layout_rects.completion_values[1] = Some(location_path);
                self.layout_rects.completion_copy = Some(location_copy);

                let (label, value) = add_completion_row(
                    ui,
                    summary_width,
                    label_width,
                    value_width,
                    summary_row_height,
                    self.language.text("complete.size"),
                    egui::Label::new(format!("{:.1} MB", self.output_bytes as f64 / 1_048_576.0))
                        .halign(Align::Min),
                );
                self.layout_rects.completion_labels[2] = Some(label.rect);
                self.layout_rects.completion_values[2] = Some(value.rect);

                let elapsed = self.build_elapsed.unwrap_or_default().as_secs_f32();
                let (label, value) = add_completion_row(
                    ui,
                    summary_width,
                    label_width,
                    value_width,
                    summary_row_height,
                    self.language.text("complete.elapsed"),
                    egui::Label::new(format!("{elapsed:.1} s")).halign(Align::Min),
                );
                self.layout_rects.completion_labels[3] = Some(label.rect);
                self.layout_rects.completion_values[3] = Some(value.rect);
                self.render_version_results(ui, palette);
                self.render_badge(ui, palette, self.repro_status);
                let explanation_key = match self.repro_status {
                    ReproStatus::Identical => "repro.explain_identical",
                    ReproStatus::Custom => "repro.explain_custom",
                    ReproStatus::Diverged => "repro.explain_diverged",
                    ReproStatus::Unverified => "repro.explain_unverified",
                };
                ui.add(
                    egui::Label::new(
                        RichText::new(self.language.text(explanation_key)).color(palette.secondary),
                    )
                    .wrap(),
                );
                self.render_hash_details(ui);
                ui.add(
                    egui::Label::new(
                        RichText::new(self.language.text("complete.options_not_applied"))
                            .color(palette.secondary),
                    )
                    .wrap(),
                );
            }
            _ => {
                ui.label(self.language.text("complete.no_build"));
            }
        }
    }

    fn render_badge(&self, ui: &mut Ui, palette: Palette, state: ReproStatus) {
        let (icon, text, color) = match state {
            ReproStatus::Identical => (
                Icon::Check,
                self.language.text("repro.identical"),
                palette.accent,
            ),
            ReproStatus::Custom => (
                Icon::Diamond,
                self.language.text("repro.custom"),
                palette.warning,
            ),
            ReproStatus::Diverged => (
                Icon::Cross,
                self.language.text("repro.diverged"),
                palette.error,
            ),
            ReproStatus::Unverified => (
                Icon::Question,
                self.language.text("repro.unverified"),
                palette.info,
            ),
        };
        Frame::new()
            .fill(palette.tint(color, 0.05))
            .stroke(Stroke::new(1.0, color))
            .corner_radius(CornerRadius::same(8))
            .inner_margin(10.0)
            .show(ui, |ui| {
                ui.horizontal_wrapped(|ui| {
                    icons::show(ui, icon, color, 16.0);
                    ui.add(egui::Label::new(RichText::new(text).strong().color(color)).wrap());
                });
            });
    }

    fn render_version_results(&mut self, ui: &mut Ui, palette: Palette) {
        if self.build_results.is_empty() {
            return;
        }
        ui.add_space(8.0);
        let available_width = ui.available_width().min(ui.clip_rect().width());
        let heading = add_left_cell(
            ui,
            Vec2::new(available_width, 22.0),
            egui::Label::new(RichText::new(self.language.text("complete.artifacts")).strong())
                .halign(Align::Min),
        );
        self.layout_rects.completion_artifacts = Some(heading.rect);
        for result in &self.build_results {
            let (status, color) = if result.exit_code != 0 {
                (self.language.text("build.failed"), palette.error)
            } else {
                (self.language.text("complete.success"), palette.accent)
            };
            let status_text = format!("{} · {status}", result.version);
            let path = result.output_dir.display().to_string();
            if available_width < 720.0 {
                ui.vertical(|ui| {
                    ui.add_sized(
                        [available_width, 22.0],
                        egui::Label::new(RichText::new(status_text).color(color)).truncate(),
                    );
                    ui.add_sized(
                        [available_width, 22.0],
                        egui::Label::new(path.as_str()).truncate(),
                    )
                    .on_hover_text(path.as_str());
                    let open = ui.button(self.language.text("complete.open_folder"));
                    keep_focused_visible(&mut self.content_has_focus, open.clone());
                    if result.exit_code == 0 && open.clicked() {
                        open_path(&result.output_dir);
                    }
                });
            } else {
                ui.horizontal(|ui| {
                    ui.label(RichText::new(status_text).color(color));
                    let path_width = (available_width - 236.0).clamp(80.0, 420.0);
                    ui.add_sized(
                        [path_width, 22.0],
                        egui::Label::new(path.as_str()).truncate(),
                    )
                    .on_hover_text(path.as_str());
                    let open = ui.button(self.language.text("complete.open_folder"));
                    keep_focused_visible(&mut self.content_has_focus, open.clone());
                    if result.exit_code == 0 && open.clicked() {
                        open_path(&result.output_dir);
                    }
                });
            }
        }
    }

    fn render_hash_details(&self, ui: &mut Ui) {
        if self.hash_mismatches.is_empty() {
            return;
        }
        CollapsingHeader::new(self.language.text("repro.mismatches"))
            .default_open(false)
            .show(ui, |ui| {
                for path in &self.hash_mismatches {
                    ui.label(
                        RichText::new(path)
                            .monospace()
                            .color(eframe::egui::Color32::RED),
                    );
                }
            });
    }

    fn render_build_stages(&self, ui: &mut Ui, palette: Palette, progress: f32, phase: &str) {
        let keys = [
            "build.stage.verify",
            "build.stage.patch",
            "build.stage.compile",
            "build.stage.package",
            "build.stage.compare",
        ];
        let active = match phase {
            "fetch" => 0,
            "stage" | "prepare" => 1,
            "compile" => 2,
            "publish" => 3,
            "compare" => 4,
            _ => 0,
        };
        ui.vertical(|ui| {
            for (index, key) in keys.iter().enumerate() {
                let done = index < active || (index == active && progress >= 1.0);
                let (icon, color) = if done {
                    (Icon::Check, palette.accent)
                } else if index == active {
                    (Icon::Ring, palette.info)
                } else {
                    (Icon::Bullet, palette.secondary)
                };
                ui.horizontal(|ui| {
                    icons::show(ui, icon, color, 16.0);
                    ui.add_sized(
                        [164.0, 22.0],
                        egui::Label::new(RichText::new(self.language.text(key)).color(color)),
                    );
                });
            }
        });
    }

    fn start_build(&mut self) {
        if self.selected_versions.is_empty() {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("build.select_version").to_owned());
            return;
        }
        if !self.terms_checked {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("build.accept_terms").to_owned());
            return;
        }
        let mappings_path = self.config.mappings_path.trim();
        if self
            .selected_versions
            .iter()
            .any(|version| version == "1.21.11")
            && !mappings_path.is_empty()
            && !Path::new(mappings_path).is_file()
        {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("build.mapping_missing").to_owned());
            return;
        }
        if self
            .selected_versions
            .iter()
            .any(|version| version == "26.3")
        {
            let toolchain = self.shader_toolchain_path.trim();
            if toolchain.is_empty() {
                self.build_state = BuildState::Failed;
                self.build_error = Some(self.language.text("build.toolchain_required").to_owned());
                return;
            }
            if validate_shader_toolchain(Path::new(toolchain)).is_err() {
                self.build_state = BuildState::Failed;
                self.build_error = Some(self.language.text("build.toolchain_invalid").to_owned());
                return;
            }
        }
        let minecraft_dir = self.minecraft_dir.trim();
        if !minecraft_dir.is_empty() && !Path::new(minecraft_dir).is_dir() {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("build.minecraft_dir_missing").to_owned());
            return;
        }
        if self
            .config
            .resource_paths
            .iter()
            .any(|pack| !Path::new(&pack.path).is_file())
        {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("build.resource_pack_missing").to_owned());
            return;
        }
        if self.output_dir.is_empty() {
            self.output_dir = default_output_dir();
        }
        if let Err(error) = self.write_build_options() {
            self.build_state = BuildState::Failed;
            self.build_error = Some(error);
            return;
        }
        self.config_confirmed = true;
        self.build_generation = self.build_generation.saturating_add(1);
        self.build_progress.clear();
        self.build_logs.clear();
        self.build_results.clear();
        self.hash_mismatches.clear();
        self.build_error = None;
        self.build_state = BuildState::Running;
        self.build_started = Some(SystemTime::now());
        let output = PathBuf::from(&self.output_dir);
        self.cleanup_candidates = self
            .selected_versions
            .iter()
            .map(|version| output.join(version))
            .filter(|path| !path.exists())
            .collect();
        let control = ProcessControl::default();
        let settings = BuildSettings {
            quality: Some(["low", "mid", "high"][self.config.quality.min(2)].to_owned()),
            render_distance: Some(self.config.distance),
            backend: Some(["webgpu", "webgl2", "auto"][self.config.backend.min(2)].to_owned()),
            resource_packs: self
                .config
                .resource_paths
                .iter()
                .map(|pack| PathBuf::from(&pack.path))
                .collect(),
        };
        let task = PatcherClient.start_build(
            self.selected_versions.clone(),
            output,
            (!mappings_path.is_empty()).then(|| PathBuf::from(mappings_path)),
            (!self.shader_toolchain_path.trim().is_empty())
                .then(|| PathBuf::from(self.shader_toolchain_path.trim())),
            (!minecraft_dir.is_empty()).then(|| PathBuf::from(minecraft_dir)),
            settings,
            control.clone(),
        );
        self.build_control = Some(control);
        self.build_task = Some(task);
    }

    fn config_is_custom(&self) -> bool {
        self.config.backend != 0
            || self.config.quality != 1
            || self.config.distance != 12
            || self.config.builtin_pack
            || self.config.builtin_license
            || !self.config.resource_path.trim().is_empty()
            || !self.config.resource_paths.is_empty()
            || self.config.loader != 0
            || self.config.loader_license
            || !self.config.mod_path.trim().is_empty()
            || !self.config.mods.is_empty()
    }

    fn write_build_options(&self) -> Result<(), String> {
        #[derive(Serialize)]
        struct BuildOptions<'a> {
            schema: &'static str,
            versions: &'a [String],
            applied_to_build: [&'static str; 8],
            not_applied_to_build: [&'static str; 3],
            graphics_backend: &'static str,
            quality: &'static str,
            view_distance_chunks: u32,
            built_in_pack_enabled: bool,
            built_in_pack_license_confirmed: bool,
            local_pack_path: &'a str,
            local_packs: &'a [LocalAsset],
            loader: &'static str,
            loader_license_confirmed: bool,
            local_mod_path: &'a str,
            mods: &'a [ModRow],
        }
        let backend = ["webgpu", "webgl2", "auto"][self.config.backend.min(2)];
        let quality = ["low", "mid", "high"][self.config.quality.min(2)];
        let loader = ["off", "fabric", "neoforge", "forge"][self.config.loader.min(3)];
        let record = BuildOptions {
            schema: "gaius-builder-options-v1",
            versions: &self.selected_versions,
            applied_to_build: [
                "output_dir",
                "mappings_path",
                "shader_toolchain_path",
                "minecraft_dir",
                "graphics_backend",
                "quality",
                "view_distance",
                "local_packs",
            ],
            not_applied_to_build: ["built_in_pack", "loader", "mods"],
            graphics_backend: backend,
            quality,
            view_distance_chunks: self.config.distance,
            built_in_pack_enabled: self.config.builtin_pack,
            built_in_pack_license_confirmed: self.config.builtin_license,
            local_pack_path: &self.config.resource_path,
            local_packs: &self.config.resource_paths,
            loader,
            loader_license_confirmed: self.config.loader_license,
            local_mod_path: &self.config.mod_path,
            mods: &self.config.mods,
        };
        let output = Path::new(&self.output_dir);
        fs::create_dir_all(output).map_err(|error| {
            format!(
                "{}: {error}",
                self.language.text("build.options_write_failed")
            )
        })?;
        let bytes = serde_json::to_vec_pretty(&record).map_err(|error| {
            format!(
                "{}: {error}",
                self.language.text("build.options_write_failed")
            )
        })?;
        fs::write(output.join("build-options.json"), bytes).map_err(|error| {
            format!(
                "{}: {error}",
                self.language.text("build.options_write_failed")
            )
        })
    }

    fn diagnostics(&self) -> String {
        let logs = self.build_logs.iter().cloned().collect::<Vec<_>>().join("");
        let value = format!(
            "Gaius Builder\nPage: {}\nState: {:?}\nOutput: {}\nError: {}\nLogs:\n{}",
            self.page,
            self.build_state,
            self.output_dir,
            self.build_error.as_deref().unwrap_or("none"),
            logs
        );
        sanitize_user_paths(&value)
    }

    fn doctor_diagnostics(&self) -> String {
        let report = self.doctor_report.as_ref();
        let value = format!(
            "Gaius Builder\nPage: 2\nError: {}\nOutput:\n{}\n{}",
            self.doctor_error.as_deref().unwrap_or("none"),
            report
                .map(|item| item.raw_output.as_str())
                .unwrap_or_default(),
            report.map(|item| item.stderr.as_str()).unwrap_or_default(),
        );
        sanitize_user_paths(&value)
    }

    fn action_row_height(&self) -> f32 {
        let scale = self.config.scale.max(1.0);
        let base = match self.page {
            2 => 72.0,
            3 if scale >= 1.9 => 168.0,
            3 if scale >= 1.4 => 150.0,
            3 if scale >= 1.2 => 120.0,
            3 => 88.0,
            5 if self.build_state == BuildState::Cancelled
                && !self.cleanup_candidates.is_empty() =>
            {
                96.0
            }
            5 => 72.0,
            6 => 72.0,
            _ => 0.0,
        };
        base / scale
    }

    fn render_page_actions(&mut self, ui: &mut Ui, palette: Palette) {
        let compact = ui.available_width() < 360.0;
        if compact {
            ui.spacing_mut().item_spacing.x = 4.0;
            ui.spacing_mut().button_padding.x = 6.0;
        }
        ui.painter().line_segment(
            [ui.max_rect().left_top(), ui.max_rect().right_top()],
            Stroke::new(1.0, palette.stroke),
        );
        ui.add_space(5.0);
        if self.page == 3 {
            self.render_terms_actions(ui, palette);
            return;
        }
        ui.horizontal_wrapped(|ui| match self.page {
            2 => {
                if self.doctor_receiver.is_some() {
                    let cancel = ui.button(self.language.text("doctor.cancel"));
                    self.record_action(0, &cancel);
                    if cancel.clicked() {
                        self.doctor_cancelled = true;
                        if let Some(control) = &self.doctor_control {
                            control.cancel();
                        }
                    }
                } else {
                    let retry_label = self.language.text(if compact {
                        "doctor.retry.short"
                    } else {
                        "doctor.retry"
                    });
                    let retry = ui
                        .button(retry_label)
                        .on_hover_text(self.language.text("doctor.retry"));
                    self.record_action(0, &retry);
                    if retry.clicked() {
                        self.start_doctor();
                    }
                    let failed = self.doctor_error.is_some()
                        || self.doctor_cancelled
                        || self.doctor_report.as_ref().is_some_and(has_hash_mismatch);
                    if failed {
                        let copy = ui
                            .button(self.language.text(if compact {
                                "build.copy_diagnostics.short"
                            } else {
                                "build.copy_diagnostics"
                            }))
                            .on_hover_text(self.language.text("build.copy_diagnostics"));
                        self.record_action(1, &copy);
                        if copy.clicked() {
                            copy_diagnostics(&self.doctor_diagnostics());
                        }
                    }
                }
            }
            3 => {}
            5 => match self.build_state {
                BuildState::Idle => {
                    let start = ui.button(self.language.text("build.start"));
                    self.record_action(0, &start);
                    if start.clicked() {
                        self.start_build();
                    }
                }
                BuildState::Running => {
                    let cancel = ui.button(self.language.text("build.cancel"));
                    self.record_action(0, &cancel);
                    if cancel.clicked() {
                        self.cancel_confirm = true;
                    }
                }
                BuildState::Succeeded => {
                    let open = ui.button(self.language.text("build.open_log"));
                    self.record_action(0, &open);
                    if open.clicked() {
                        open_path(Path::new(&self.output_dir));
                    }
                }
                BuildState::Failed | BuildState::Cancelled => {
                    let copy = ui
                        .button(self.language.text(if compact {
                            "build.copy_diagnostics.short"
                        } else {
                            "build.copy_diagnostics"
                        }))
                        .on_hover_text(self.language.text("build.copy_diagnostics"));
                    self.record_action(0, &copy);
                    if copy.clicked() {
                        copy_diagnostics(&self.diagnostics());
                    }
                    let open = ui
                        .button(self.language.text(if compact {
                            "build.open_log.short"
                        } else {
                            "build.open_log"
                        }))
                        .on_hover_text(self.language.text("build.open_log"));
                    self.record_action(1, &open);
                    if open.clicked() {
                        open_path(Path::new(&self.output_dir));
                    }
                    if self.build_state == BuildState::Cancelled
                        && !self.cleanup_candidates.is_empty()
                    {
                        let cleanup = ui
                            .button(self.language.text(if compact {
                                "build.cleanup_partial.short"
                            } else {
                                "build.cleanup_partial"
                            }))
                            .on_hover_text(self.language.text("build.cleanup_partial"));
                        self.record_action(2, &cleanup);
                        if cleanup.clicked() {
                            self.cleanup_confirm = true;
                        }
                    }
                    let retry = ui
                        .button(self.language.text(if compact {
                            "build.retry.short"
                        } else {
                            "build.retry"
                        }))
                        .on_hover_text(self.language.text("build.retry"));
                    self.record_action(3, &retry);
                    if retry.clicked() {
                        self.start_build();
                    }
                }
            },
            6 => match self.build_state {
                BuildState::Succeeded => {
                    let open_browser_label = self.language.text(if compact {
                        "complete.open_browser.short"
                    } else {
                        "complete.open_browser"
                    });
                    let open_browser = ui.add(
                        Button::new(RichText::new(open_browser_label).color(palette.on_accent()))
                            .fill(palette.accent)
                            .stroke(Stroke::new(1.0, palette.accent)),
                    );
                    let open_browser =
                        open_browser.on_hover_text(self.language.text("complete.open_browser"));
                    self.record_action(0, &open_browser);
                    if open_browser.clicked()
                        && let Some(version) = self.selected_versions.first()
                    {
                        let html = Path::new(&self.output_dir)
                            .join(version)
                            .join(format!("Gaius-{version}.html"));
                        open_path(&html);
                    }
                    let open_folder = ui
                        .button(self.language.text(if compact {
                            "complete.open_folder.short"
                        } else {
                            "complete.open_folder"
                        }))
                        .on_hover_text(self.language.text("complete.open_folder"));
                    self.record_action(1, &open_folder);
                    if open_folder.clicked() {
                        open_path(Path::new(&self.output_dir));
                    }
                    let rebuild = ui
                        .button(self.language.text(if compact {
                            "complete.rebuild.short"
                        } else {
                            "complete.rebuild"
                        }))
                        .on_hover_text(self.language.text("complete.rebuild"));
                    self.record_action(2, &rebuild);
                    if rebuild.clicked() {
                        self.page = 5;
                        self.build_state = BuildState::Idle;
                    }
                }
                BuildState::Failed | BuildState::Cancelled => {
                    let copy = ui
                        .button(self.language.text(if compact {
                            "build.copy_diagnostics.short"
                        } else {
                            "build.copy_diagnostics"
                        }))
                        .on_hover_text(self.language.text("build.copy_diagnostics"));
                    self.record_action(0, &copy);
                    if copy.clicked() {
                        copy_diagnostics(&self.diagnostics());
                    }
                    let open = ui
                        .button(self.language.text(if compact {
                            "build.open_log.short"
                        } else {
                            "build.open_log"
                        }))
                        .on_hover_text(self.language.text("build.open_log"));
                    self.record_action(1, &open);
                    if open.clicked() {
                        open_path(Path::new(&self.output_dir));
                    }
                    let retry = ui
                        .button(self.language.text(if compact {
                            "build.retry.short"
                        } else {
                            "build.retry"
                        }))
                        .on_hover_text(self.language.text("build.retry"));
                    self.record_action(2, &retry);
                    if retry.clicked() {
                        self.page = 5;
                        self.start_build();
                    }
                }
                _ => {
                    let start = ui.button(self.language.text("build.start"));
                    self.record_action(0, &start);
                    if start.clicked() {
                        self.page = 5;
                    }
                }
            },
            _ => {}
        });
    }

    fn render_terms_actions(&mut self, ui: &mut Ui, palette: Palette) {
        ui.set_width(ui.available_width());
        let mut accepted = self.terms_checked;
        let terms_label = self.language.text("terms.accept").to_owned();
        let checkbox = ui.add_enabled_ui(self.terms_scrolled, |ui| {
            wrapped_checkbox(ui, &mut accepted, &terms_label)
        });
        let checkbox = checkbox.inner;
        self.layout_rects.terms_checkbox = Some(checkbox.response.rect);
        self.layout_rects.terms_checkbox_mark = Some(checkbox.mark);
        self.layout_rects.terms_checkbox_label = Some(checkbox.label);
        self.record_text_audit("terms.accept", checkbox.label, ui.clip_rect());
        self.record_action(0, &checkbox.response);
        if checkbox.response.changed() && accepted {
            self.terms_checked = self.persist_terms_acceptance();
        } else if checkbox.response.changed() {
            self.terms_checked = false;
        }

        ui.add_space(4.0);
        let hash_height = if self.config.scale >= 1.9 { 20.0 } else { 24.0 };
        let row_width = ui.available_width().max(0.0);
        ui.allocate_ui_with_layout(
            Vec2::new(row_width, hash_height),
            Layout::left_to_right(Align::Center),
            |ui| {
                let copy_width = 32.0;
                let hash_width =
                    (ui.available_width() - copy_width - ui.spacing().item_spacing.x).max(0.0);
                let hash_text = format!("SHA-256  {}", self.terms_hash);
                let display = fit_middle_ellipsis(
                    ui,
                    &hash_text,
                    hash_width,
                    egui::FontId::monospace(10.0),
                    palette.secondary,
                );
                let hash = add_left_cell(
                    ui,
                    Vec2::new(hash_width, hash_height),
                    egui::Label::new(
                        RichText::new(display)
                            .monospace()
                            .size(10.0)
                            .color(palette.secondary),
                    )
                    .halign(Align::Min)
                    .truncate(),
                );
                self.layout_rects.terms_hash_label = Some(hash.rect);
                self.record_text_audit("terms.hash", hash.rect, ui.clip_rect());
                hash.on_hover_text(self.terms_hash.as_str());
                let copy = if hash_height < 24.0 {
                    let (_, response) = ui.allocate_exact_size(
                        Vec2::new(copy_width, hash_height),
                        egui::Sense::click(),
                    );
                    response.on_hover_text(self.language.text("terms.hash.copy"))
                } else {
                    ui.add_sized([copy_width, hash_height], Button::new(""))
                        .on_hover_text(self.language.text("terms.hash.copy"))
                };
                self.layout_rects.terms_hash_copy = Some(copy.rect);
                copy.widget_info(|| {
                    egui::WidgetInfo::labeled(
                        egui::Role::Button,
                        true,
                        self.language.text("terms.hash.copy"),
                    )
                });
                icons::paint(
                    ui.painter(),
                    copy.rect.shrink(5.0),
                    Icon::Copy,
                    palette.text,
                );
                self.record_action(1, &copy);
                if copy.clicked() {
                    ui.ctx().copy_text(self.terms_hash.clone());
                }
            },
        );
    }

    fn render_bottom_bar(&mut self, ui: &mut Ui, palette: Palette) -> Rect {
        Frame::new()
            .fill(palette.panel)
            .stroke(Stroke::new(1.0, palette.stroke))
            .corner_radius(CornerRadius::same(8))
            .inner_margin(egui::Margin::symmetric(12, 5))
            .show(ui, |ui| {
                let available = ui.available_size();
                ui.set_width(available.x);
                ui.set_height(available.y);
                ui.with_layout(Layout::left_to_right(Align::Center), |ui| {
                    let (icon, label, color) = self.status(palette);
                    icons::show(ui, icon, color, 16.0);
                    ui.label(RichText::new(label).color(palette.secondary));
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        let next = ui.add_enabled(
                            self.can_advance(),
                            Button::new(self.language.text(if self.page == 6 {
                                "nav.finish"
                            } else {
                                "nav.next"
                            })),
                        );
                        self.layout_rects.nav_next = Some(next.rect);
                        if next.clicked() {
                            self.advance();
                        }
                        let back = ui.button(self.language.text("nav.back"));
                        self.layout_rects.nav_back = Some(back.rect);
                        if back.clicked() {
                            self.page = self.page.saturating_sub(1).max(1);
                        }
                    });
                });
            })
            .response
            .rect
    }

    fn can_advance(&self) -> bool {
        match self.page {
            1 => !self.selected_versions.is_empty(),
            3 => self.terms_checked,
            5 => self.build_state == BuildState::Succeeded,
            _ => true,
        }
    }

    fn advance(&mut self) {
        if !self.can_advance() {
            return;
        }
        if self.page == 6 {
            self.page = 1;
            return;
        }
        if self.page == 4 {
            self.config_confirmed = true;
        }
        self.page = (self.page + 1).min(6);
    }

    fn status(&self, palette: Palette) -> (Icon, &'static str, eframe::egui::Color32) {
        if self.page == 2 {
            if self.doctor_receiver.is_some() {
                return (
                    Icon::Ring,
                    self.language.text("status.checking"),
                    palette.info,
                );
            }
            if self.doctor_cancelled {
                return (
                    Icon::Square,
                    self.language.text("status.cancelled"),
                    palette.warning,
                );
            }
            if self.doctor_error.is_some() {
                return (
                    Icon::Cross,
                    self.language.text("status.check_failed"),
                    palette.error,
                );
            }
            if self.doctor_report.as_ref().is_some_and(has_hash_mismatch) {
                return (
                    Icon::Cross,
                    self.language.text("status.hash_error"),
                    palette.error,
                );
            }
        }
        match self.build_state {
            BuildState::Running => (
                Icon::Ring,
                self.language.text("status.building"),
                palette.info,
            ),
            BuildState::Failed => (
                Icon::Cross,
                self.language.text("status.build_failed"),
                palette.error,
            ),
            BuildState::Cancelled => (
                Icon::Square,
                self.language.text("status.cancelled"),
                palette.warning,
            ),
            BuildState::Succeeded => (
                Icon::Check,
                self.language.text("status.completed"),
                palette.accent,
            ),
            _ => (
                Icon::Bullet,
                self.language.text("status.ready"),
                palette.secondary,
            ),
        }
    }

    fn render_footer(&mut self, ui: &mut Ui, palette: Palette) -> Rect {
        ui.with_layout(Layout::top_down(Align::Center), |ui| {
            let scale = self.config.scale.max(1.0);
            let clip = ui.clip_rect();
            let response = ui.add_sized(
                [ui.available_width(), ui.available_height()],
                egui::Label::new(
                    RichText::new(self.language.text("footer.short_disclaimer"))
                        .size(11.5 / scale)
                        .color(palette.secondary),
                )
                .truncate(),
            );
            self.record_text_audit("footer.short_disclaimer", response.rect, clip);
            response.rect
        })
        .inner
    }

    fn render_dialogs(&mut self, context: &egui::Context, palette: Palette) {
        if self.cancel_confirm {
            egui::Window::new(self.language.text("build.cancel_title"))
                .collapsible(false)
                .resizable(false)
                .default_width(360.0)
                .show(context, |ui| {
                    ui.label(self.language.text("build.cancel_body"));
                    ui.horizontal(|ui| {
                        if ui
                            .button(self.language.text("build.cancel_confirm"))
                            .clicked()
                        {
                            if let Some(control) = &self.build_control {
                                control.cancel();
                            }
                            self.cancel_confirm = false;
                        }
                        if ui.button(self.language.text("build.cancel_keep")).clicked() {
                            self.cancel_confirm = false;
                        }
                    });
                });
        }
        if self.cleanup_confirm {
            egui::Window::new(self.language.text("build.cleanup_title"))
                .collapsible(false)
                .resizable(false)
                .default_width(380.0)
                .show(context, |ui| {
                    ui.label(self.language.text("build.cleanup_body"));
                    for path in &self.cleanup_candidates {
                        ui.label(path.display().to_string());
                    }
                    ui.horizontal(|ui| {
                        if ui
                            .button(self.language.text("build.cleanup_confirm"))
                            .clicked()
                        {
                            match cleanup_owned_outputs(
                                Path::new(&self.output_dir),
                                &self.cleanup_candidates,
                                &self.selected_versions,
                            ) {
                                Ok(()) => self.cleanup_candidates.clear(),
                                Err(error) => self.build_error = Some(error),
                            }
                            self.cleanup_confirm = false;
                        }
                        if ui.button(self.language.text("build.cancel_keep")).clicked() {
                            self.cleanup_confirm = false;
                        }
                    });
                });
        }
        if let Some(index) = self.mod_settings_open {
            let title = self.language.text("config.mod_settings").to_owned();
            let license = self.language.text("config.license").to_owned();
            let close = self.language.text("nav.close").to_owned();
            let mut open = true;
            let mut close_clicked = false;
            egui::Window::new(title)
                .collapsible(false)
                .resizable(false)
                .open(&mut open)
                .default_width(380.0)
                .show(context, |ui| {
                    if let Some(row) = self.config.mods.get_mut(index) {
                        ui.label(row.path.as_str());
                        ui.checkbox(&mut row.licensed, license);
                    }
                    if ui.button(close).clicked() {
                        close_clicked = true;
                    }
                });
            if !open || close_clicked || self.config.mods.get(index).is_none() {
                self.mod_settings_open = None;
            }
        }
        if self.about_open {
            egui::Window::new(self.language.text("app.about_title"))
                .collapsible(false)
                .resizable(false)
                .default_width(360.0)
                .show(context, |ui| {
                    ui.label(self.language.text("app.about_body"));
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new(self.language.text("footer.disclaimer"))
                            .size(12.0)
                            .color(palette.secondary),
                    );
                    if ui.button(self.language.text("app.about")).clicked() {
                        self.about_open = false;
                    }
                });
        }
    }

    fn mock_completed(&mut self, status: ReproStatus) {
        self.build_state = BuildState::Succeeded;
        self.build_generation = self.build_generation.saturating_add(1);
        self.config_confirmed = true;
        self.output_dir = "./gaius-output".to_owned();
        self.output_bytes = 191_365_120;
        self.build_elapsed = Some(Duration::from_secs(418));
        self.repro_status = status;
        let mismatches = if status == ReproStatus::Diverged {
            vec!["index.html".to_owned()]
        } else {
            Vec::new()
        };
        self.hash_mismatches = mismatches.clone();
        self.build_results = vec![VersionBuildResult {
            version: "26.3".to_owned(),
            output_dir: PathBuf::from("./gaius-output/26.3"),
            exit_code: 0,
            error: None,
            hashes: HashComparison {
                state: match status {
                    ReproStatus::Identical | ReproStatus::Custom => HashState::Match,
                    ReproStatus::Diverged => HashState::Mismatch,
                    ReproStatus::Unverified => HashState::Unknown,
                },
                mismatches,
            },
        }];
        self.terms_checked = true;
    }

    fn mock_failed(&mut self, phase: &str, reason: &str) {
        self.build_state = BuildState::Failed;
        self.build_error = Some(format!("{phase}: {reason}"));
    }
}

impl eframe::App for BuilderApp {
    fn ui(&mut self, ui: &mut Ui, _frame: &mut eframe::Frame) {
        let context = ui.ctx().clone();
        self.poll_doctor();
        self.poll_build();
        self.handle_keys(&context);
        let system_theme = context.system_theme();
        let effective = (self.theme_mode, system_theme);
        if self.theme_applied != Some(effective) {
            theme::apply(&context, self.theme_mode);
            self.theme_applied = Some(effective);
        }
        context.set_zoom_factor(self.config.scale);
        context.style_mut_of(self.theme_mode.effective(system_theme), |style| {
            style.animation_time = if self.config.reduce_motion { 0.0 } else { 0.15 };
            style.spacing.scroll = egui::style::ScrollStyle::solid();
            style.spacing.scroll.bar_width = 8.0;
            style.spacing.scroll.bar_inner_margin = 2.0;
            style.spacing.scroll.foreground_color = true;
        });
        let palette = self.theme_mode.palette(system_theme);
        let viewport = context.content_rect();
        ui.painter()
            .rect_filled(viewport, CornerRadius::ZERO, palette.background);

        let scale = self.config.scale.max(1.0);
        let bounds = viewport.shrink(12.0 / scale);
        ui.set_clip_rect(viewport);
        let (top_rect, top_content_rect) =
            scope_at_rect(ui, bounds, |ui| self.render_top_bar(ui, palette)).inner;
        #[cfg(not(test))]
        let _ = top_content_rect;
        let rail_fraction = if self.config.scale >= 1.5 { 0.38 } else { 0.30 };
        let rail_width = 205.0f32.min(bounds.width() * rail_fraction);
        let column_gap = 12.0 / scale;
        let bottom_height = 24.0 + 36.0 / scale;
        let footer_height = if scale >= 1.9 {
            28.0 / scale
        } else {
            34.0 / scale
        };
        let section_gap = 8.0 / scale;
        let footer_gap = if scale >= 1.9 {
            2.0 / scale
        } else {
            6.0 / scale
        };

        let footer_rect = Rect::from_min_size(
            egui::pos2(bounds.left(), bounds.bottom() - footer_height),
            Vec2::new(bounds.width(), footer_height),
        );
        let bottom_rect = Rect::from_min_size(
            egui::pos2(
                bounds.left(),
                footer_rect.top() - footer_gap - bottom_height,
            ),
            Vec2::new(bounds.width(), bottom_height),
        );
        let main_top = top_rect.bottom() + section_gap;
        let main_bottom = bottom_rect.top() - section_gap;
        let main_height = (main_bottom - main_top).max(0.0);
        if self.scroll_page != self.page {
            self.scroll_page = self.page;
            self.page_scroll_offset = 0.0;
        }
        let requested_action_height = self.action_row_height();
        let action_height = requested_action_height.min(main_height);
        let action_gap = if action_height > 0.0 {
            section_gap.min((main_height - action_height).max(0.0))
        } else {
            0.0
        };
        let content_bottom = (main_bottom - action_height - action_gap).max(main_top);
        let rail_rect = Rect::from_min_size(
            egui::pos2(bounds.left(), main_top),
            Vec2::new(rail_width, main_height),
        );
        let content_rect = Rect::from_min_max(
            egui::pos2(rail_rect.right() + column_gap, main_top),
            egui::pos2(bounds.right(), content_bottom),
        );
        let action_rect = if action_height > 0.0 {
            Rect::from_min_size(
                egui::pos2(
                    bounds.left() + rail_width + column_gap,
                    content_bottom + action_gap,
                ),
                Vec2::new(
                    bounds.right() - rail_width - column_gap - bounds.left(),
                    action_height,
                ),
            )
        } else {
            Rect::NOTHING
        };
        self.layout_rects = LayoutRects {
            viewport,
            #[cfg(test)]
            top_bar: top_rect,
            #[cfg(test)]
            top_bar_content: top_content_rect,
            rail: rail_rect,
            content: content_rect,
            content_used: Rect::NOTHING,
            content_scroll_viewport: None,
            content_scroll_present: false,
            content_scroll_needed: false,
            config_panel: None,
            config_scroll_viewport: None,
            config_add_mod: None,
            config_scroll_needed: false,
            mod_first_row: None,
            terms_checkbox: None,
            terms_checkbox_mark: None,
            terms_checkbox_label: None,
            terms_hash_label: None,
            terms_hash_copy: None,
            #[cfg(test)]
            text_audits: [None; 32],
            completion_labels: [None; 4],
            completion_values: [None; 4],
            completion_copy: None,
            completion_artifacts: None,
            action_row: action_rect,
            action_widgets: [None; 5],
            action_widget_count: 0,
            nav_next: None,
            nav_back: None,
            bottom_bar: bottom_rect,
            footer: footer_rect,
        };
        ui.set_clip_rect(self.layout_rects.viewport);
        scope_at_rect(ui, self.layout_rects.rail, |ui| {
            self.render_steps(ui, palette)
        });
        self.content_has_focus = false;
        scope_at_rect(ui, content_rect, |ui| {
            let scroll = ScrollArea::vertical()
                .id_salt(("wizard-page-scroll", self.page))
                .max_width(ui.available_width())
                .auto_shrink([false, false])
                .vertical_scroll_offset(self.page_scroll_offset)
                .scroll_bar_visibility(
                    egui::containers::scroll_area::ScrollBarVisibility::AlwaysVisible,
                )
                .show_viewport(ui, |ui, viewport| {
                    let content_width = (viewport.width() - 12.0).max(0.0);
                    ui.set_width(content_width);
                    ui.set_max_width(content_width);
                    self.render_content(ui, palette);
                    ui.min_rect()
                });
            self.layout_rects.content_used = scroll.inner;
            self.layout_rects.content_scroll_viewport = Some(scroll.inner_rect);
            self.layout_rects.content_scroll_present = true;
            self.layout_rects.content_scroll_needed =
                scroll.content_size.y > scroll.inner_rect.height() + 0.5;
            self.page_scroll_offset = scroll.state.unclamped_offset().y.max(0.0);
        });
        self.layout_rects.content = content_rect;
        if action_height > 0.0 {
            scope_at_rect(ui, action_rect, |ui| {
                self.render_page_actions(ui, palette);
            });
            self.layout_rects.action_row = action_rect;
        }
        scope_at_rect(ui, self.layout_rects.bottom_bar, |ui| {
            self.render_bottom_bar(ui, palette)
        });
        scope_at_rect(ui, self.layout_rects.footer, |ui| {
            self.render_footer(ui, palette)
        });
        self.render_dialogs(&context, palette);
        if self.doctor_receiver.is_some() || self.build_task.is_some() {
            context.request_repaint_after(Duration::from_millis(100));
        }
    }
}

fn scope_at_rect<R>(
    ui: &mut Ui,
    rect: Rect,
    add_contents: impl FnOnce(&mut Ui) -> R,
) -> egui::InnerResponse<R> {
    ui.scope_builder(
        UiBuilder::new()
            .max_rect(rect)
            .layout(Layout::top_down(Align::Min)),
        |ui| {
            ui.set_clip_rect(ui.clip_rect().intersect(rect));
            add_contents(ui)
        },
    )
}

fn keep_focused_visible(focus: &mut bool, response: egui::Response) -> egui::Response {
    if response.has_focus() {
        *focus = true;
    }
    if response.gained_focus() {
        response.scroll_to_me(Some(Align::Center));
    }
    response
}

struct WrappedCheckbox {
    response: egui::Response,
    mark: Rect,
    label: Rect,
}

fn wrapped_checkbox(ui: &mut Ui, checked: &mut bool, text: &str) -> WrappedCheckbox {
    let line_height = ui.text_style_height(&TextStyle::Body) * 1.05;
    let mark_size = ui.spacing().interact_size.y.clamp(16.0, 20.0);
    let (mark, label) = ui
        .horizontal_top(|ui| {
            let mark = ui
                .allocate_exact_size(Vec2::splat(mark_size), egui::Sense::hover())
                .0;
            let label =
                ui.add(egui::Label::new(RichText::new(text).line_height(Some(line_height))).wrap());
            (mark, label)
        })
        .inner;
    let label_rect = label.rect;
    let mut response = ui.interact(
        mark.union(label_rect),
        ui.next_auto_id(),
        egui::Sense::click(),
    );
    if response.clicked() {
        *checked = !*checked;
        response.mark_changed();
    }
    let visuals = ui.style().interact(&response);
    ui.painter().rect(
        mark,
        CornerRadius::same(2),
        if *checked {
            ui.visuals().selection.bg_fill
        } else {
            visuals.bg_fill
        },
        visuals.fg_stroke,
        egui::StrokeKind::Inside,
    );
    if *checked {
        let inset = mark.shrink(mark_size * 0.25);
        ui.painter().add(egui::Shape::line(
            vec![
                egui::pos2(inset.left(), inset.center().y),
                egui::pos2(inset.center().x, inset.bottom()),
                egui::pos2(inset.right(), inset.top()),
            ],
            visuals.fg_stroke,
        ));
    }
    response.widget_info(|| {
        egui::WidgetInfo::selected(
            egui::Role::CheckBox,
            ui.is_enabled(),
            *checked,
            text.to_owned(),
        )
    });
    WrappedCheckbox {
        response,
        mark,
        label: label_rect,
    }
}

fn fit_middle_ellipsis(
    ui: &mut Ui,
    text: &str,
    max_width: f32,
    font: egui::FontId,
    color: egui::Color32,
) -> String {
    let measure = |candidate: &str| {
        ui.fonts_mut(|fonts| {
            fonts
                .layout_no_wrap(candidate.to_owned(), font.clone(), color)
                .size()
                .x
        })
    };
    if measure(text) <= max_width {
        return text.to_owned();
    }

    let chars = text.chars().collect::<Vec<_>>();
    for retained in (0..chars.len()).rev() {
        let prefix = retained.div_ceil(2);
        let suffix = retained / 2;
        let mut candidate = chars.iter().take(prefix).collect::<String>();
        candidate.push('…');
        candidate.extend(chars.iter().skip(chars.len() - suffix));
        if measure(&candidate) <= max_width {
            return candidate;
        }
    }
    String::new()
}

pub fn render_screenshot(
    path: &Path,
    page: usize,
    theme_mode: ScreenTheme,
    language: Language,
    size: [u32; 2],
    scale: f32,
    state: &str,
) -> Result<(), String> {
    use egui_kittest::kittest::{NodeT, Queryable};

    let scenario = ScreenshotState::parse(state)?;
    if scenario == ScreenshotState::TermsAccepted && page != 3 {
        return Err("terms-accepted screenshots must use page 3".to_owned());
    }
    if scenario == ScreenshotState::ModsScrolled && page != 4 {
        return Err("mods-scrolled screenshots must use page 4".to_owned());
    }
    if scenario == ScreenshotState::ToolchainField && page != 2 {
        return Err("toolchain-field screenshots must use page 2".to_owned());
    }
    if scenario == ScreenshotState::PreflightError && page != 5 {
        return Err("real-preflight-error screenshots must use page 5".to_owned());
    }
    if scenario == ScreenshotState::BuildSuccess && page != 6 {
        return Err("build-success screenshots must use page 6".to_owned());
    }
    let preflight_data = if scenario == ScreenshotState::PreflightError {
        Some(run_real_toolchain_preflight()?)
    } else {
        None
    };
    let mut harness = egui_kittest::Harness::builder()
        .with_size(Vec2::new(size[0] as f32, size[1] as f32))
        .with_pixels_per_point(1.0)
        .with_theme(match theme_mode {
            ScreenTheme::Dark => Theme::Dark,
            ScreenTheme::Light => Theme::Light,
        })
        .with_context_setup(move |context| context.set_zoom_factor(scale))
        .allow_missing_glyphs()
        .with_render_cursor(false)
        .build_eframe(move |context| {
            let mut app = BuilderApp::with_state(
                &context.egui_ctx,
                language,
                theme_mode.mode(),
                page,
                false,
                scenario,
            );
            app.config.scale = scale;
            if let Some((progress, logs, error)) = &preflight_data {
                app.build_progress = progress.clone();
                app.build_logs = logs.iter().cloned().collect();
                app.build_error = Some(error.clone());
            }
            app
        });
    harness.set_size(Vec2::new(size[0] as f32 / scale, size[1] as f32 / scale));
    harness.run();
    if scenario == ScreenshotState::TermsAccepted {
        let first_paragraph = terms_text(language)
            .split("\n\n")
            .next()
            .unwrap_or_default();
        for _ in 0..32 {
            if harness.state().terms_scrolled {
                break;
            }
            harness.get_by_label(first_paragraph).scroll_down();
            harness.run();
        }
        if !harness.state().terms_scrolled {
            return Err("terms text did not reach its scroll end".to_owned());
        }
        harness
            .get_by_role_and_label(
                egui::accesskit::Role::CheckBox,
                language.text("terms.accept"),
            )
            .click();
        harness.run();
        if !harness.state().terms_checked || !harness.state().can_advance() {
            return Err("terms acceptance did not enable Next".to_owned());
        }
    }
    if scenario == ScreenshotState::ModsScrolled {
        let settings = harness
            .root()
            .children_recursive()
            .find(|node| {
                node.accesskit_node().role() == egui::accesskit::Role::Button
                    && node.accesskit_node().label().as_deref()
                        == Some(language.text("config.table.settings"))
            })
            .ok_or_else(|| "Mods tab has no settings button to scroll into view".to_owned())?;
        settings.scroll_to_me();
        harness.run();
        let layout = harness.state().layout_rects;
        if !layout
            .config_scroll_viewport
            .zip(layout.mod_first_row)
            .is_some_and(|(viewport, row)| rect_contains_rect(viewport, row))
        {
            return Err("first mod row is not fully visible in the scrolled panel".to_owned());
        }
    }
    if scenario == ScreenshotState::ToolchainField {
        harness
            .get_by_label(language.text("doctor.toolchain"))
            .scroll_to_me();
        harness.run();
    }
    let image = harness.render()?;
    image
        .save(path)
        .map_err(|error| format!("could not save screenshot {}: {error}", path.display()))
}

fn run_real_toolchain_preflight() -> Result<(Vec<BuildProgress>, Vec<String>, String), String> {
    let unique = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    let output = std::env::temp_dir()
        .join(format!(
            "gaius-builder-preflight-{}-{unique}",
            std::process::id()
        ))
        .join("26.3");
    let progress = Arc::new(std::sync::Mutex::new(Vec::new()));
    let logs = Arc::new(std::sync::Mutex::new(Vec::new()));
    let progress_sink = Arc::clone(&progress);
    let logs_sink = Arc::clone(&logs);
    let settings = BuildSettings::default();
    let result = PatcherClient
        .run_build(
            BuildRequest {
                version: "26.3",
                output: &output,
                mappings: None,
                shader_toolchain: None,
                minecraft_dir: None,
                settings: &settings,
            },
            ProcessControl::default(),
            move |mut event| {
                event.version = Some("26.3".to_owned());
                if let Ok(mut events) = progress_sink.lock() {
                    events.push(event);
                }
            },
            move |line| {
                if let Ok(mut lines) = logs_sink.lock() {
                    lines.push(line);
                }
            },
        )
        .map_err(|error| error.to_string())?;
    if result.exit_code == 0 || output.exists() {
        return Err("real Java patcher did not stop at the missing-toolchain preflight".to_owned());
    }
    let progress = progress
        .lock()
        .map_err(|_| "could not read preflight progress".to_owned())?
        .clone();
    let logs = logs
        .lock()
        .map_err(|_| "could not read preflight log".to_owned())?
        .clone();
    let Some(failure) = progress.iter().rev().find(|event| event.phase == "error") else {
        return Err("real Java patcher did not emit a JSON preflight error".to_owned());
    };
    if !failure
        .error
        .as_deref()
        .unwrap_or(&failure.message)
        .contains("prebuilt shader toolchain")
    {
        return Err(format!(
            "unexpected Java preflight error: {}",
            failure.message
        ));
    }
    let error = localized_build_failure(Language::Chinese, failure);
    Ok((progress, logs, error))
}

impl ScreenshotState {
    fn parse(value: &str) -> Result<Self, String> {
        Ok(match value {
            "normal" | "page" => Self::Normal,
            "terms-accepted" => Self::TermsAccepted,
            "detect-fail" => Self::DetectFail,
            "hash-error" => Self::HashError,
            "build-fail" => Self::BuildFail,
            "build-success" => Self::BuildSuccess,
            "cancelled" => Self::Cancelled,
            "running" => Self::Running,
            "unsupported-mod" => Self::UnsupportedMod,
            "mods-scrolled" => Self::ModsScrolled,
            "badge-green" => Self::BadgeGreen,
            "badge-yellow" => Self::BadgeYellow,
            "badge-red" => Self::BadgeRed,
            "toolchain-field" => Self::ToolchainField,
            "real-preflight-error" => Self::PreflightError,
            _ => return Err(format!("unknown screenshot state: {value}")),
        })
    }
}

fn rect_contains_rect(container: Rect, content: Rect) -> bool {
    content.min.x >= container.min.x - 0.5
        && content.min.y >= container.min.y - 0.5
        && content.max.x <= container.max.x + 0.5
        && content.max.y <= container.max.y + 0.5
}

#[cfg(test)]
fn rects_overlap(first: Rect, second: Rect) -> bool {
    first.left() < second.right()
        && first.right() > second.left()
        && first.top() < second.bottom()
        && first.bottom() > second.top()
}

#[cfg(test)]
fn rects_approximately_equal(first: Rect, second: Rect) -> bool {
    let epsilon = 1.0;
    (first.min.x - second.min.x).abs() <= epsilon
        && (first.min.y - second.min.y).abs() <= epsilon
        && (first.max.x - second.max.x).abs() <= epsilon
        && (first.max.y - second.max.y).abs() <= epsilon
}

fn add_left_cell(ui: &mut Ui, size: Vec2, widget: impl egui::Widget) -> egui::Response {
    ui.allocate_ui_with_layout(size, Layout::left_to_right(Align::Center), |ui| {
        ui.spacing_mut().item_spacing.x = 0.0;
        let response = ui.add(widget);
        ui.allocate_space(Vec2::new((size.x - response.rect.width()).max(0.0), 0.0));
        response
    })
    .inner
}

fn add_completion_row(
    ui: &mut Ui,
    row_width: f32,
    label_width: f32,
    value_width: f32,
    height: f32,
    label: &str,
    value: impl egui::Widget,
) -> (egui::Response, egui::Response) {
    ui.allocate_ui_with_layout(
        Vec2::new(row_width, height),
        Layout::left_to_right(Align::Center),
        |ui| {
            let label = add_left_cell(
                ui,
                Vec2::new(label_width, height),
                egui::Label::new(label).halign(Align::Min),
            );
            let value = add_left_cell(ui, Vec2::new(value_width, height), value);
            (label, value)
        },
    )
    .inner
}

fn has_hash_mismatch(report: &DoctorReport) -> bool {
    report.versions.iter().any(|version| {
        version.installed
            && !version.verified
            && version.reason.as_deref().is_some_and(|reason| {
                let reason = reason.to_ascii_lowercase();
                reason.contains("hash") || reason.contains("sha-1") || reason.contains("sha1")
            })
    })
}

fn display_build_phase_key(phase: &str) -> &'static str {
    match phase {
        "fetch" => "build.stage.verify",
        "stage" | "prepare" => "build.stage.patch",
        "compile" => "build.stage.compile",
        "publish" => "build.stage.package",
        "compare" => "build.stage.compare",
        "error" => "build.phase.error",
        _ => "build.stage.unknown",
    }
}

fn draw_progress_ring(ui: &mut Ui, progress: f32, stage: &str, palette: Palette) {
    let (rect, _) = ui.allocate_exact_size(Vec2::new(150.0, 140.0), egui::Sense::hover());
    let center = egui::pos2(rect.center().x, rect.top() + 65.0);
    let radius = 55.0;
    ui.painter()
        .circle_stroke(center, radius, Stroke::new(8.0, palette.stroke));
    let fraction = progress.clamp(0.0, 1.0);
    let end = -std::f32::consts::FRAC_PI_2 + fraction * std::f32::consts::TAU;
    let segments = ((fraction * 64.0).ceil() as usize).max(2);
    let points = (0..=segments)
        .map(|index| {
            let angle = -std::f32::consts::FRAC_PI_2
                + (end + std::f32::consts::FRAC_PI_2) * index as f32 / segments as f32;
            center + Vec2::new(angle.cos() * radius, angle.sin() * radius)
        })
        .collect::<Vec<_>>();
    ui.painter()
        .add(egui::Shape::line(points, Stroke::new(8.0, palette.accent)));
    ui.painter().text(
        egui::pos2(center.x, center.y - 7.0),
        egui::Align2::CENTER_CENTER,
        format!("{:.0}%", fraction * 100.0),
        egui::FontId::proportional(22.0),
        palette.text,
    );
    ui.painter().text(
        egui::pos2(center.x, center.y + 19.0),
        egui::Align2::CENTER_CENTER,
        stage,
        egui::FontId::proportional(12.0),
        palette.secondary,
    );
}

fn draw_success_check(ui: &mut Ui, progress: f32, palette: Palette, size: f32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(size), egui::Sense::hover());
    let amount = progress.clamp(0.0, 1.0);
    let center = rect.center();
    let radius = size * 0.38 * amount;
    if amount > 0.0 {
        ui.painter()
            .circle_stroke(center, radius, Stroke::new(2.0, palette.accent));
        icons::paint(
            ui.painter(),
            Rect::from_center_size(center, Vec2::splat(size * 0.52 * amount)),
            Icon::Check,
            palette.accent,
        );
    }
}

fn render_check(
    ui: &mut Ui,
    label: &str,
    value: &str,
    state: CheckState,
    palette: Palette,
    language: Language,
) {
    let (icon, text, color) = match state {
        CheckState::Ready => (Icon::Check, language.text("item.ok"), palette.accent),
        CheckState::Warning => (
            Icon::Warning,
            language.text("item.warning"),
            palette.warning,
        ),
        CheckState::Missing => (Icon::Cross, language.text("item.error"), palette.error),
    };
    Frame::new()
        .fill(palette.panel)
        .corner_radius(CornerRadius::same(8))
        .inner_margin(egui::Margin::symmetric(8, 1))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                icons::show(ui, icon, color, 16.0);
                ui.vertical(|ui| {
                    ui.spacing_mut().item_spacing.y = 0.0;
                    ui.label(RichText::new(format!("{label} · {text}")).strong());
                    ui.label(RichText::new(value).size(12.0).color(palette.secondary));
                });
            });
        });
    ui.add_space(0.0);
}

fn version_detected(report: &DoctorReport, version_id: &str) -> bool {
    report
        .versions
        .iter()
        .find(|version| version.id == version_id)
        .is_some_and(|version| version.installed)
}

fn failed_hash_report() -> DoctorReport {
    let mut report = match MockBackend.doctor() {
        Ok(report) => report,
        Err(_) => unreachable!(),
    };
    for version in &mut report.versions {
        version.installed = true;
        version.verified = false;
        version.state = CheckState::Warning;
        version.reason = Some("client SHA-1 did not match the pinned profile".to_owned());
    }
    report.raw_output = "Synthetic acceptance state: installed file hash mismatch".to_owned();
    report
}

fn terms_text(language: Language) -> &'static str {
    match language {
        Language::Chinese => {
            "使用本工具前，请确认以下事项。\n\n1. 你拥有 Minecraft: Java Edition 的正版副本，并由自己的官方启动器安装游戏文件。\n\n2. 你已阅读并同意 Minecraft 最终用户许可协议（EULA）。\n\n3. 构建产物仅供你个人使用。\n\n4. 你不会分享、分发或托管构建产物。\n\n5. 你确认本工具不是 Minecraft 官方产品，未经 Mojang 或 Microsoft 认可。\n\n接受记录仅保存在本机工作目录，包含本页条款文本的 SHA-256 和接受时间。"
        }
        Language::English => {
            "Review these conditions before using the builder.\n\n1. You own Minecraft: Java Edition and installed the game files with your own official launcher.\n\n2. You have read and accepted the Minecraft End User License Agreement (EULA).\n\n3. Build output is for your personal use only.\n\n4. You will not share, distribute, or host build output.\n\n5. You understand this is not an official Minecraft product and is not approved by Mojang or Microsoft.\n\nAcceptance is stored in the local work directory with the SHA-256 of this text and the acceptance time."
        }
    }
}

fn work_dir() -> PathBuf {
    if let Some(path) = std::env::var_os("GAIUS_BUILDER_WORK_DIR") {
        return PathBuf::from(path);
    }
    #[cfg(target_os = "windows")]
    if let Some(path) = std::env::var_os("LOCALAPPDATA") {
        return PathBuf::from(path).join("Gaius Builder").join("work");
    }
    #[cfg(target_os = "macos")]
    if let Some(path) = std::env::var_os("HOME") {
        return PathBuf::from(path).join("Library/Application Support/Gaius Builder/work");
    }
    if let Some(path) = std::env::var_os("HOME") {
        return PathBuf::from(path).join(".local/share/gaius-builder/work");
    }
    std::env::current_dir()
        .unwrap_or_default()
        .join(".gaius-builder-work")
}

fn default_output_dir() -> String {
    work_dir().join("builds").to_string_lossy().into_owned()
}

fn validate_shader_toolchain(path: &Path) -> Result<(), String> {
    let manifest_path = path.join("gaius-shader-toolchain.json");
    let manifest: serde_json::Value = serde_json::from_slice(
        &fs::read(&manifest_path)
            .map_err(|error| format!("{}: {error}", manifest_path.display()))?,
    )
    .map_err(|error| format!("invalid shader toolchain manifest: {error}"))?;
    if manifest.get("schema").and_then(serde_json::Value::as_u64) != Some(1) {
        return Err("unsupported shader toolchain manifest schema".to_owned());
    }
    let sources = manifest
        .get("sources")
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| "shader toolchain manifest has no source pins".to_owned())?;
    let source_names = [
        "shaderc",
        "glslang",
        "spirv-tools",
        "spirv-headers",
        "spirv-cross",
    ];
    if source_names.iter().any(|name| {
        sources
            .get(*name)
            .and_then(serde_json::Value::as_str)
            .is_none_or(str::is_empty)
    }) {
        return Err("shader toolchain manifest has incomplete source pins".to_owned());
    }
    let emscripten = manifest
        .get("emscripten")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| "shader toolchain manifest has no emscripten pin".to_owned())?;
    let files = manifest
        .get("files")
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| "shader toolchain manifest has no file hashes".to_owned())?;
    let required = [
        "gaius-shader-toolchain.js",
        "gaius-shaderc.js",
        "gaius-shaderc.wasm",
        "gaius-spvc.js",
        "gaius-spvc.wasm",
    ];
    for name in required {
        let expected = files
            .get(name)
            .and_then(|entry| entry.get("sha256"))
            .and_then(serde_json::Value::as_str)
            .filter(|value| value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit()))
            .ok_or_else(|| format!("shader toolchain manifest has no valid hash for {name}"))?;
        let actual = sha256_file(&path.join(name))?;
        if !actual.eq_ignore_ascii_case(expected) {
            return Err(format!("shader toolchain file hash does not match: {name}"));
        }
    }
    verify_toolchain_pins(sources, emscripten)?;
    Ok(())
}

fn verify_toolchain_pins(
    sources: &serde_json::Map<String, serde_json::Value>,
    emscripten: &str,
) -> Result<(), String> {
    const PINS: &str = include_str!("../../../port/wasm/shader-toolchain/pins.env");
    let pin_names = [
        ("shaderc", "GAIUS_ST_SHADERC_COMMIT"),
        ("glslang", "GAIUS_ST_GLSLANG_COMMIT"),
        ("spirv-tools", "GAIUS_ST_SPIRV_TOOLS_COMMIT"),
        ("spirv-headers", "GAIUS_ST_SPIRV_HEADERS_COMMIT"),
        ("spirv-cross", "GAIUS_ST_SPIRV_CROSS_COMMIT"),
    ];
    let values = PINS
        .lines()
        .filter_map(|line| line.split_once('='))
        .collect::<std::collections::HashMap<_, _>>();
    for (source, key) in pin_names {
        if sources.get(source).and_then(serde_json::Value::as_str) != values.get(key).copied() {
            return Err(format!("shader toolchain source pin does not match {key}"));
        }
    }
    let emscripten_version = values
        .get("GAIUS_ST_EMSCRIPTEN_VERSION")
        .copied()
        .ok_or_else(|| "local shader toolchain pins have no emscripten version".to_owned())?;
    let emscripten_commit = values
        .get("GAIUS_ST_EMSCRIPTEN_COMMIT")
        .copied()
        .ok_or_else(|| "local shader toolchain pins have no emscripten commit".to_owned())?;
    let expected_emscripten = format!("{} ({})", emscripten_version, emscripten_commit);
    if emscripten != expected_emscripten {
        return Err("shader toolchain emscripten pin does not match the local pins".to_owned());
    }
    Ok(())
}

fn sha256_file(path: &Path) -> Result<String, String> {
    let mut file = fs::File::open(path).map_err(|error| format!("{}: {error}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer).map_err(|error| error.to_string())?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn cleanup_owned_outputs(
    root: &Path,
    candidates: &[PathBuf],
    versions: &[String],
) -> Result<(), String> {
    let root = root
        .canonicalize()
        .map_err(|error| format!("could not resolve output directory: {error}"))?;
    for candidate in candidates {
        if !candidate.exists() {
            continue;
        }
        let expected_parent = candidate.parent().and_then(|path| path.canonicalize().ok());
        let name = candidate.file_name().and_then(|name| name.to_str());
        let canonical = candidate
            .canonicalize()
            .map_err(|error| format!("could not resolve partial output: {error}"))?;
        let metadata = fs::symlink_metadata(candidate)
            .map_err(|error| format!("could not inspect partial output: {error}"))?;
        if expected_parent.as_deref() != Some(root.as_path())
            || !name.is_some_and(|name| versions.iter().any(|version| version == name))
            || !canonical.starts_with(&root)
            || canonical == root
            || metadata.file_type().is_symlink()
        {
            return Err(format!(
                "refusing to remove output outside owned version directories: {}",
                candidate.display()
            ));
        }
        fs::remove_dir_all(candidate)
            .map_err(|error| format!("could not remove {}: {error}", candidate.display()))?;
    }
    Ok(())
}

fn retain_partial_outputs(candidates: &mut Vec<PathBuf>, results: &[VersionBuildResult]) {
    candidates.retain(|candidate| {
        !results
            .iter()
            .any(|result| result.exit_code == 0 && result.output_dir == *candidate)
    });
}

fn directory_size(path: &Path) -> u64 {
    let Ok(entries) = fs::read_dir(path) else {
        return 0;
    };
    entries
        .filter_map(Result::ok)
        .map(|entry| {
            let path = entry.path();
            if path.is_dir() {
                directory_size(&path)
            } else {
                entry.metadata().map(|meta| meta.len()).unwrap_or(0)
            }
        })
        .sum()
}

fn last_nonempty(stderr: &str, stdout: &str) -> String {
    stderr
        .lines()
        .rev()
        .find(|line| !line.trim().is_empty())
        .or_else(|| stdout.lines().rev().find(|line| !line.trim().is_empty()))
        .unwrap_or("The builder stopped without an error message.")
        .to_owned()
}

fn display_build_phase(language: Language, phase: &str) -> String {
    if phase == "unknown" {
        return language.text("build.stage.unknown").to_owned();
    }
    language.text(display_build_phase_key(phase)).to_owned()
}

fn display_doctor_value(language: Language, value: &str) -> String {
    if value == "unknown" {
        return language.text("doctor.value.unknown").to_owned();
    }
    if let Some(path) = value.strip_suffix(" (free space unknown)") {
        return format!("{path} ({})", language.text("doctor.space_unknown"));
    }
    value.replace(
        " GB free)",
        &format!(" GB {})", language.text("doctor.space_free")),
    )
}

fn display_doctor_reason(language: Language, version_id: &str, reason: &str) -> String {
    if reason == "client SHA-1 did not match the pinned profile" {
        return language.text("doctor.mock.hash_mismatch").to_owned();
    }
    if reason == format!("start {version_id} once in the official launcher") {
        return language
            .text("doctor.mock.start_version")
            .replace("{version}", version_id);
    }
    reason.to_owned()
}

fn display_build_message(language: Language, message: &str) -> String {
    let key = match message {
        "Validating local files" => Some("build.mock.validating"),
        "Applying source patches" => Some("build.mock.patching"),
        "Compiling client" => Some("build.mock.compiling"),
        "Writing output and hashes" => Some("build.mock.publishing"),
        "Build completed." => Some("build.mock.completed"),
        "Build cancelled by user." => Some("build.mock.cancelled"),
        "client file hash did not match the pinned profile" => Some("build.mock.hash_failed"),
        "selected mod is unsupported for this profile" => Some("build.mock.unsupported_mod"),
        "compiler exited with code 1" => Some("build.mock.compile_failed"),
        _ => None,
    };
    key.map_or_else(|| message.to_owned(), |key| language.text(key).to_owned())
}

fn display_progress_message(language: Language, progress: &BuildProgress) -> String {
    let key = format!("build.message.{}.{}", progress.phase, progress.step);
    let localized = language.text(&key);
    if localized != key {
        localized.to_owned()
    } else if progress.message.is_empty() {
        language.text("build.message.unknown").to_owned()
    } else {
        progress.message.clone()
    }
}

fn localized_build_failure(language: Language, progress: &BuildProgress) -> String {
    let detail = progress.error.as_deref().unwrap_or(&progress.message);
    let key = format!("build.error.{}.{}", progress.phase, progress.step);
    let localized = language.text(&key);
    let description = if localized == key {
        display_build_message(language, detail)
    } else if detail.is_empty() {
        localized.to_owned()
    } else {
        format!("{localized}: {detail}")
    };
    format!(
        "{}: {description}",
        display_build_phase(language, &progress.phase)
    )
}

fn localized_last_error(language: Language, stderr: &str, stdout: &str) -> String {
    let detail = last_nonempty(stderr, stdout);
    if detail == "The builder stopped without an error message." {
        language.text("build.error.no_message").to_owned()
    } else {
        display_build_message(language, &detail)
    }
}

fn mock_progress(phase: &str, pct: u8, message: &str) -> BuildProgress {
    BuildProgress {
        schema: "gaius-patcher-progress-v1".to_owned(),
        phase: phase.to_owned(),
        step: if phase == "error" {
            "failed".to_owned()
        } else {
            phase.to_owned()
        },
        pct,
        message: message.to_owned(),
        error: None,
        version: None,
    }
}

fn theme_label(language: Language, mode: ThemeMode) -> &'static str {
    language.text(match mode {
        ThemeMode::Dark => "theme.dark",
        ThemeMode::Light => "theme.light",
        ThemeMode::System => "theme.system",
    })
}

fn system_reduce_motion() -> bool {
    if std::env::var("GAIUS_REDUCE_MOTION")
        .is_ok_and(|value| value == "1" || value.eq_ignore_ascii_case("true"))
    {
        return true;
    }
    #[cfg(target_os = "linux")]
    if let Ok(output) = std::process::Command::new("gsettings")
        .args(["get", "org.gnome.desktop.interface", "enable-animations"])
        .output()
    {
        return String::from_utf8_lossy(&output.stdout).trim() == "false";
    }
    #[cfg(target_os = "macos")]
    if let Ok(output) = std::process::Command::new("defaults")
        .args(["read", "-g", "AppleReduceMotion"])
        .output()
    {
        return String::from_utf8_lossy(&output.stdout).trim() == "1";
    }
    #[cfg(target_os = "windows")]
    unsafe {
        #[link(name = "user32")]
        unsafe extern "system" {
            fn SystemParametersInfoW(
                action: u32,
                parameter: u32,
                value: *mut u32,
                flags: u32,
            ) -> i32;
        }
        let mut animations_enabled = 1_u32;
        if SystemParametersInfoW(0x1042, 0, &mut animations_enabled, 0) != 0 {
            return animations_enabled == 0;
        }
    }
    false
}

fn sanitize_user_paths(value: &str) -> String {
    let homes = ["HOME", "USERPROFILE"]
        .into_iter()
        .filter_map(std::env::var_os)
        .filter(|path| !path.is_empty())
        .map(|path| path.to_string_lossy().into_owned())
        .collect::<Vec<_>>();
    let usernames = ["USER", "USERNAME"]
        .into_iter()
        .filter_map(|variable| std::env::var(variable).ok())
        .filter(|name| !name.trim().is_empty())
        .collect::<Vec<_>>();
    sanitize_paths(value, &homes, &usernames)
}

fn sanitize_paths(value: &str, homes: &[String], usernames: &[String]) -> String {
    let mut result = value.to_owned();
    let mut replacements = Vec::with_capacity(homes.len() + usernames.len());
    for (index, home) in homes.iter().enumerate() {
        let marker = format!("\0GAIUS_REDACT_{index}\0");
        result = result.replace(home, &marker);
        replacements.push((marker, "<user-home>"));
    }
    for (index, username) in usernames.iter().enumerate() {
        let marker = format!("\0GAIUS_REDACT_USER_{index}\0");
        result = result.replace(username, &marker);
        replacements.push((marker, "<user>"));
    }
    for (marker, replacement) in replacements {
        result = result.replace(&marker, replacement);
    }
    result
}

fn copy_diagnostics(value: &str) {
    let candidates: &[(&str, &[&str])] = if cfg!(target_os = "windows") {
        &[("clip", &[])]
    } else if cfg!(target_os = "macos") {
        &[("pbcopy", &[])]
    } else {
        &[("wl-copy", &[]), ("xclip", &["-selection", "clipboard"])]
    };
    for (program, args) in candidates {
        let Ok(mut child) = std::process::Command::new(program)
            .args(*args)
            .stdin(std::process::Stdio::piped())
            .spawn()
        else {
            continue;
        };
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(value.as_bytes());
        }
        if child.wait().is_ok_and(|status| status.success()) {
            break;
        }
    }
}

fn open_path(path: &Path) {
    #[cfg(target_os = "windows")]
    let result = std::process::Command::new("explorer").arg(path).spawn();
    #[cfg(target_os = "macos")]
    let result = std::process::Command::new("open").arg(path).spawn();
    #[cfg(target_os = "linux")]
    let result = std::process::Command::new("xdg-open").arg(path).spawn();
    let _ = result;
}

fn show_notification(message: &str) {
    #[cfg(target_os = "linux")]
    let result = std::process::Command::new("notify-send")
        .args(["Gaius Builder", message])
        .spawn();
    #[cfg(target_os = "macos")]
    let result = std::process::Command::new("osascript")
        .args([
            "-e",
            &format!(
                "display notification {} with title \"Gaius Builder\"",
                serde_json::to_string(message).unwrap_or_else(|_| "\"Build complete\"".to_owned())
            ),
        ])
        .spawn();
    #[cfg(target_os = "windows")]
    let result = std::process::Command::new("powershell").args(["-NoProfile", "-Command", &format!("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null; $xml = [Windows.Data.Xml.Dom.XmlDocument]::new(); $xml.LoadXml('<toast><visual><binding template=\"ToastGeneric\"><text>Gaius Builder</text><text>{}</text></binding></visual></toast>'); [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Gaius Builder').Show([Windows.UI.Notifications.ToastNotification]::new($xml)", message.replace('"', ""))]).spawn();
    let _ = result;
}

trait Sha256Text {
    fn sha256(&self) -> String;
}
impl Sha256Text for str {
    fn sha256(&self) -> String {
        format!("{:x}", Sha256::digest(self.as_bytes()))
    }
}

fn install_cjk_font(context: &egui::Context, use_system_fonts: bool) {
    context.set_missing_glyph_policy(egui::MissingGlyphPolicy::Tofu);
    if !use_system_fonts || std::env::var_os("GAIUS_BUILDER_NO_SYSTEM_FONTS").is_some() {
        context.set_font_providers(vec![]);
        context.set_fonts(egui::FontDefinitions::default());
        return;
    }
    let cjk_candidates = [
        (
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            0,
        ),
        ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 3),
        (
            "/usr/share/fonts/opentype/adobe-source-han-sans/SourceHanSansSC-Regular.otf",
            0,
        ),
        (
            "/usr/share/fonts/opentype/source-han-sans/SourceHanSansSC-Regular.otf",
            0,
        ),
        ("C:/Windows/Fonts/msyh.ttc", 0),
        ("C:/Windows/Fonts/msyh.ttf", 0),
        ("/System/Library/Fonts/PingFang.ttc", 0),
        ("/Library/Fonts/Arial Unicode.ttf", 0),
    ];
    let symbol_candidates = [
        ("C:/Windows/Fonts/seguisym.ttf", 0),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0),
        (
            "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
            0,
        ),
        ("/usr/share/fonts/truetype/freefont/FreeSans.ttf", 0),
        (
            "/usr/share/fonts/opentype/noto/NotoSansSymbols2-Regular.ttf",
            0,
        ),
        ("/System/Library/Fonts/Apple Symbols.ttf", 0),
    ];
    let mut fonts = egui::FontDefinitions::default();
    let mut append_font = |name: &str, path: &str, index: u32| -> bool {
        if let Ok(bytes) = fs::read(path) {
            let name = name.to_owned();
            fonts.font_data.insert(
                name.clone(),
                Arc::new(egui::FontData::from_blob(Arc::new(bytes), index)),
            );
            for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
                fonts.families.entry(family).or_default().push(name.clone());
            }
            return true;
        }
        false
    };
    for (path, index) in cjk_candidates {
        if append_font("system-cjk-fallback", path, index) {
            break;
        }
    }
    for (index, (path, face)) in symbol_candidates.into_iter().enumerate() {
        append_font(&format!("system-symbol-fallback-{index}"), path, face);
    }
    context.set_fonts(fonts);
}

#[cfg(test)]
mod tests {
    use super::*;
    use egui_kittest::kittest::{NodeT, Queryable};
    use std::collections::BTreeSet;

    fn test_app() -> BuilderApp {
        let context = egui::Context::default();
        BuilderApp::new(&context, Language::English, ThemeMode::Dark, 1, false)
    }

    fn visible_input_ids(harness: &egui_kittest::Harness<'_, BuilderApp>) -> BTreeSet<String> {
        harness
            .root()
            .children_recursive()
            .filter_map(|node| {
                let access = node.accesskit_node();
                (egui::accessibility::is_input(access.role())
                    && !access.is_hidden()
                    && !access.is_disabled()
                    && !access.data().is_read_only())
                .then(|| format!("{:?}", access.id()))
            })
            .collect()
    }

    fn focused_input_id(harness: &egui_kittest::Harness<'_, BuilderApp>) -> Option<String> {
        harness
            .root()
            .children_recursive()
            .find(|node| node.accesskit_node().is_focused())
            .map(|node| format!("{:?}", node.accesskit_node().id()))
    }

    fn collect_text_galleys(shape: &egui::Shape, clip: Rect, output: &mut Vec<(Rect, Rect)>) {
        match shape {
            egui::Shape::Text(text) => {
                output.push((text.galley.rect.translate(text.pos.to_vec2()), clip));
            }
            egui::Shape::Vec(shapes) => {
                for shape in shapes {
                    collect_text_galleys(shape, clip, output);
                }
            }
            _ => {}
        }
    }

    fn assert_audited_text_fits_clip(
        harness: &egui_kittest::Harness<'_, BuilderApp>,
        page: usize,
        state_name: &str,
        size: [u32; 2],
        scale: f32,
        language: Language,
    ) {
        let mut rendered = Vec::new();
        for clipped in &harness.output().shapes {
            collect_text_galleys(&clipped.shape, clipped.clip_rect, &mut rendered);
        }
        for audit in harness.state().layout_rects.text_audits.iter().flatten() {
            if !rects_overlap(audit.rect, audit.clip) {
                continue;
            }
            assert!(
                rect_contains_rect(audit.clip, audit.rect),
                "text galley {name} is clipped at page {page}, state {state_name}, size {size:?}, scale {scale}, language {language:?}: {rect:?} vs {clip:?}",
                name = audit.name,
                rect = audit.rect,
                clip = audit.clip,
            );
            for (galley, clip) in rendered
                .iter()
                .filter(|(galley, _)| rects_approximately_equal(*galley, audit.rect))
            {
                assert!(
                    rect_contains_rect(audit.clip, *galley),
                    "rendered galley {name} is clipped at page {page}, state {state_name}, size {size:?}, scale {scale}, language {language:?}: {galley:?} vs {clip:?}",
                    name = audit.name,
                    galley = galley,
                    clip = clip,
                );
            }
        }
    }

    #[test]
    fn all_six_steps_have_defined_transitions_and_gates() {
        let mut app = test_app();
        assert!(app.can_advance());
        app.page = 2;
        app.advance();
        assert_eq!(app.page, 3);
        app.terms_checked = false;
        app.page = 3;
        app.advance();
        assert_eq!(app.page, 3);
        app.terms_checked = true;
        app.advance();
        assert_eq!(app.page, 4);
        assert!(!app.step_complete(4));
        app.advance();
        assert_eq!(app.page, 5);
        assert!(app.step_complete(4));
        app.build_state = BuildState::Running;
        app.advance();
        assert_eq!(app.page, 5);
        app.build_state = BuildState::Succeeded;
        app.advance();
        assert_eq!(app.page, 6);
    }

    #[test]
    fn terms_scroll_and_acceptance_gate_page_advance() {
        let mut app = test_app();
        app.page = 3;
        app.terms_scrolled = false;
        app.terms_checked = false;
        app.advance();
        assert_eq!(app.page, 3);
        app.terms_scrolled = true;
        app.terms_checked = true;
        app.advance();
        assert_eq!(app.page, 4);
        assert_eq!(app.terms_hash.len(), 64);
    }

    #[test]
    fn terms_checkbox_stays_inside_the_screen_at_all_sizes_and_scales() {
        let cases = [
            ([800, 540], 1.0),
            ([800, 540], 1.25),
            ([800, 540], 1.5),
            ([800, 540], 2.0),
            ([960, 620], 1.0),
            ([960, 620], 1.25),
            ([960, 620], 1.5),
            ([960, 620], 2.0),
        ];
        for language in [Language::Chinese, Language::English] {
            for (size, scale) in cases {
                let mut harness = egui_kittest::Harness::builder()
                    .with_size(Vec2::new(size[0] as f32, size[1] as f32))
                    .with_pixels_per_point(1.0)
                    .with_context_setup(move |context| context.set_zoom_factor(scale))
                    .with_render_cursor(false)
                    .build_eframe(|creation| {
                        let mut app = BuilderApp::new(
                            &creation.egui_ctx,
                            language,
                            ThemeMode::Dark,
                            3,
                            false,
                        );
                        app.config.scale = scale;
                        app
                    });
                harness.set_size(Vec2::new(size[0] as f32 / scale, size[1] as f32 / scale));
                harness.run();

                let layout = harness.state().layout_rects;
                let checkbox = layout.terms_checkbox.expect("terms checkbox is laid out");
                let mark = layout
                    .terms_checkbox_mark
                    .expect("terms checkbox mark is laid out");
                let label = layout
                    .terms_checkbox_label
                    .expect("terms checkbox label is laid out");
                let hash = layout.terms_hash_label.expect("terms hash is laid out");
                let hash_copy = layout
                    .terms_hash_copy
                    .expect("terms hash copy control is laid out");
                assert!(
                    rect_contains_rect(layout.viewport, checkbox),
                    "terms checkbox outside screen for {language:?}, size {size:?}, scale {scale}: {checkbox:?} vs {:?}",
                    layout.viewport
                );
                assert!(
                    rect_contains_rect(layout.action_row, checkbox),
                    "terms checkbox outside its pinned action row for {language:?}, size {size:?}, scale {scale}: {checkbox:?} vs {:?}",
                    layout.action_row
                );
                assert!(
                    (mark.min.y - label.min.y).abs() <= 1.5,
                    "terms checkbox is not aligned with the first label line for {language:?}, size {size:?}, scale {scale}: {mark:?} vs {label:?}"
                );
                assert!(
                    layout
                        .nav_next
                        .is_some_and(|next| rect_contains_rect(layout.bottom_bar, next)),
                    "Next is not visible at {language:?}, size {size:?}, scale {scale}"
                );
                assert!(
                    hash.width() >= 40.0,
                    "terms hash has insufficient display width for {language:?}, size {size:?}, scale {scale}: {hash:?}"
                );
                assert!(
                    (hash.min.x - checkbox.min.x).abs() <= 1.0,
                    "terms hash row is not left-aligned with the consent checkbox for {language:?}, size {size:?}, scale {scale}: {hash:?} vs {checkbox:?}"
                );
                assert!(
                    (hash_copy.max.x - layout.action_row.max.x).abs() <= 1.0,
                    "terms hash copy control is not at the right end of the action row for {language:?}, size {size:?}, scale {scale}: {hash_copy:?} vs {:?}",
                    layout.action_row
                );
                for (name, rect) in [("terms hash", hash), ("terms hash copy", hash_copy)] {
                    assert!(
                        rect_contains_rect(layout.viewport, rect)
                            && rect_contains_rect(layout.action_row, rect),
                        "{name} is not visible at {language:?}, size {size:?}, scale {scale}: {rect:?}, action row {:?}, viewport {:?}",
                        layout.action_row,
                        layout.viewport
                    );
                }
                if size == [800, 540] && scale == 1.0 {
                    assert!(
                        !harness.state().terms_scrolled,
                        "terms should require scrolling at the minimum size for {language:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn scrolling_to_terms_end_and_checking_enables_next() {
        let language = Language::English;
        let mut harness = egui_kittest::Harness::builder()
            .with_size(Vec2::new(800.0, 540.0))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                BuilderApp::new(&creation.egui_ctx, language, ThemeMode::Dark, 3, false)
            });
        harness.run();
        assert!(!harness.state().terms_scrolled);
        assert!(!harness.state().can_advance());

        let paragraphs = terms_text(language).split("\n\n").collect::<Vec<_>>();
        let first_paragraph = paragraphs.first().copied().unwrap();
        let last_paragraph = paragraphs.last().copied().unwrap();
        assert!(
            harness.get_by_label(first_paragraph).rect().top()
                < harness.get_by_label(last_paragraph).rect().top(),
            "terms must remain in reading order"
        );
        for _ in 0..32 {
            if harness.state().terms_scrolled {
                break;
            }
            harness.get_by_label(first_paragraph).scroll_down();
            harness.run();
        }
        assert!(
            harness.state().terms_scrolled,
            "terms reached their scroll end"
        );
        assert!(!harness.state().can_advance());

        harness
            .get_by_role_and_label(
                egui::accesskit::Role::CheckBox,
                language.text("terms.accept"),
            )
            .click();
        harness.run();
        assert!(harness.state().terms_checked);
        assert!(harness.state().can_advance());
        assert!(!harness.get_by_label("Next").accesskit_node().is_disabled());
    }

    #[test]
    fn status_bar_uses_the_current_workflow_stage_in_both_languages() {
        for language in [Language::Chinese, Language::English] {
            let palette = ThemeMode::Dark.palette(Some(Theme::Dark));
            let mut app = test_app();
            app.language = language;
            app.page = 2;
            app.doctor_error = Some("mock check error".to_owned());
            assert_eq!(app.status(palette).1, language.text("status.check_failed"));

            app.doctor_error = None;
            app.doctor_report = Some(failed_hash_report());
            assert_eq!(app.status(palette).1, language.text("status.hash_error"));

            app.page = 5;
            app.build_state = BuildState::Failed;
            assert_eq!(app.status(palette).1, language.text("status.build_failed"));

            app.build_state = BuildState::Cancelled;
            assert_eq!(app.status(palette).1, language.text("status.cancelled"));
        }
    }

    #[test]
    fn mod_form_stays_within_the_page_clip_at_high_scales() {
        for (size, scale) in [
            ([800, 540], 1.5),
            ([800, 540], 2.0),
            ([1280, 800], 1.5),
            ([1280, 800], 2.0),
        ] {
            let physical_size = Vec2::new(size[0] as f32, size[1] as f32);
            let mut harness = egui_kittest::Harness::builder()
                .with_size(physical_size)
                .with_pixels_per_point(1.0)
                .with_context_setup(move |context| context.set_zoom_factor(scale))
                .with_render_cursor(false)
                .build_eframe(|creation| {
                    let mut app = BuilderApp::with_state(
                        &creation.egui_ctx,
                        Language::English,
                        ThemeMode::Light,
                        4,
                        false,
                        ScreenshotState::UnsupportedMod,
                    );
                    app.config.scale = scale;
                    app
                });
            harness.set_size(physical_size / scale);
            harness.run();
            let layout = harness.state().layout_rects;
            if layout.content_used.max.x > layout.content.max.x + 12.0 {
                let overflow_widgets = harness
                    .root()
                    .children_recursive()
                    .filter_map(|node| {
                        node.accesskit_node().bounding_box()?;
                        let rect = node.rect();
                        (rect.right() > layout.content.max.x + 0.5).then(|| {
                            (
                                node.accesskit_node().role(),
                                node.accesskit_node().label(),
                                rect,
                            )
                        })
                    })
                    .collect::<Vec<_>>();
                panic!(
                    "Mods content exceeds the vertical-scroll viewport at {size:?}, {scale}: {:?} vs {:?}, panel {:?}, add {:?}, first row {:?}, overflow {:?}",
                    layout.content_used,
                    layout.content,
                    layout.config_panel,
                    layout.config_add_mod,
                    layout.mod_first_row,
                    overflow_widgets
                );
            }
            let add_mod = layout.config_add_mod.expect("Mods tab has an add button");
            assert!(rect_contains_rect(layout.content_used, add_mod));
        }
    }

    #[test]
    fn mods_first_row_can_be_scrolled_fully_into_the_panel() {
        let mut harness = egui_kittest::Harness::builder()
            .with_size(Vec2::new(800.0, 540.0))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                BuilderApp::with_state(
                    &creation.egui_ctx,
                    Language::Chinese,
                    ThemeMode::Dark,
                    4,
                    false,
                    ScreenshotState::ModsScrolled,
                )
            });
        harness.run();
        let initial_layout = harness.state().layout_rects;
        assert!(
            initial_layout.content_used.max.x <= initial_layout.content.max.x + 12.0,
            "Mods content exceeds its vertical-scroll viewport: {:?} vs {:?}",
            initial_layout.content_used,
            initial_layout.content
        );
        let first_row = initial_layout
            .mod_first_row
            .expect("first mod row is laid out");
        let initial_top = first_row.min.y;
        let viewport = harness
            .state()
            .layout_rects
            .config_scroll_viewport
            .expect("configuration tab has a scroll viewport");
        assert!(
            !rect_contains_rect(viewport, first_row),
            "fixture must start with the first row clipped"
        );

        harness
            .get_by_label("local/example-mod-1.jar")
            .scroll_to_me();
        harness.run();
        let layout = harness.state().layout_rects;
        let first_row = layout.mod_first_row.expect("first row remains laid out");
        let viewport = layout
            .config_scroll_viewport
            .expect("configuration tab retains its viewport");
        assert!(
            rect_contains_rect(viewport, first_row),
            "first row remains clipped: viewport {viewport:?}, row {first_row:?}"
        );
        assert!(
            first_row.min.y < initial_top,
            "scrolling moves the row into view"
        );
    }

    #[test]
    fn rejects_missing_local_mappings_file_before_build() {
        let mut app = test_app();
        app.selected_versions = vec!["1.21.11".to_owned()];
        app.terms_checked = true;
        app.config.mappings_path = "/path/that/does/not/exist/client_mappings".to_owned();
        app.start_build();
        assert_eq!(app.build_state, BuildState::Failed);
        assert_eq!(
            app.build_error.as_deref(),
            Some(app.language.text("build.mapping_missing"))
        );
    }

    #[test]
    fn local_mappings_section_renders_for_12111() {
        let mut harness = egui_kittest::Harness::builder()
            .with_size(Vec2::new(960.0, 620.0))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                let mut app = BuilderApp::new(
                    &creation.egui_ctx,
                    Language::English,
                    ThemeMode::Dark,
                    2,
                    false,
                );
                app.selected_versions = vec!["1.21.11".to_owned()];
                app
            });
        harness.run();
        harness.get_by_label("Local mappings for 1.21.11");
    }

    #[test]
    fn mock_end_states_have_text_and_icon_reproducibility_badges() {
        for (state, expected) in [
            ("badge-green", ReproStatus::Identical),
            ("badge-yellow", ReproStatus::Custom),
            ("badge-red", ReproStatus::Diverged),
        ] {
            let parsed = ScreenshotState::parse(state).unwrap();
            let mut app = test_app();
            match parsed {
                ScreenshotState::BadgeGreen => app.mock_completed(ReproStatus::Identical),
                ScreenshotState::BadgeYellow => app.mock_completed(ReproStatus::Custom),
                ScreenshotState::BadgeRed => app.mock_completed(ReproStatus::Diverged),
                _ => unreachable!(),
            }
            assert_eq!(app.repro_status, expected);
            assert_eq!(app.build_state, BuildState::Succeeded);
        }
        assert!(ScreenshotState::parse("unsupported-mod").is_ok());
        assert!(ScreenshotState::parse("detect-fail").is_ok());
        assert!(ScreenshotState::parse("hash-error").is_ok());
        assert!(ScreenshotState::parse("build-success").is_ok());
        assert!(ScreenshotState::parse("build-fail").is_ok());
        assert!(ScreenshotState::parse("cancelled").is_ok());
        assert!(ScreenshotState::parse("terms-accepted").is_ok());
        assert!(ScreenshotState::parse("mods-scrolled").is_ok());
    }

    #[test]
    fn symbolless_fonts_render_every_page_and_screenshot_state() {
        let states = [
            ScreenshotState::Normal,
            ScreenshotState::TermsAccepted,
            ScreenshotState::DetectFail,
            ScreenshotState::HashError,
            ScreenshotState::BuildSuccess,
            ScreenshotState::BuildFail,
            ScreenshotState::Cancelled,
            ScreenshotState::Running,
            ScreenshotState::UnsupportedMod,
            ScreenshotState::ModsScrolled,
            ScreenshotState::BadgeGreen,
            ScreenshotState::BadgeYellow,
            ScreenshotState::BadgeRed,
            ScreenshotState::ToolchainField,
            ScreenshotState::PreflightError,
        ];
        for state in states {
            for page in 1..=6 {
                let mut harness = egui_kittest::Harness::builder()
                    .with_size(Vec2::new(960.0, 620.0))
                    .allow_missing_glyphs()
                    .with_render_cursor(false)
                    .build_eframe(|creation| {
                        let mut app = BuilderApp::with_state_without_system_fonts(
                            &creation.egui_ctx,
                            Language::Chinese,
                            ThemeMode::Dark,
                            page,
                            state,
                        );
                        app.config.reduce_motion = true;
                        app
                    });
                harness.run();
                harness
                    .render()
                    .unwrap_or_else(|error| panic!("{state:?} page {page}: {error}"));
            }
        }
    }

    #[test]
    fn mock_build_phases_messages_and_failures_are_localized() {
        let messages = [
            ("Validating local files", "build.mock.validating"),
            ("Applying source patches", "build.mock.patching"),
            ("Compiling client", "build.mock.compiling"),
            ("Writing output and hashes", "build.mock.publishing"),
            ("Build completed.", "build.mock.completed"),
            ("Build cancelled by user.", "build.mock.cancelled"),
            (
                "client file hash did not match the pinned profile",
                "build.mock.hash_failed",
            ),
            (
                "selected mod is unsupported for this profile",
                "build.mock.unsupported_mod",
            ),
            ("compiler exited with code 1", "build.mock.compile_failed"),
        ];
        for language in [Language::Chinese, Language::English] {
            for (raw, key) in messages {
                assert_eq!(display_build_message(language, raw), language.text(key));
            }
            for (phase, key) in [
                ("fetch", "build.stage.verify"),
                ("stage", "build.stage.patch"),
                ("prepare", "build.stage.patch"),
                ("compile", "build.stage.compile"),
                ("publish", "build.stage.package"),
                ("compare", "build.stage.compare"),
                ("error", "build.phase.error"),
            ] {
                assert_eq!(display_build_phase(language, phase), language.text(key));
            }
            assert_eq!(
                display_build_phase(language, "future_phase"),
                language.text("build.stage.unknown")
            );
            let known_step = BuildProgress {
                schema: "gaius-patcher-progress-v1".to_owned(),
                phase: "fetch".to_owned(),
                step: "local-files".to_owned(),
                pct: 8,
                message: "Validating local launcher files".to_owned(),
                error: None,
                version: None,
            };
            assert_eq!(
                display_progress_message(language, &known_step),
                language.text("build.message.fetch.local-files")
            );
            let future_step = BuildProgress {
                step: "future-step".to_owned(),
                ..known_step
            };
            assert_eq!(
                display_progress_message(language, &future_step),
                "Validating local launcher files"
            );
        }
        let failure = mock_progress("error", 100, "compiler exited with code 1");
        assert_eq!(
            localized_build_failure(Language::Chinese, &failure),
            "失败: 补丁器报告了构建错误: compiler exited with code 1"
        );
        assert_eq!(
            localized_build_failure(Language::English, &failure),
            "Failed: The patcher reported a build error: compiler exited with code 1"
        );
        assert_eq!(
            display_build_message(Language::Chinese, "raw patcher diagnostic"),
            "raw patcher diagnostic"
        );
        assert_eq!(
            display_doctor_reason(
                Language::Chinese,
                "26.3",
                "client SHA-1 did not match the pinned profile"
            ),
            "客户端文件 SHA-1 与固定配置不一致。"
        );
        assert_eq!(
            display_doctor_reason(
                Language::English,
                "26.3",
                "client SHA-1 did not match the pinned profile"
            ),
            "The client file SHA-1 did not match the pinned profile."
        );
        assert_eq!(
            display_doctor_reason(Language::Chinese, "26.3", "raw doctor diagnostic"),
            "raw doctor diagnostic"
        );
        assert_eq!(
            display_doctor_reason(
                Language::Chinese,
                "1.21.11",
                "start 1.21.11 once in the official launcher"
            ),
            "请先在官方启动器中启动 1.21.11。"
        );
        assert_eq!(
            display_doctor_reason(
                Language::English,
                "1.21.11",
                "start 1.21.11 once in the official launcher"
            ),
            "Start 1.21.11 once in the official launcher."
        );
        assert_eq!(
            display_doctor_value(Language::Chinese, "/path/work (316.3 GB free)"),
            "/path/work (316.3 GB 可用)"
        );
    }

    #[test]
    fn shader_toolchain_manifest_and_modules_are_verified() {
        let root = std::env::temp_dir().join(format!("patchgui-toolchain-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).expect("create temporary toolchain");
        let module_names = [
            "gaius-shader-toolchain.js",
            "gaius-shaderc.js",
            "gaius-shaderc.wasm",
            "gaius-spvc.js",
            "gaius-spvc.wasm",
        ];
        let mut file_hashes = serde_json::Map::new();
        for name in module_names {
            let bytes = format!("fixture:{name}");
            fs::write(root.join(name), &bytes).expect("write fixture module");
            file_hashes.insert(
                name.to_owned(),
                serde_json::json!({ "sha256": bytes.sha256(), "bytes": bytes.len() }),
            );
        }
        let pins = include_str!("../../../port/wasm/shader-toolchain/pins.env")
            .lines()
            .filter_map(|line| line.split_once('='))
            .collect::<std::collections::HashMap<_, _>>();
        let sources = serde_json::json!({
            "shaderc": pins["GAIUS_ST_SHADERC_COMMIT"],
            "glslang": pins["GAIUS_ST_GLSLANG_COMMIT"],
            "spirv-tools": pins["GAIUS_ST_SPIRV_TOOLS_COMMIT"],
            "spirv-headers": pins["GAIUS_ST_SPIRV_HEADERS_COMMIT"],
            "spirv-cross": pins["GAIUS_ST_SPIRV_CROSS_COMMIT"],
        });
        let manifest = serde_json::json!({
            "schema": 1,
            "emscripten": format!("{} ({})", pins["GAIUS_ST_EMSCRIPTEN_VERSION"], pins["GAIUS_ST_EMSCRIPTEN_COMMIT"]),
            "sources": sources,
            "files": file_hashes,
        });
        fs::write(
            root.join("gaius-shader-toolchain.json"),
            serde_json::to_vec(&manifest).unwrap(),
        )
        .expect("write manifest");
        assert!(validate_shader_toolchain(&root).is_ok());
        fs::write(root.join("gaius-spvc.wasm"), b"modified").expect("modify fixture module");
        assert!(validate_shader_toolchain(&root).is_err());
        fs::remove_dir_all(root).expect("remove fixture toolchain");
    }

    #[test]
    fn build_options_mark_cli_settings_as_applied_and_mods_as_unapplied() {
        let root = std::env::temp_dir().join(format!("patchgui-options-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let mut app = test_app();
        app.output_dir = root.to_string_lossy().into_owned();
        app.selected_versions = vec!["26.2".to_owned()];
        app.config.distance = 16;
        app.config.mods.push(ModRow {
            path: "local/mod.jar".to_owned(),
            supported: CompatGrade::Experimental,
            licensed: true,
        });
        assert!(app.config_is_custom());
        app.write_build_options().expect("write build options");
        let record: serde_json::Value = serde_json::from_slice(
            &fs::read(root.join("build-options.json")).expect("options file exists"),
        )
        .expect("options file parses");
        assert_eq!(record["schema"], "gaius-builder-options-v1");
        let applied = record["applied_to_build"].as_array().unwrap();
        for key in [
            "graphics_backend",
            "quality",
            "view_distance",
            "local_packs",
        ] {
            assert!(applied.contains(&serde_json::json!(key)));
        }
        assert!(
            record["not_applied_to_build"]
                .as_array()
                .unwrap()
                .contains(&serde_json::json!("mods"))
        );
        assert_eq!(record["view_distance_chunks"], 16);
        fs::remove_dir_all(root).expect("remove fixture options");
    }

    #[test]
    fn partial_output_cleanup_refuses_paths_outside_output_root() {
        let root = std::env::temp_dir().join(format!("patchgui-cleanup-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let output = root.join("output");
        let outside = root.join("outside/26.2");
        fs::create_dir_all(&output).expect("create output root");
        fs::create_dir_all(&outside).expect("create outside path");
        assert!(
            cleanup_owned_outputs(
                &output,
                std::slice::from_ref(&outside),
                &["26.2".to_owned()]
            )
            .is_err()
        );
        assert!(outside.is_dir());
        fs::remove_dir_all(root).expect("remove fixture cleanup roots");
    }

    #[test]
    fn cancelled_multiversion_cleanup_preserves_completed_artifacts() {
        let root =
            std::env::temp_dir().join(format!("patchgui-cancel-cleanup-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let output = root.join("output");
        let completed = output.join("26.2");
        let partial = output.join("26.3");
        fs::create_dir_all(&completed).expect("create completed output");
        fs::create_dir_all(&partial).expect("create partial output");
        fs::write(completed.join("artifact.html"), "complete").expect("write completed artifact");
        fs::write(partial.join("partial.bin"), "partial").expect("write partial artifact");
        let mut candidates = vec![completed.clone(), partial.clone()];
        let results = vec![
            VersionBuildResult {
                version: "26.2".to_owned(),
                output_dir: completed.clone(),
                exit_code: 0,
                error: None,
                hashes: HashComparison {
                    state: HashState::Match,
                    mismatches: Vec::new(),
                },
            },
            VersionBuildResult {
                version: "26.3".to_owned(),
                output_dir: partial.clone(),
                exit_code: 143,
                error: Some("cancelled".to_owned()),
                hashes: HashComparison {
                    state: HashState::Unknown,
                    mismatches: Vec::new(),
                },
            },
        ];

        retain_partial_outputs(&mut candidates, &results);
        assert_eq!(candidates.as_slice(), std::slice::from_ref(&partial));
        cleanup_owned_outputs(
            &output,
            &candidates,
            &["26.2".to_owned(), "26.3".to_owned()],
        )
        .expect("clean only partial version output");
        assert_eq!(
            fs::read_to_string(completed.join("artifact.html")).unwrap(),
            "complete"
        );
        assert!(!partial.exists());
        fs::remove_dir_all(root).expect("remove fixture cleanup roots");
    }

    #[test]
    fn installed_versions_are_detected_even_when_hash_verification_fails() {
        let report = failed_hash_report();
        let version = report
            .versions
            .iter()
            .find(|item| item.id == "26.3")
            .unwrap();
        assert!(version.installed);
        assert!(!version.verified);
        assert!(version_detected(&report, "26.3"));
        assert!(!version_detected(&report, "missing"));
    }

    #[test]
    fn completion_key_value_columns_have_fixed_left_edges() {
        for language in [Language::Chinese, Language::English] {
            for theme in [ScreenTheme::Dark, ScreenTheme::Light] {
                let mut harness = egui_kittest::Harness::builder()
                    .with_size(Vec2::new(960.0, 620.0))
                    .with_theme(match theme {
                        ScreenTheme::Dark => Theme::Dark,
                        ScreenTheme::Light => Theme::Light,
                    })
                    .with_render_cursor(false)
                    .build_eframe(|creation| {
                        BuilderApp::with_state(
                            &creation.egui_ctx,
                            language,
                            theme.mode(),
                            6,
                            false,
                            ScreenshotState::BadgeGreen,
                        )
                    });
                harness.run();

                let layout = harness.state().layout_rects;
                let labels = layout
                    .completion_labels
                    .map(|rect| rect.expect("every completion label has a layout rect"));
                let values = layout
                    .completion_values
                    .map(|rect| rect.expect("every completion value has a layout rect"));
                for rect in labels.iter().skip(1) {
                    assert!(
                        (rect.min.x - labels[0].min.x).abs() <= 0.5,
                        "completion labels do not share a left edge for {language:?}, {theme:?}: {labels:?}"
                    );
                }
                for rect in values.iter().skip(1) {
                    assert!(
                        (rect.min.x - values[0].min.x).abs() <= 0.5,
                        "completion values do not share a left edge for {language:?}, {theme:?}: {values:?}"
                    );
                }
                let artifacts = layout
                    .completion_artifacts
                    .expect("artifact heading has a layout rect");
                assert!(
                    (artifacts.min.x - labels[0].min.x).abs() <= 0.5,
                    "artifact heading is not left-aligned with the completion grid for {language:?}, {theme:?}: {artifacts:?}, {:?}",
                    labels[0]
                );
                let copy = layout
                    .completion_copy
                    .expect("path copy button has a layout rect");
                let path = values[1];
                assert!(
                    copy.min.x >= path.max.x - 0.5 && copy.min.x - path.max.x <= 8.5,
                    "copy button is not directly after the path for {language:?}, {theme:?}: {path:?}, {copy:?}"
                );
            }
        }
    }

    #[test]
    fn completion_path_and_artifact_heading_fit_at_200_percent_in_a_small_window() {
        let scale = 2.0;
        let size = Vec2::new(800.0, 540.0);
        let mut harness = egui_kittest::Harness::builder()
            .with_size(size)
            .with_pixels_per_point(1.0)
            .with_context_setup(move |context| context.set_zoom_factor(scale))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                let mut app = BuilderApp::with_state(
                    &creation.egui_ctx,
                    Language::English,
                    ThemeMode::Light,
                    6,
                    false,
                    ScreenshotState::BadgeGreen,
                );
                app.config.scale = scale;
                app.output_dir = "/data/output/builds/Gaius-26.3-with-a-long-directory-name".into();
                app
            });
        harness.set_size(size / scale);
        harness.run();

        let layout = harness.state().layout_rects;
        let label = layout.completion_labels[1].expect("location label is laid out");
        let path = layout.completion_values[1].expect("output path is laid out");
        let copy = layout.completion_copy.expect("copy control is laid out");
        let artifacts = layout
            .completion_artifacts
            .expect("artifact heading is laid out");
        assert!(
            path.width() > 40.0,
            "output path has no readable width: {path:?}"
        );
        assert!(copy.min.x >= path.max.x - 0.5 && copy.min.x - path.max.x <= 8.5);
        assert!((artifacts.min.x - label.min.x).abs() <= 0.5);
        assert!(
            rect_contains_rect(layout.content, path),
            "output path is outside the page viewport: {path:?} vs {:?}",
            layout.content
        );
        assert!(
            rect_contains_rect(layout.content, copy),
            "copy control is outside the page viewport: {copy:?} vs {:?}",
            layout.content
        );
    }

    #[test]
    fn top_bar_content_fits_and_stays_above_the_main_panel_at_every_scale() {
        let sizes = [[800, 540], [960, 620], [1280, 800], [1920, 1080]];
        let scales = [1.0, 1.25, 1.5, 2.0];
        for size in sizes {
            for scale in scales {
                let physical_size = Vec2::new(size[0] as f32, size[1] as f32);
                let mut harness = egui_kittest::Harness::builder()
                    .with_size(physical_size)
                    .with_pixels_per_point(1.0)
                    .with_context_setup(move |context| context.set_zoom_factor(scale))
                    .with_render_cursor(false)
                    .build_eframe(|creation| {
                        let mut app = BuilderApp::new(
                            &creation.egui_ctx,
                            Language::Chinese,
                            ThemeMode::Dark,
                            2,
                            false,
                        );
                        app.config.scale = scale;
                        app
                    });
                harness.set_size(physical_size / scale);
                harness.run();

                let layout = harness.state().layout_rects;
                assert!(
                    rect_contains_rect(layout.top_bar, layout.top_bar_content),
                    "top bar content exceeds its measured frame at {size:?}, scale {scale}: {:?} vs {:?}",
                    layout.top_bar_content,
                    layout.top_bar
                );
                assert!(
                    !rects_overlap(layout.top_bar, layout.content)
                        && !rects_overlap(layout.top_bar, layout.rail),
                    "top bar overlaps the main panel at {size:?}, scale {scale}: {:?}, content {:?}, rail {:?}",
                    layout.top_bar,
                    layout.content,
                    layout.rail
                );
            }
        }
    }

    #[test]
    fn high_scale_layout_gallery_when_requested() {
        let Some(directory) = std::env::var_os("GAIUS_BUILDER_HIGH_SCALE_GALLERY_DIR") else {
            return;
        };
        let directory = PathBuf::from(directory);
        fs::create_dir_all(&directory).expect("high-scale gallery directory is writable");
        let sizes = [
            ([800, 540], "800x540"),
            ([960, 620], "960x620"),
            ([1280, 800], "1280x800"),
            ([1920, 1080], "1920x1080"),
        ];
        let scales = [(1.0, "100"), (1.25, "125"), (1.5, "150"), (2.0, "200")];
        let mut index = String::from(
            "# T6d 高缩放布局截图\n\n顶栏使用第 2 页；完成页使用成功状态；条款页滚动到末尾并显示确认项。\n\n| 窗口 | 缩放 | 顶栏 | 完成页 | 条款页 |\n|---|---:|---|---|---|\n",
        );
        for (size, size_label) in sizes {
            for (scale, scale_label) in scales {
                let top = format!("top-bar-{size_label}-{scale_label}.png");
                let complete = format!("completion-{size_label}-{scale_label}.png");
                let terms = format!("terms-{size_label}-{scale_label}.png");
                render_screenshot(
                    &directory.join(&top),
                    2,
                    ScreenTheme::Dark,
                    Language::Chinese,
                    size,
                    scale,
                    "normal",
                )
                .expect("top-bar matrix screenshot renders");
                render_screenshot(
                    &directory.join(&complete),
                    6,
                    ScreenTheme::Dark,
                    Language::Chinese,
                    size,
                    scale,
                    "build-success",
                )
                .expect("completion matrix screenshot renders");
                render_screenshot(
                    &directory.join(&terms),
                    3,
                    ScreenTheme::Dark,
                    Language::Chinese,
                    size,
                    scale,
                    "terms-accepted",
                )
                .expect("terms matrix screenshot renders");
                index.push_str(&format!(
                    "| {size_label} | {scale_label}% | [{top}]({top}) | [{complete}]({complete}) | [{terms}]({terms}) |\n"
                ));
            }
        }
        fs::write(directory.join("INDEX.md"), index).expect("gallery index is writable");
    }

    #[test]
    fn completion_screenshot_gallery_when_requested() {
        let Some(directory) = std::env::var_os("GAIUS_BUILDER_COMPLETION_GALLERY_DIR") else {
            return;
        };
        let directory = PathBuf::from(directory);
        fs::create_dir_all(&directory).expect("completion gallery directory is writable");

        for language in [Language::Chinese, Language::English] {
            for theme in [ScreenTheme::Dark, ScreenTheme::Light] {
                let theme_code = theme_slug(theme);
                let language_code = language.code();
                render_screenshot(
                    &directory.join(format!("page-6-{theme_code}-{language_code}-960x620.png")),
                    6,
                    theme,
                    language,
                    [960, 620],
                    1.0,
                    "badge-green",
                )
                .expect("completion page screenshot renders");
                for (badge, state) in [
                    ("badge-green", "badge-green"),
                    ("badge-yellow", "badge-yellow"),
                    ("badge-red", "badge-red"),
                ] {
                    render_screenshot(
                        &directory
                            .join(format!("{badge}-{theme_code}-{language_code}-960x620.png")),
                        6,
                        theme,
                        language,
                        [960, 620],
                        1.0,
                        state,
                    )
                    .expect("completion badge screenshot renders");
                }
            }
        }
    }

    #[test]
    fn diagnostics_remove_current_home_and_username() {
        let home = std::env::var_os("HOME")
            .filter(|value| !value.is_empty())
            .map(|value| value.to_string_lossy().into_owned());
        let username = std::env::var("USER")
            .or_else(|_| std::env::var("USERNAME"))
            .ok()
            .filter(|value| !value.trim().is_empty());
        let source = format!(
            "path={} user={}",
            home.as_deref().unwrap_or("/missing-home"),
            username.as_deref().unwrap_or("missing-user")
        );
        let sanitized = sanitize_user_paths(&source);
        if let Some(home) = home {
            assert!(!sanitized.contains(&home));
            assert!(sanitized.contains("<user-home>"));
        }
        if let Some(username) = username {
            assert!(!sanitized.contains(&username));
            assert!(sanitized.contains("<user>"));
        }
        let overlapping = sanitize_paths(
            "/home/user/.minecraft owned by user",
            &["/home/user".to_owned()],
            &["user".to_owned()],
        );
        assert_eq!(overlapping, "<user-home>/.minecraft owned by <user>");
    }

    #[test]
    fn screenshot_gallery_when_requested() {
        let Some(directory) = std::env::var_os("GAIUS_BUILDER_GALLERY_DIR") else {
            return;
        };
        let directory = PathBuf::from(directory);
        fs::create_dir_all(&directory).expect("gallery directory is writable");
        for language in [Language::Chinese, Language::English] {
            for theme in [ScreenTheme::Dark, ScreenTheme::Light] {
                for page in 1..=6 {
                    let name = format!(
                        "page-{page}-{}-{}-960x620.png",
                        theme_slug(theme),
                        language.code()
                    );
                    render_screenshot(
                        &directory.join(name),
                        page,
                        theme,
                        language,
                        [960, 620],
                        1.0,
                        if page == 5 {
                            "running"
                        } else if page == 6 {
                            "badge-green"
                        } else {
                            "normal"
                        },
                    )
                    .expect("page screenshot renders");
                }
                render_screenshot(
                    &directory.join(format!(
                        "page-3-gate-before-{}-{}-800x540.png",
                        theme_slug(theme),
                        language.code()
                    )),
                    3,
                    theme,
                    language,
                    [800, 540],
                    1.0,
                    "normal",
                )
                .expect("terms-before screenshot renders");
                render_screenshot(
                    &directory.join(format!(
                        "page-3-gate-accepted-{}-{}-800x540.png",
                        theme_slug(theme),
                        language.code()
                    )),
                    3,
                    theme,
                    language,
                    [800, 540],
                    1.0,
                    "terms-accepted",
                )
                .expect("terms-accepted screenshot renders");
            }
        }
        for (name, page, state) in [
            ("detect-fail", 2, "detect-fail"),
            ("hash-error", 2, "hash-error"),
            ("running", 5, "running"),
            ("build-success", 6, "build-success"),
            ("build-fail", 5, "build-fail"),
            ("cancelled", 5, "cancelled"),
            ("unsupported-mod", 4, "unsupported-mod"),
            ("badge-green", 6, "badge-green"),
            ("badge-yellow", 6, "badge-yellow"),
            ("badge-red", 6, "badge-red"),
        ] {
            render_screenshot(
                &directory.join(format!("{name}-dark-zh-960x620.png")),
                page,
                ScreenTheme::Dark,
                Language::Chinese,
                [960, 620],
                1.0,
                state,
            )
            .expect("state screenshot renders");
        }
        render_screenshot(
            &directory.join("toolchain-field-dark-zh-960x620.png"),
            2,
            ScreenTheme::Dark,
            Language::Chinese,
            [960, 620],
            1.0,
            "toolchain-field",
        )
        .expect("toolchain field screenshot renders");
        for (name, scale) in [("scale-125", 1.25), ("scale-150", 1.5)] {
            render_screenshot(
                &directory.join(format!("{name}-dark-zh.png")),
                4,
                ScreenTheme::Dark,
                Language::Chinese,
                [960, 620],
                scale,
                "normal",
            )
            .expect("scale screenshot renders");
        }
        render_screenshot(
            &directory.join("config-dark-zh-800x540.png"),
            4,
            ScreenTheme::Dark,
            Language::Chinese,
            [800, 540],
            1.0,
            "mods-scrolled",
        )
        .expect("dense page screenshot renders");

        if let Some(review_dir) = std::env::var_os("GAIUS_BUILDER_LAYOUT_REVIEW_DIR") {
            let review_dir = PathBuf::from(review_dir);
            fs::create_dir_all(&review_dir).expect("layout review directory is writable");
            for page in 1..=6 {
                render_screenshot(
                    &review_dir.join(format!("page-{page}-800x540.png")),
                    page,
                    ScreenTheme::Dark,
                    Language::Chinese,
                    [800, 540],
                    1.0,
                    if page == 5 {
                        "running"
                    } else if page == 6 {
                        "badge-green"
                    } else {
                        "normal"
                    },
                )
                .expect("minimum-size page screenshot renders");
            }
            render_screenshot(
                &review_dir.join("page-4-mods-800x540.png"),
                4,
                ScreenTheme::Dark,
                Language::Chinese,
                [800, 540],
                1.0,
                "mods-scrolled",
            )
            .expect("minimum-size mods screenshot renders");
        }
    }

    #[test]
    fn scale_regression_screenshots_when_requested() {
        let Some(directory) = std::env::var_os("GAIUS_BUILDER_SCALE_GALLERY_DIR") else {
            return;
        };
        let directory = PathBuf::from(directory);
        fs::create_dir_all(&directory).expect("scale gallery directory is writable");
        let dimensions = [([1280, 800], "1280x800"), ([800, 540], "800x540")];
        let scales = [(1.5, "150"), (2.0, "200")];
        let languages = [Language::Chinese, Language::English];

        for (size, size_label) in dimensions {
            for (scale, scale_label) in scales {
                for language in languages {
                    let language_label = language.code();
                    render_screenshot(
                        &directory.join(format!(
                            "success-{scale_label}-{size_label}-{language_label}.png"
                        )),
                        6,
                        ScreenTheme::Light,
                        language,
                        size,
                        scale,
                        "build-success",
                    )
                    .expect("scaled success screenshot renders");
                    render_screenshot(
                        &directory.join(format!(
                            "mods-{scale_label}-{size_label}-{language_label}.png"
                        )),
                        4,
                        ScreenTheme::Light,
                        language,
                        size,
                        scale,
                        "mods-scrolled",
                    )
                    .expect("scaled Mods screenshot renders");
                    for (state, page) in [
                        ("detect-fail", 2),
                        ("hash-error", 2),
                        ("build-fail", 5),
                        ("cancelled", 5),
                    ] {
                        render_screenshot(
                            &directory.join(format!(
                                "{state}-{scale_label}-{size_label}-{language_label}.png"
                            )),
                            page,
                            ScreenTheme::Light,
                            language,
                            size,
                            scale,
                            state,
                        )
                        .expect("scaled failure screenshot renders");
                    }
                }
            }
        }
    }

    #[test]
    fn running_build_progress_stays_within_the_page_clip_at_150_percent() {
        let scale = 1.5;
        let size = Vec2::new(800.0, 540.0);
        let mut harness = egui_kittest::Harness::builder()
            .with_size(size)
            .with_pixels_per_point(1.0)
            .with_context_setup(move |context| context.set_zoom_factor(scale))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                let mut app = BuilderApp::with_state(
                    &creation.egui_ctx,
                    Language::English,
                    ThemeMode::Light,
                    5,
                    false,
                    ScreenshotState::Running,
                );
                app.config.scale = scale;
                app
            });
        harness.set_size(size / scale);
        harness.run();
        let layout = harness.state().layout_rects;
        assert!(
            layout.content_used.max.x <= layout.content.max.x + 12.0,
            "running build content exceeds the vertical-scroll viewport: {:?} vs {:?}",
            layout.content_used,
            layout.content
        );
        for action in layout.action_widgets[..layout.action_widget_count]
            .iter()
            .flatten()
        {
            assert!(rect_contains_rect(layout.action_row, *action));
            assert!(rect_contains_rect(layout.viewport, *action));
        }
    }

    #[test]
    fn completion_results_and_actions_fit_at_200_percent_in_a_small_window() {
        let scale = 2.0;
        let size = Vec2::new(800.0, 540.0);
        let mut harness = egui_kittest::Harness::builder()
            .with_size(size)
            .with_pixels_per_point(1.0)
            .with_context_setup(move |context| context.set_zoom_factor(scale))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                let mut app = BuilderApp::with_state(
                    &creation.egui_ctx,
                    Language::English,
                    ThemeMode::Light,
                    6,
                    false,
                    ScreenshotState::BadgeRed,
                );
                app.config.scale = scale;
                app
            });
        harness.set_size(size / scale);
        harness.run();
        let layout = harness.state().layout_rects;
        assert!(
            layout.content_used.max.x <= layout.content.max.x + 12.0,
            "completion content exceeds the vertical-scroll viewport: {:?} vs {:?}",
            layout.content_used,
            layout.content
        );
        for action in layout.action_widgets[..layout.action_widget_count]
            .iter()
            .flatten()
        {
            assert!(rect_contains_rect(layout.action_row, *action));
            assert!(rect_contains_rect(layout.viewport, *action));
        }
    }

    #[test]
    fn real_preflight_screenshot_rejects_pages_other_than_build() {
        let error = render_screenshot(
            Path::new("unused-preflight-page-check.png"),
            4,
            ScreenTheme::Dark,
            Language::Chinese,
            [960, 620],
            1.0,
            "real-preflight-error",
        )
        .expect_err("preflight error screenshot must use the build page");
        assert_eq!(error, "real-preflight-error screenshots must use page 5");
    }

    #[test]
    fn shell_panels_stay_in_viewport_and_bottom_bar_is_page_stable() {
        let states = [
            ("normal", ScreenshotState::Normal),
            ("detect-fail", ScreenshotState::DetectFail),
            ("hash-error", ScreenshotState::HashError),
            ("build-fail", ScreenshotState::BuildFail),
            ("cancelled", ScreenshotState::Cancelled),
            ("running", ScreenshotState::Running),
            ("unsupported-mod", ScreenshotState::UnsupportedMod),
            ("mods-scrolled", ScreenshotState::ModsScrolled),
            ("badge-green", ScreenshotState::BadgeGreen),
            ("badge-yellow", ScreenshotState::BadgeYellow),
            ("badge-red", ScreenshotState::BadgeRed),
        ];
        let sizes = [
            ([800, 540], 1.0),
            ([800, 540], 1.25),
            ([800, 540], 1.5),
            ([800, 540], 2.0),
            ([960, 620], 1.5),
            ([1280, 800], 1.5),
            ([1280, 800], 2.0),
            ([1920, 1080], 1.0),
        ];
        let appearances = [
            (ThemeMode::Dark, Language::Chinese),
            (ThemeMode::Dark, Language::English),
            (ThemeMode::Light, Language::Chinese),
            (ThemeMode::Light, Language::English),
        ];

        for (state_index, (state_name, state)) in states.into_iter().enumerate() {
            for (dimension_index, (size, scale)) in sizes.into_iter().enumerate() {
                let (theme_mode, language) =
                    appearances[(state_index + dimension_index) % appearances.len()];
                let mut expected_bottom = None;
                for page in 1..=6 {
                    let mut harness = egui_kittest::Harness::builder()
                        .with_size(Vec2::new(size[0] as f32, size[1] as f32))
                        .with_pixels_per_point(1.0)
                        .with_context_setup(move |context| context.set_zoom_factor(scale))
                        .with_render_cursor(false)
                        .build_eframe(|creation| {
                            let mut app = BuilderApp::with_state(
                                &creation.egui_ctx,
                                language,
                                theme_mode,
                                page,
                                false,
                                state,
                            );
                            app.config.scale = scale;
                            app.config.reduce_motion = true;
                            app
                        });
                    harness.set_size(Vec2::new(size[0] as f32 / scale, size[1] as f32 / scale));
                    harness.run();

                    let layout = harness.state().layout_rects;
                    assert_audited_text_fits_clip(
                        &harness, page, state_name, size, scale, language,
                    );
                    let viewport = layout.viewport;
                    assert!(layout.content_scroll_present);
                    if layout.content_scroll_needed {
                        assert!(layout.content_scroll_viewport.is_some());
                    }
                    if layout.config_scroll_needed {
                        assert!(layout.config_scroll_viewport.is_some());
                    }
                    let mut panels = vec![
                        ("top bar", layout.top_bar),
                        ("step rail", layout.rail),
                        ("content viewport", layout.content),
                        ("bottom bar", layout.bottom_bar),
                        ("footer", layout.footer),
                    ];
                    if let Some(config_panel) = layout.config_panel {
                        panels.push(("configuration panel", config_panel));
                    }
                    if layout.action_widget_count > 0 {
                        panels.push(("pinned action row", layout.action_row));
                    }
                    if page == 6
                        && state == ScreenshotState::BadgeGreen
                        && size == [800, 540]
                        && scale >= 1.5
                    {
                        assert_eq!(
                            layout.action_widget_count, 3,
                            "success actions must all render at size {size:?}, scale {scale}"
                        );
                    }
                    for (name, rect) in panels {
                        assert!(
                            rect.min.x >= viewport.min.x - 0.5
                                && rect.min.y >= viewport.min.y - 0.5
                                && rect.max.x <= viewport.max.x + 0.5
                                && rect.max.y <= viewport.max.y + 0.5,
                            "{name} outside viewport at page {page}, state {state_name}, size {size:?}, scale {scale}: {rect:?} vs {viewport:?}"
                        );
                    }
                    if layout.content_used.min.x < layout.content.min.x - 0.5
                        || layout.content_used.max.x > layout.content.max.x + 12.0
                    {
                        let overflow_widgets = harness
                            .root()
                            .children_recursive()
                            .filter_map(|node| {
                                node.accesskit_node().bounding_box()?;
                                let rect = node.rect();
                                (rect.left() < layout.content.min.x - 0.5
                                    || rect.right() > layout.content.max.x + 0.5)
                                    .then(|| {
                                        (
                                            node.accesskit_node().role(),
                                            node.accesskit_node().label(),
                                            rect,
                                        )
                                    })
                            })
                            .collect::<Vec<_>>();
                        panic!(
                            "content exceeds the vertical-scroll clip width at page {page}, state {state_name}, size {size:?}, scale {scale}: {:?} vs {:?}; panel {:?}, config viewport {:?}, overflow widgets {:?}",
                            layout.content_used,
                            layout.content,
                            layout.config_panel,
                            layout.config_scroll_viewport,
                            overflow_widgets
                        );
                    }
                    for action in layout.action_widgets[..layout.action_widget_count]
                        .iter()
                        .flatten()
                    {
                        assert!(
                            rect_contains_rect(layout.action_row, *action)
                                && rect_contains_rect(viewport, *action),
                            "primary action is clipped at page {page}, state {state_name}, size {size:?}, scale {scale}, theme {theme_mode:?}, language {language:?}: {action:?} vs {:?}",
                            layout.action_row
                        );
                    }
                    for (name, widget, parent) in [
                        ("next", layout.nav_next, layout.bottom_bar),
                        ("back", layout.nav_back, layout.bottom_bar),
                        (
                            "terms acceptance",
                            layout.terms_checkbox,
                            if page == 3 {
                                layout.action_row
                            } else {
                                layout.content_scroll_viewport.unwrap_or(layout.content)
                            },
                        ),
                    ] {
                        if let Some(widget) = widget {
                            assert!(
                                rect_contains_rect(parent, widget),
                                "{name} control is initially clipped at page {page}, state {state_name}, size {size:?}, scale {scale}, theme {theme_mode:?}, language {language:?}: {widget:?} vs {parent:?}"
                            );
                        }
                    }
                    if let Some(add_mod) = layout.config_add_mod {
                        assert!(
                            rect_contains_rect(layout.content_used, add_mod),
                            "add-mod control is outside scrollable page content at size {size:?}, scale {scale}: {add_mod:?} vs {:?}",
                            layout.content_used
                        );
                    }
                    if let Some(expected) = expected_bottom {
                        assert_eq!(
                            layout.bottom_bar, expected,
                            "bottom-bar rect changed at page {page}, state {state_name}, size {size:?}, scale {scale}"
                        );
                    } else {
                        expected_bottom = Some(layout.bottom_bar);
                    }
                }
            }
        }
    }

    #[test]
    fn configuration_tabs_text_fit_at_high_scale() {
        let appearances = [
            (ThemeMode::Dark, Language::Chinese),
            (ThemeMode::Dark, Language::English),
            (ThemeMode::Light, Language::Chinese),
            (ThemeMode::Light, Language::English),
        ];
        for (theme_mode, language) in appearances {
            for tab in 0..=2 {
                let state = if tab == 2 {
                    ScreenshotState::UnsupportedMod
                } else {
                    ScreenshotState::Normal
                };
                let mut harness = egui_kittest::Harness::builder()
                    .with_size(Vec2::new(800.0, 540.0))
                    .with_pixels_per_point(1.0)
                    .with_context_setup(|context| context.set_zoom_factor(2.0))
                    .with_render_cursor(false)
                    .build_eframe(|creation| {
                        let mut app = BuilderApp::with_state(
                            &creation.egui_ctx,
                            language,
                            theme_mode,
                            4,
                            false,
                            state,
                        );
                        app.config.scale = 2.0;
                        app.config_tab = tab;
                        app.config.reduce_motion = true;
                        app
                    });
                harness.set_size(Vec2::new(400.0, 270.0));
                harness.run();
                assert_audited_text_fits_clip(&harness, 4, "config-tab", [800, 540], 2.0, language);
            }
        }
    }

    fn theme_slug(theme: ScreenTheme) -> &'static str {
        match theme {
            ScreenTheme::Dark => "dark",
            ScreenTheme::Light => "light",
        }
    }

    #[test]
    fn keyboard_shortcuts_and_tab_focus_work() {
        let mut harness = egui_kittest::Harness::builder()
            .with_size(Vec2::new(800.0, 540.0))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                BuilderApp::new(
                    &creation.egui_ctx,
                    Language::English,
                    ThemeMode::Dark,
                    2,
                    false,
                )
            });
        harness.key_press(egui::Key::Tab);
        harness.run();
        assert!(harness.ctx.memory(|memory| memory.focused()).is_some());
        harness.key_press_modifiers(egui::Modifiers::CTRL, egui::Key::ArrowRight);
        harness.run();
        assert_eq!(harness.state().page, 3);
        harness.key_press_modifiers(egui::Modifiers::ALT, egui::Key::ArrowLeft);
        harness.run();
        assert_eq!(harness.state().page, 2);
        harness.get_by_label("Next").focus();
        harness.run();
        harness.key_press(egui::Key::Enter);
        harness.run();
        assert_eq!(harness.state().page, 3);
        harness.key_press_modifiers(egui::Modifiers::CTRL, egui::Key::ArrowRight);
        harness.run();
        assert_eq!(harness.state().page, 3);
    }

    #[test]
    fn version_card_is_keyboard_activatable_as_one_control() {
        let mut harness = egui_kittest::Harness::builder()
            .with_size(Vec2::new(800.0, 540.0))
            .with_render_cursor(false)
            .build_eframe(|creation| {
                let mut app = BuilderApp::new(
                    &creation.egui_ctx,
                    Language::English,
                    ThemeMode::Dark,
                    1,
                    false,
                );
                app.doctor_report = Some(failed_hash_report());
                app
            });
        harness.run();
        harness.get_by_label("26.3 Detected").focus();
        harness.run();
        harness.key_press(egui::Key::Enter);
        harness.run();
        assert!(
            !harness
                .state()
                .selected_versions
                .iter()
                .any(|version| version == "26.3")
        );
    }

    #[test]
    fn every_visible_interactive_widget_is_reachable_by_tab_on_each_page() {
        let cases = [
            (1, 0, ScreenshotState::Normal),
            (2, 0, ScreenshotState::Normal),
            (3, 0, ScreenshotState::Normal),
            (4, 0, ScreenshotState::Normal),
            (4, 1, ScreenshotState::Normal),
            (4, 2, ScreenshotState::UnsupportedMod),
            (5, 0, ScreenshotState::Normal),
            (5, 0, ScreenshotState::Running),
            (6, 0, ScreenshotState::BadgeGreen),
        ];
        for (page, tab, state) in cases {
            let mut harness = egui_kittest::Harness::builder()
                .with_size(Vec2::new(800.0, 540.0))
                .with_render_cursor(false)
                .build_eframe(|creation| {
                    let mut app = BuilderApp::with_state(
                        &creation.egui_ctx,
                        Language::English,
                        ThemeMode::Dark,
                        page,
                        false,
                        state,
                    );
                    app.config_tab = tab;
                    app.config.reduce_motion = true;
                    app
                });
            harness.run();
            let expected = visible_input_ids(&harness);
            assert!(!expected.is_empty(), "page {page}, tab {tab} has no inputs");
            let mut reached = BTreeSet::new();
            for _ in 0..expected.len() + 1 {
                harness.key_press(egui::Key::Tab);
                harness.run();
                if let Some(id) = focused_input_id(&harness) {
                    reached.insert(id);
                }
            }
            assert_eq!(
                reached, expected,
                "Tab could not reach every input on page {page}, tab {tab} ({state:?})"
            );
        }
    }
}
