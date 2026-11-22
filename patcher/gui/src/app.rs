use std::collections::{BTreeSet, VecDeque};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use eframe::egui::{
    self, Align, Button, CollapsingHeader, ComboBox, CornerRadius, Frame, Layout, Rect, RichText,
    ScrollArea, Stroke, TextStyle, Theme, Ui, UiBuilder, Vec2,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::desktop::{self, Desktop};
use crate::i18n::Language;
use crate::icons::{self, Icon};
use crate::patcher_client::{
    BuildLogLine, BuildProgress, BuildRequest, BuildSettings, BuildTask, CheckState, DoctorReport,
    DoctorTask, HashComparison, HashState, MockBackend, PatcherBackend, PatcherClient,
    ProcessControl, ToolInstallTask, ToolPaths, ToolStatus, VerifyGroup, VerifyGroupResult,
    VerifyProgress, VerifyReport, VerifyTask, VersionBuildResult, mock_tool_statuses,
};
use crate::terms_acceptance;
use crate::theme::{self, Palette, ThemeMode};
use crate::ui_scale::{self, UiScale};

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
    TermsPreAccepted,
    VerifyOk,
    VerifyRunning,
    VerifyFailedIndex,
    ResourceConfig,
    ModConfig,
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
    BuildSuccessThree,
    ToolchainField,
    PreflightError,
    ToolsMissing,
    ToolsOptional,
    ToolsStrict,
    ToolsPythonExe,
    ToolsDownloading,
    ToolsFailed,
    ToolsReady,
    HostToolsMissing,
    HostToolsMismatch,
    HostToolsReady,
    ShaderVerified,
    ShaderMissing,
    ShaderMissingScrolled,
    ShaderTampered,
    MavenPartial,
    MavenReady,
    DiagnosticsChinese,
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
    completion_artifact_labels: [Option<Rect>; 3],
    completion_artifact_paths: [Option<Rect>; 3],
    completion_artifact_open_buttons: [Option<Rect>; 3],
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
            completion_artifact_labels: [None; 3],
            completion_artifact_paths: [None; 3],
            completion_artifact_open_buttons: [None; 3],
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
    ui_scale: UiScale,
    reduce_motion: bool,
    strict_kernel: bool,
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
            ui_scale: UiScale::Auto,
            reduce_motion: system_reduce_motion(),
            strict_kernel: false,
        }
    }
}

type DpiResult = Arc<OnceLock<Result<(), String>>>;

#[derive(Debug, Default)]
pub struct DisplayOptions {
    pub work: Option<PathBuf>,
    pub scale: Option<f32>,
}

impl DisplayOptions {
    fn work_dir(&self) -> PathBuf {
        self.work.clone().unwrap_or_else(work_dir)
    }

    fn zoom(&self, settings: &desktop::Settings) -> StartupZoom {
        if let Some(factor) = self.scale {
            let choice = UiScale::for_factor(factor);
            StartupZoom {
                choice: choice.unwrap_or(UiScale::P100),
                custom: choice.is_none().then_some(factor),
                source: "cli",
            }
        } else {
            StartupZoom {
                choice: settings.zoom.unwrap_or_default(),
                custom: None,
                source: if settings.zoom.is_some() {
                    "settings"
                } else {
                    "default"
                },
            }
        }
    }
}

#[derive(Clone, Copy)]
struct StartupZoom {
    choice: UiScale,
    custom: Option<f32>,
    source: &'static str,
}

impl StartupZoom {
    fn factor(self) -> f32 {
        self.custom.unwrap_or_else(|| self.choice.factor())
    }
}

/// Mock, read-only preview using the native WGPU window, not the test harness.
pub struct DpiPreview {
    pub path: PathBuf,
    pub hold: bool,
    pub completion: DpiResult,
    pub page: usize,
    pub theme: ScreenTheme,
    pub language: Language,
    pub state: String,
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
    live_verification: bool,
    verification: Option<VerificationRun>,
    verification_input_focused: bool,
    show_official_check: bool,
    tools_task: Option<ToolInstallTask>,
    tools_confirm: bool,
    tools_error: Option<String>,
    tools_progress_pct: u8,
    tools_simulated_downloading: bool,
    terms_scrolled: bool,
    terms_checked: bool,
    terms_hash: String,
    terms_record_path: Option<PathBuf>,
    terms_accepted_at: Option<u64>,
    terms_error: Option<&'static str>,
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
    tool_path_settings: ToolPathSettings,
    tool_settings_file: Option<PathBuf>,
    tool_settings_save_failed: bool,
    tool_check_at: Option<Instant>,
    minecraft_dir: String,
    output_bytes: u64,
    repro_status: ReproStatus,
    cancel_confirm: bool,
    cleanup_confirm: bool,
    cleanup_candidates: Vec<PathBuf>,
    mod_settings_open: Option<usize>,
    about_open: bool,
    theme_applied: Option<(ThemeMode, Option<Theme>, f32)>,
    effective_zoom: f32,
    custom_zoom: Option<f32>,
    zoom_source: &'static str,
    work_dir: PathBuf,
    compact_rail: bool,
    font_sources: Vec<String>,
    desktop: Option<Desktop>,
    dpi_preview: Option<DpiPreview>,
    read_only_preview: bool,
    dpi_watch: Option<(Instant, DpiResult)>,
    layout_rects: LayoutRects,
    content_has_focus: bool,
    page_scroll_offset: f32,
    scroll_page: usize,
}

#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(default)]
struct ToolPathSettings {
    toolchain_dir: String,
    shader_toolchain: String,
}

impl ToolPathSettings {
    fn paths(&self) -> ToolPaths {
        let path = |text: &str| (!text.trim().is_empty()).then(|| PathBuf::from(text.trim()));
        ToolPaths {
            toolchain_dir: path(&self.toolchain_dir),
            shader_toolchain: path(&self.shader_toolchain),
        }
    }

    fn load(file: &Path) -> Self {
        fs::read(file)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    fn save(&self, file: &Path) -> io::Result<()> {
        desktop::write_json(file, self)
    }
}

#[derive(PartialEq, Eq)]
struct VerificationInputs {
    versions: Vec<String>,
    minecraft_dir: Option<PathBuf>,
    mappings: Option<PathBuf>,
}

struct VerificationRun {
    inputs: VerificationInputs,
    reports: Vec<VerifyReport>,
    active_groups: Vec<VerifyGroupResult>,
    progress: Option<VerifyProgress>,
    task: Option<VerifyTask>,
    error: bool,
    simulated_running: bool,
}

impl VerificationRun {
    fn new(inputs: VerificationInputs) -> Self {
        Self {
            inputs,
            reports: Vec::new(),
            active_groups: Vec::new(),
            progress: None,
            task: None,
            error: false,
            simulated_running: false,
        }
    }

    fn start_next(&mut self) {
        self.active_groups.clear();
        self.progress = None;
        if let Some(version) = self.inputs.versions.get(self.reports.len()) {
            self.task = Some(
                PatcherClient.start_verify(
                    version.clone(),
                    self.inputs.minecraft_dir.clone(),
                    self.inputs
                        .mappings
                        .clone()
                        .filter(|_| version == "1.21.11"),
                ),
            );
        }
    }

    fn running(&self) -> bool {
        self.task.is_some() || self.simulated_running
    }

    fn passed(&self) -> bool {
        !self.error
            && !self.running()
            && self.reports.len() == self.inputs.versions.len()
            && !self.reports.is_empty()
            && self.reports.iter().all(VerifyReport::passed)
    }

    fn has_failures(&self) -> bool {
        self.reports.iter().any(|report| !report.passed())
            || self.active_groups.iter().any(|group| !group.passed())
    }
}

impl Drop for VerificationRun {
    fn drop(&mut self) {
        if let Some(task) = &self.task {
            task.control.cancel();
        }
    }
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
            run_live_doctor.then(work_dir),
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
            None,
            screenshot_state,
            false,
        )
    }

    fn with_state_font_mode(
        context: &egui::Context,
        language: Language,
        theme_mode: ThemeMode,
        page: usize,
        live_work_dir: Option<PathBuf>,
        screenshot_state: ScreenshotState,
        use_system_fonts: bool,
    ) -> Self {
        let run_live_doctor = live_work_dir.is_some();
        let font_sources = install_cjk_font(context, use_system_fonts);
        ui_scale::configure_input(context);
        theme::configure_text(context);
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
            live_verification: run_live_doctor,
            verification: None,
            verification_input_focused: false,
            show_official_check: run_live_doctor
                || matches!(
                    screenshot_state,
                    ScreenshotState::VerifyOk
                        | ScreenshotState::VerifyRunning
                        | ScreenshotState::VerifyFailedIndex
                ),
            tools_task: None,
            tools_confirm: false,
            tools_error: None,
            tools_progress_pct: 0,
            tools_simulated_downloading: false,
            terms_scrolled: false,
            terms_checked: false,
            terms_hash,
            terms_record_path: None,
            terms_accepted_at: None,
            terms_error: None,
            config_confirmed: false,
            config_tab: match screenshot_state {
                ScreenshotState::ResourceConfig => 1,
                ScreenshotState::ModConfig
                | ScreenshotState::UnsupportedMod
                | ScreenshotState::ModsScrolled => 2,
                _ => 0,
            },
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
            tool_path_settings: ToolPathSettings::default(),
            tool_settings_file: None,
            tool_settings_save_failed: false,
            tool_check_at: None,
            minecraft_dir: String::new(),
            output_bytes: 0,
            repro_status: ReproStatus::Unverified,
            cancel_confirm: false,
            cleanup_confirm: false,
            cleanup_candidates: Vec::new(),
            mod_settings_open: None,
            about_open: false,
            theme_applied: None,
            effective_zoom: 1.0,
            custom_zoom: None,
            zoom_source: "default",
            work_dir: live_work_dir.unwrap_or_else(work_dir),
            compact_rail: false,
            font_sources,
            desktop: None,
            dpi_preview: None,
            read_only_preview: false,
            dpi_watch: None,
            layout_rects: LayoutRects::default(),
            content_has_focus: false,
            page_scroll_offset: 0.0,
            scroll_page: page,
        };
        if screenshot_state == ScreenshotState::TermsPreAccepted {
            app.restore_terms_acceptance(Some(1_795_000_000));
        }
        if run_live_doctor {
            let file = app.work_dir.join("tool-paths.json");
            app.tool_path_settings = ToolPathSettings::load(&file);
            app.tool_settings_file = Some(file);
            app.start_doctor();
        } else if let Ok(report) = MockBackend.doctor() {
            app.doctor_report = Some(report);
        }
        if matches!(
            screenshot_state,
            ScreenshotState::MavenPartial | ScreenshotState::MavenReady
        ) {
            app.selected_versions = vec!["26.2".to_owned()];
        }
        if !run_live_doctor {
            app.mock_verification(screenshot_state);
        }
        if let Some(report) = app.doctor_report.as_mut() {
            if matches!(
                screenshot_state,
                ScreenshotState::ToolsOptional | ScreenshotState::ToolsStrict
            ) {
                app.config.strict_kernel = screenshot_state == ScreenshotState::ToolsStrict;
                report.tools = mock_tool_statuses(app.config.strict_kernel);
                for tool in &mut report.tools {
                    if matches!(
                        tool.id.as_str(),
                        "rust-cargo" | "wasm32-target" | "cargo-cache"
                    ) {
                        tool.state = "missing".to_owned();
                    }
                }
            } else if screenshot_state == ScreenshotState::ToolsPythonExe {
                report.tools.sort_by_key(|tool| tool.id != "python");
                if let Some(python) = report.tools.first_mut() {
                    python.resolved_command = Some("python.exe".to_owned());
                }
            }
            let tool_state = match screenshot_state {
                ScreenshotState::ToolsMissing => Some("missing"),
                ScreenshotState::ToolsDownloading => Some("missing"),
                ScreenshotState::ToolsFailed => Some("hash-mismatch"),
                ScreenshotState::ToolsReady => Some("present"),
                _ => None,
            };
            if let Some(state) = tool_state {
                for tool in report.tools.iter_mut().filter(|tool| tool.can_install()) {
                    tool.state = state.to_owned();
                }
            }
            if matches!(
                screenshot_state,
                ScreenshotState::HostToolsMissing
                    | ScreenshotState::HostToolsMismatch
                    | ScreenshotState::HostToolsReady
                    | ScreenshotState::ShaderVerified
                    | ScreenshotState::ShaderMissing
                    | ScreenshotState::ShaderMissingScrolled
                    | ScreenshotState::ShaderTampered
                    | ScreenshotState::MavenPartial
                    | ScreenshotState::MavenReady
            ) {
                report
                    .tools
                    .retain(|tool| tool.id != "remapper" && !tool.id.starts_with("teavm-"));
            }
            for tool in &mut report.tools {
                match (screenshot_state, tool.id.as_str()) {
                    (ScreenshotState::HostToolsMissing, "llvm" | "binaryen") => {
                        tool.state = "missing".to_owned();
                    }
                    (ScreenshotState::HostToolsMismatch, "llvm" | "binaryen") => {
                        tool.state = "hash-mismatch".to_owned();
                    }
                    (
                        ScreenshotState::ShaderMissing | ScreenshotState::ShaderMissingScrolled,
                        "shader-toolchain",
                    ) => {
                        tool.state = "missing-offline".to_owned();
                    }
                    (ScreenshotState::ShaderTampered, "shader-toolchain") => {
                        tool.state = "tampered".to_owned();
                        tool.inventory.differing_files =
                            vec!["gaius-shaderc.wasm".to_owned(), "gaius-spvc.js".to_owned()];
                    }
                    (ScreenshotState::MavenPartial, "maven-repository") => {
                        tool.state = "partial".to_owned();
                        tool.inventory.checked_files = tool
                            .inventory
                            .total_files
                            .map(|total| total.saturating_sub(2));
                        tool.inventory.first_failed_path =
                            Some("org/teavm/teavm-core/0.15.0/teavm-core-0.15.0.jar".to_owned());
                    }
                    _ => {}
                }
            }
        }
        if screenshot_state == ScreenshotState::ToolsDownloading {
            app.tools_simulated_downloading = true;
            app.tools_progress_pct = 43;
        } else if screenshot_state == ScreenshotState::ToolsFailed {
            app.tools_error = Some(app.language.text("doctor.tools.mock_failure").to_owned());
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
            ScreenshotState::BadgeGreen
                | ScreenshotState::BuildSuccess
                | ScreenshotState::BuildSuccessThree
        ) {
            app.mock_completed(ReproStatus::Identical);
            if screenshot_state == ScreenshotState::BuildSuccessThree {
                app.build_results = Self::mock_three_version_results();
            }
        } else if screenshot_state == ScreenshotState::BadgeYellow {
            app.mock_completed(ReproStatus::Custom);
        } else if screenshot_state == ScreenshotState::BadgeRed {
            app.mock_completed(ReproStatus::Diverged);
        } else if screenshot_state == ScreenshotState::BuildFail {
            app.mock_failed(
                app.language.text("build.stage.compile"),
                app.language.text("build.mock.compile_failed"),
            );
        } else if screenshot_state == ScreenshotState::DiagnosticsChinese {
            app.mock_failed(
                "stage: prepare",
                "Failure in C:\\Users\\张三\\桌面\\gaius-work\\26.3",
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

    pub fn native(context: &eframe::CreationContext<'_>, display: &DisplayOptions) -> Self {
        let language = std::env::var("GAIUS_BUILDER_LANG")
            .ok()
            .and_then(|value| Language::parse(&value))
            .unwrap_or(Language::Chinese);
        let mut app = Self::with_state_font_mode(
            &context.egui_ctx,
            language,
            ThemeMode::Dark,
            1,
            Some(display.work_dir()),
            ScreenshotState::Normal,
            true,
        );
        app.init_desktop(context, display, false);
        app.terms_record_path = Some(app.work_dir.join("terms-acceptance.json"));
        app.reload_terms_acceptance();
        app
    }

    pub fn native_preview(
        context: &eframe::CreationContext<'_>,
        preview: DpiPreview,
        display: &DisplayOptions,
    ) -> Self {
        let state = match preview.state.as_str() {
            "config-mods" => ScreenshotState::ModConfig,
            "build-success" => ScreenshotState::BuildSuccess,
            _ => ScreenshotState::Normal,
        };
        let mut app = Self::with_state(
            &context.egui_ctx,
            preview.language,
            preview.theme.mode(),
            preview.page,
            false,
            state,
        );
        app.config.reduce_motion = true;
        app.work_dir = display.work_dir();
        app.init_desktop(context, display, true);
        app.dpi_preview = Some(preview);
        app.read_only_preview = true;
        app
    }

    fn init_desktop(
        &mut self,
        context: &eframe::CreationContext<'_>,
        display: &DisplayOptions,
        read_only: bool,
    ) {
        let path = self.work_dir.join("ui-settings.json");
        let settings = desktop::Settings::load(&path);
        self.apply_startup_zoom(display.zoom(&settings));
        let desktop = Desktop::new(context, settings, (!read_only).then_some(path));
        if let Some(size) = desktop.native_size() {
            context.egui_ctx.options_mut(|options| {
                options.zoom_factor = ui_scale::fit_zoom(self.requested_zoom(), size)
            });
        }
        self.desktop = Some(desktop);
    }

    fn apply_startup_zoom(&mut self, zoom: StartupZoom) {
        self.config.ui_scale = zoom.choice;
        self.custom_zoom = zoom.custom;
        self.zoom_source = zoom.source;
    }

    fn requested_zoom(&self) -> f32 {
        self.custom_zoom
            .unwrap_or_else(|| self.config.ui_scale.factor())
    }

    fn change_zoom(&mut self, zoom: UiScale) {
        self.config.ui_scale = zoom;
        self.custom_zoom = None;
        if let Some(desktop) = &mut self.desktop {
            desktop.update_zoom(zoom);
        }
    }

    fn add_zoom_report(&self, report: &mut serde_json::Value) {
        report["requested_zoom"] = self.requested_zoom().into();
        report["requested_zoom_auto"] = (self.config.ui_scale == UiScale::Auto).into();
        report["effective_zoom"] = self.effective_zoom.into();
        report["zoom_source"] = self.zoom_source.into();
    }

    fn display_diagnostics(&self) -> String {
        self.desktop
            .as_ref()
            .map(|desktop| {
                format!(
                    "{}UI zoom: {:.2} (requested: {:.2})\n",
                    desktop.diagnostics(),
                    self.effective_zoom,
                    self.requested_zoom()
                )
            })
            .unwrap_or_default()
    }

    fn fonts_report(&self, ctx: &egui::Context) -> serde_json::Value {
        ctx.fonts_mut(|fonts| {
            let mut installed: Vec<_> = fonts.definitions().font_data.keys().cloned().collect();
            installed.sort_unstable();
            let body = egui::FontId::proportional(14.0);
            let chinese = fonts.has_glyphs(&body, "高分辨率界面自动缩放构建验证配置条款完成");
            let symbols = fonts.has_glyphs(&body, "→…·⚙");
            let mut discovered: Vec<_> = fonts
                .discovered_fonts()
                .iter()
                .map(|font| &font.name)
                .collect();
            discovered.sort_unstable();
            serde_json::json!({
                "configured_faces": installed,
                "runtime_fallback_sources": self.font_sources,
                "discovered_faces": discovered,
                "cjk_probe_has_glyphs": chinese,
                "symbol_probe_has_glyphs": symbols,
            })
        })
    }

    fn request_dpi_report(&mut self, ctx: &egui::Context, window: crate::dpi::WindowSnapshot) {
        let Some(preview) = self.dpi_preview.take() else {
            return;
        };
        let mut report = desktop::window_json(window);
        report["schema"] = "gaius-builder-dpi-v1".into();
        report["zoom_factor"] = self.effective_zoom.into();
        self.add_zoom_report(&mut report);
        report["effective_pixels_per_point"] = ctx.pixels_per_point().into();
        report["egui_native_pixels_per_point"] = serde_json::json!(ctx.native_pixels_per_point());
        report["fonts_used"] = self.fonts_report(ctx);
        report["page"] = preview.page.into();
        report["state"] = preview.state.clone().into();
        report["read_only_preview"] = true.into();
        self.dpi_watch = Some((
            Instant::now() + Duration::from_secs(10),
            preview.completion.clone(),
        ));
        let context = ctx.clone();
        ctx.request_screenshot(move |image| {
            report["framebuffer_size_physical_px"] = serde_json::json!(image.size);
            let result = desktop::write_json(&preview.path, &report).map_err(|error| {
                format!(
                    "could not write DPI report {}: {error}",
                    preview.path.display()
                )
            });
            let close = !preview.hold || result.is_err();
            let delivered = preview.completion.set(result).is_ok();
            if close && delivered {
                context.send_viewport_cmd(egui::ViewportCommand::Close);
            }
        });
    }

    fn invalidate_tool_check(&mut self) {
        if let Some(control) = self.doctor_control.take() {
            control.cancel();
        }
        self.doctor_receiver = None;
        self.doctor_report = None;
        self.doctor_error = None;
        self.doctor_cancelled = false;
        self.tools_confirm = false;
    }

    fn start_doctor(&mut self) {
        if self.tools_task.is_some() {
            return;
        }
        self.invalidate_tool_check();
        self.tool_check_at = None;
        let task =
            PatcherClient.start_doctor(self.tool_path_settings.paths(), self.config.strict_kernel);
        self.doctor_control = Some(task.control.clone());
        self.doctor_receiver = Some(task);
    }

    fn set_tool_path_settings(&mut self, settings: ToolPathSettings) {
        if self.tools_task.is_some() || settings == self.tool_path_settings {
            return;
        }
        self.tool_path_settings = settings;
        self.tool_settings_save_failed = self
            .tool_settings_file
            .as_deref()
            .is_some_and(|file| self.tool_path_settings.save(file).is_err());
        self.invalidate_tool_check();
        self.tools_error = None;
        if self.live_verification {
            self.tool_check_at = Some(Instant::now() + Duration::from_millis(400));
        }
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

    fn verification_inputs(&self) -> VerificationInputs {
        let path = |text: &str| (!text.trim().is_empty()).then(|| PathBuf::from(text.trim()));
        VerificationInputs {
            versions: self.selected_versions.clone(),
            minecraft_dir: path(&self.minecraft_dir),
            mappings: self
                .selected_versions
                .iter()
                .any(|version| version == "1.21.11")
                .then(|| path(&self.config.mappings_path))
                .flatten(),
        }
    }

    fn mock_verification(&mut self, state: ScreenshotState) {
        let inputs = self.verification_inputs();
        let mut run = VerificationRun::new(inputs);
        if state == ScreenshotState::VerifyRunning {
            run.active_groups = VerifyReport::mock_ok().groups.into_iter().take(3).collect();
            run.progress = Some(VerifyProgress::mock_running());
            run.simulated_running = true;
        } else {
            run.reports = run
                .inputs
                .versions
                .iter()
                .map(|_| {
                    if state == ScreenshotState::VerifyFailedIndex {
                        VerifyReport::mock_failed_index()
                    } else {
                        VerifyReport::mock_ok()
                    }
                })
                .collect();
        }
        self.verification = Some(run);
    }

    fn start_verification(&mut self) {
        self.verification = None;
        if self.live_verification {
            let mut run = VerificationRun::new(self.verification_inputs());
            run.start_next();
            self.verification = Some(run);
        } else {
            self.mock_verification(ScreenshotState::VerifyOk);
        }
    }

    fn poll_verification(&mut self) {
        if self.current_verification().is_none() {
            self.verification = None;
        }
        if self.live_verification
            && self.page == 2
            && !self.verification_input_focused
            && self.current_verification().is_none()
        {
            self.start_verification();
        }
        let Some(run) = &mut self.verification else {
            return;
        };
        let Some(task) = &run.task else {
            return;
        };
        if let Some(progress) = task.progress.try_iter().last() {
            run.progress = Some(progress);
        }
        run.active_groups.extend(task.completions.try_iter());
        match task.receiver.try_recv() {
            Ok(Ok(report)) => {
                run.task = None;
                run.reports.push(report);
                run.start_next();
            }
            Ok(Err(_)) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                run.task = None;
                run.error = true;
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => {}
        }
    }

    fn current_verification(&self) -> Option<&VerificationRun> {
        self.verification
            .as_ref()
            .filter(|run| run.inputs == self.verification_inputs())
    }

    fn verification_passed(&self) -> bool {
        self.current_verification()
            .is_some_and(VerificationRun::passed)
    }

    fn start_tools_install(&mut self, ids: Vec<String>) {
        let ids: BTreeSet<_> = ids.into_iter().collect();
        if ids.is_empty() || self.tools_task.is_some() {
            return;
        }
        self.tools_error = None;
        self.tools_progress_pct = 0;
        self.tools_confirm = false;
        self.tools_task = Some(PatcherClient.start_tools_install(
            ids.into_iter().collect(),
            self.tool_path_settings.paths(),
            self.config.strict_kernel,
        ));
    }

    fn poll_tools_install(&mut self) {
        let mut terminal = None;
        if let Some(task) = &self.tools_task {
            while let Ok(progress) = task.progress.try_recv() {
                self.tools_progress_pct = progress.pct;
                if let Some(error) = progress.error {
                    self.tools_error = Some(error);
                }
            }
            match task.receiver.try_recv() {
                Ok(result) => terminal = Some(result),
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    terminal = Some(Err(crate::patcher_client::PatcherError(
                        "tool installer stopped unexpectedly".to_owned(),
                    )))
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => {}
            }
        }
        if let Some(result) = terminal {
            self.tools_task = None;
            match result {
                Ok(tools) => {
                    if let Some(report) = self.doctor_report.as_mut() {
                        report.tools = tools;
                    }
                }
                Err(error) => self.tools_error = Some(error.to_string()),
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
        let compact = self.effective_zoom >= 1.5 || width < 660.0;
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
                                RichText::new("G")
                                    .strong()
                                    .color(palette.accent)
                                    .size(theme::snap(ui, 20.0)),
                            );
                            let mut content = logo.rect;
                            if width >= 180.0 {
                                let name = ui.label(
                                    RichText::new(self.language.text(if compact {
                                        "app.short_name"
                                    } else {
                                        "app.name"
                                    }))
                                    .strong()
                                    .size(theme::snap(ui, if compact { 14.0 } else { 16.0 })),
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
                                            self.scale_choices(ui);
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
        if self.language != current_language {
            self.reload_terms_acceptance();
        }
        let scale = ComboBox::from_id_salt("ui-scale")
            .selected_text(match self.custom_zoom {
                Some(zoom) => format!("{}%", zoom * 100.0),
                None => self.language.text(self.config.ui_scale.label()).to_owned(),
            })
            .width(if compact { 58.0 } else { 70.0 })
            .show_ui(ui, |ui| self.scale_choices(ui))
            .response
            .on_hover_text(self.language.text("scale.tip"));
        let mut controls = theme.rect.union(language.rect).union(scale.rect);
        if !compact {
            controls = controls.union(ui.label(self.language.text("scale.label")).rect);
        }
        controls
    }

    fn scale_choices(&mut self, ui: &mut Ui) {
        for scale in UiScale::VALUES {
            if ui
                .selectable_label(
                    self.custom_zoom.is_none() && self.config.ui_scale == scale,
                    self.language.text(scale.label()),
                )
                .clicked()
            {
                self.change_zoom(scale);
                ui.close();
            }
        }
    }

    fn render_steps(&mut self, ui: &mut Ui, palette: Palette) -> Rect {
        let row_height = if self.compact_rail {
            28.0
        } else if self.effective_zoom >= 1.45 {
            26.0
        } else {
            38.0
        };
        let row_gap = if self.effective_zoom >= 1.45 {
            2.0
        } else {
            4.0
        };
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
                        if !self.compact_rail {
                            ui.label(
                                RichText::new(self.language.text("step.header"))
                                    .size(theme::snap(ui, 12.0))
                                    .color(palette.secondary),
                            );
                            ui.add_space(8.0);
                        }
                        for (index, key) in STEP_KEYS.iter().enumerate() {
                            let step = index + 1;
                            let current = self.page == step;
                            let done = self.step_complete(step);
                            let visited = step <= self.page;
                            let step_label = if !self.compact_rail && self.effective_zoom >= 1.5 {
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
                            if self.compact_rail {
                                let label_x = if done {
                                    text_pos.center().x - 5.0
                                } else {
                                    text_pos.center().x
                                };
                                if done {
                                    icons::paint(
                                        ui.painter(),
                                        Rect::from_center_size(
                                            egui::pos2(text_pos.right() - 8.0, text_pos.center().y),
                                            Vec2::splat(10.0),
                                        ),
                                        icon,
                                        icon_color,
                                    );
                                }
                                ui.painter().text(
                                    egui::pos2(label_x, text_pos.center().y),
                                    egui::Align2::CENTER_CENTER,
                                    format!("{step:02}"),
                                    egui::FontId::monospace(theme::snap(ui, 14.0)),
                                    text_color,
                                );
                            } else {
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
                                    egui::FontId::monospace(theme::snap(ui, 14.0)),
                                    text_color,
                                );
                                ui.painter().text(
                                    egui::pos2(text_pos.left() + 62.0, text_pos.center().y),
                                    egui::Align2::LEFT_CENTER,
                                    step_label,
                                    egui::FontId::proportional(theme::snap(ui, 14.0)),
                                    text_color,
                                );
                            }
                            theme::focus_ring(ui, &response, palette.accent);
                            let response = response.on_hover_text(&label);
                            if response.gained_focus() {
                                response.scroll_to_me(Some(Align::Center));
                            }
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
                        theme::scroll_end_padding(ui);
                    });
            })
            .response
            .rect
    }

    fn step_complete(&self, step: usize) -> bool {
        match step {
            1 => !self.selected_versions.is_empty(),
            2 => self.check_page_ready(),
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

    fn check_page_ready(&self) -> bool {
        self.tools_ready_for_selection() && self.verification_passed()
    }

    fn build_gate(&self) -> Option<&'static str> {
        if !self.tools_ready_for_selection() {
            Some("doctor.tools.blocked")
        } else if !self.verification_passed() {
            Some("verify.blocked")
        } else if !self.terms_checked {
            Some("build.accept_terms")
        } else {
            None
        }
    }

    fn tools_ready_for_selection(&self) -> bool {
        self.tools_task.is_none()
            && self.doctor_report.as_ref().is_some_and(|report| {
                !report.tools.is_empty()
                    && report
                        .tools
                        .iter()
                        .all(|tool| !tool.blocks(&self.selected_versions))
            })
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
                            ui.label(RichText::new(*id).strong().size(theme::snap(ui, 16.0)));
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
                                                .size(theme::snap(ui, 12.0))
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
                            .size(theme::snap(ui, 12.0))
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
            theme::focus_ring(ui, &response, palette.accent);
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

    fn render_build_tools(&mut self, ui: &mut Ui, palette: Palette) {
        let Some(report) = &self.doctor_report else {
            return;
        };
        let previous_row_spacing = ui.spacing().item_spacing.y;
        ui.spacing_mut().item_spacing.y = 3.0;
        let tools = report.tools.clone();
        let mut seen = BTreeSet::new();
        let missing: Vec<_> = tools
            .iter()
            .filter(|tool| tool.can_install() && tool.blocks(&self.selected_versions))
            .filter(|tool| seen.insert(tool.id.as_str()))
            .collect();
        ui.label(RichText::new(self.language.text("doctor.tools")).strong());
        if self.config.strict_kernel {
            ui.label(RichText::new(self.language.text("config.strict_kernel")).strong());
        }
        if self.tools_task.is_some() || self.tools_simulated_downloading {
            ui.add(
                egui::ProgressBar::new(f32::from(self.tools_progress_pct) / 100.0)
                    .text(format!("{}%", self.tools_progress_pct)),
            );
            ui.horizontal_wrapped(|ui| {
                ui.label(self.language.text("doctor.tools.downloading"));
                if let Some(pid) = self.tools_task.as_ref().and_then(|task| task.control.pid()) {
                    ui.label(format!("PID {pid}"));
                }
                let cancel = ui.button(self.language.text("doctor.tools.cancel"));
                keep_focused_visible(&mut self.content_has_focus, cancel.clone());
                if cancel.clicked() {
                    if let Some(task) = &self.tools_task {
                        task.control.cancel();
                    }
                    self.tools_simulated_downloading = false;
                }
            });
        } else if !missing.is_empty() && self.tools_confirm {
            ui.label(self.language.text("doctor.tools.confirm"));
            for tool in &missing {
                let name = if tool.id == "maven-repository" {
                    self.language.text("doctor.maven")
                } else {
                    &tool.display_name
                };
                let size = human_bytes(tool.bytes);
                let source = if tool.source_host.is_empty() {
                    String::new()
                } else {
                    self.language
                        .text("doctor.tools.source")
                        .replace("{value}", &tool.source_host)
                };
                ui.label(
                    [name, &size, &source]
                        .into_iter()
                        .filter(|text| !text.is_empty())
                        .collect::<Vec<_>>()
                        .join(" · "),
                );
            }
            ui.horizontal_wrapped(|ui| {
                let confirm = ui.button(self.language.text("doctor.tools.confirm_download"));
                keep_focused_visible(&mut self.content_has_focus, confirm.clone());
                if confirm.clicked() {
                    self.start_tools_install(missing.iter().map(|tool| tool.id.clone()).collect());
                }
                let dismiss = ui.button(self.language.text("doctor.tools.dismiss"));
                keep_focused_visible(&mut self.content_has_focus, dismiss.clone());
                if dismiss.clicked() {
                    self.tools_confirm = false;
                }
            });
        } else if !missing.is_empty() {
            let key = if self.tools_error.is_some() {
                "doctor.tools.retry"
            } else {
                "doctor.tools.download"
            };
            let download = ui
                .add(Button::new(self.language.text(key)).stroke(Stroke::new(1.0, palette.accent)));
            keep_focused_visible(&mut self.content_has_focus, download.clone());
            if download.clicked() {
                self.tools_confirm = true;
            }
        }
        if let Some(error) = &self.tools_error {
            CollapsingHeader::new(self.language.text("doctor.tools.failure"))
                .default_open(true)
                .show(ui, |ui| {
                    ui.label(RichText::new(error).color(palette.error));
                    ui.label(self.language.text("doctor.tools.failure_hint"));
                });
        }
        // Keep the two Wasm host tools together above the longer prerequisite list.
        for tool in tools
            .iter()
            .filter(|tool| matches!(tool.id.as_str(), "llvm" | "binaryen"))
        {
            self.render_tool_row(ui, palette, tool);
        }
        for present in [false, true] {
            for tool in tools
                .iter()
                .filter(|tool| {
                    !matches!(
                        tool.id.as_str(),
                        "llvm" | "binaryen" | "shader-toolchain" | "maven-repository"
                    )
                })
                .filter(|tool| tool.ready() == present)
            {
                self.render_tool_row(ui, palette, tool);
            }
        }
        ui.spacing_mut().item_spacing.y = previous_row_spacing;
    }

    fn render_tool_row(&self, ui: &mut Ui, palette: Palette, tool: &ToolStatus) {
        if tool.id == "emscripten-sdk" {
            ui.horizontal_wrapped(|ui| {
                ui.label(format!(
                    "{} · {}",
                    self.language.text("doctor.emscripten"),
                    self.language.text("doctor.tools.optional"),
                ));
                if !tool.inventory.sdk_platforms.is_empty() {
                    ui.label(
                        RichText::new(tool.inventory.sdk_platforms.join(", "))
                            .color(palette.secondary),
                    );
                }
                if let Some(command) = &tool.resolved_command {
                    ui.label(RichText::new(command).monospace().color(palette.secondary));
                }
            });
            return;
        }
        let (icon, color, state_key) = match tool.state.as_str() {
            "present" => (Icon::Check, palette.accent, "item.ok"),
            "hash-mismatch" => (Icon::Cross, palette.error, "doctor.tools.hash_mismatch"),
            "unsupported" => (Icon::Ring, palette.warning, "doctor.tools.unsupported"),
            _ => (Icon::Cross, palette.warning, "doctor.tools.missing"),
        };
        let status = self.language.text(state_key);
        let mut label = RichText::new(format!("{} · {status}", tool.display_name)).strong();
        if !tool.required {
            label = label.size(theme::snap(ui, 12.0));
        }
        ui.horizontal_wrapped(|ui| {
            icons::show(ui, icon, color, 14.0);
            if !tool.required {
                Frame::new()
                    .fill(palette.panel)
                    .stroke(Stroke::new(1.0, palette.stroke))
                    .corner_radius(CornerRadius::same(4))
                    .inner_margin(egui::Margin::symmetric(5, 1))
                    .show(ui, |ui| {
                        ui.add(
                            egui::Label::new(
                                RichText::new(self.language.text("doctor.tools.optional"))
                                    .size(theme::snap(ui, 12.0))
                                    .color(palette.secondary),
                            )
                            .extend(),
                        );
                    });
            }
            if tool.hint.is_empty() {
                ui.label(label);
            } else {
                CollapsingHeader::new(label)
                    .id_salt(&tool.id)
                    .show(ui, |ui| {
                        ui.label(self.language.text(if tool.installable {
                            "doctor.tools.lock_hint"
                        } else {
                            "doctor.tools.manual_hint"
                        }));
                        ui.label(&tool.hint);
                        if !tool.source_url.is_empty() {
                            ui.label(&tool.source_url);
                        }
                    });
            }
            if let Some(command) = &tool.resolved_command {
                ui.label(RichText::new(command).monospace().color(palette.text));
            }
            if tool.bytes > 0 {
                ui.label(RichText::new(human_bytes(tool.bytes)).color(palette.secondary));
            }
            if tool.installable && !tool.source_host.is_empty() {
                ui.label(RichText::new(&tool.source_host).color(palette.secondary));
            }
        });
    }

    fn render_local_tool_reports(&mut self, ui: &mut Ui, palette: Palette) {
        let Some(report) = &self.doctor_report else {
            return;
        };
        let mut local_tools: Vec<_> = report
            .tools
            .iter()
            .filter(|tool| {
                (tool.id == "maven-repository"
                    && tool
                        .required_for
                        .iter()
                        .any(|v| self.selected_versions.contains(v)))
                    || (tool.id == "shader-toolchain"
                        && self.selected_versions.iter().any(|v| v == "26.3"))
            })
            .cloned()
            .collect();
        local_tools.sort_by_key(|tool| (tool.ready(), tool.id != "shader-toolchain"));
        for tool in local_tools {
            let (title, state) = match (tool.id.as_str(), tool.state.as_str()) {
                ("maven-repository", "present") => ("doctor.maven", "doctor.tools.verified"),
                ("maven-repository", "partial") => ("doctor.maven", "doctor.tools.partial"),
                ("maven-repository", "missing") => ("doctor.maven", "doctor.tools.missing"),
                ("maven-repository", "hash-mismatch") => {
                    ("doctor.maven", "doctor.tools.hash_mismatch")
                }
                ("maven-repository", _) => ("doctor.maven", "doctor.tools.unknown"),
                (_, "verified") => ("doctor.shader", "doctor.tools.verified"),
                (_, "missing-offline") => ("doctor.shader", "doctor.tools.missing_offline"),
                (_, "tampered") => ("doctor.shader", "doctor.tools.tampered"),
                _ => ("doctor.shader", "doctor.tools.unknown"),
            };
            let mut title = self.language.text(title).to_owned();
            if tool.id == "maven-repository" && tool.required_for.len() == 1 {
                title.push_str(" · ");
                title.push_str(&tool.required_for[0]);
            }
            let color = match tool.state.as_str() {
                _ if tool.ready() => palette.accent,
                "tampered" | "hash-mismatch" => palette.error,
                _ => palette.warning,
            };
            ui.horizontal_wrapped(|ui| {
                icons::show(
                    ui,
                    if tool.ready() {
                        Icon::Check
                    } else {
                        Icon::Cross
                    },
                    color,
                    14.0,
                );
                ui.label(
                    RichText::new(format!("{title} · {}", self.language.text(state))).strong(),
                );
            });
            let detail = |ui: &mut Ui, key: &str, value: &str| {
                let text = self.language.text(key).replace("{value}", value);
                ui.label(
                    RichText::new(text)
                        .size(theme::snap(ui, 12.0))
                        .color(palette.secondary),
                );
            };
            if tool.id == "maven-repository" {
                let inventory = &tool.inventory;
                if let (Some(checked), Some(total)) =
                    (inventory.checked_files, inventory.total_files)
                {
                    let mut summary = self
                        .language
                        .text("doctor.maven.summary")
                        .replace("{checked}", &checked.to_string())
                        .replace("{total}", &total.to_string());
                    if let Some(bytes) = inventory.total_bytes {
                        let size = if bytes == 0 {
                            "0 B".to_owned()
                        } else {
                            human_bytes(bytes)
                        };
                        summary.push_str(" · ");
                        summary.push_str(&size);
                    }
                    ui.label(
                        RichText::new(summary)
                            .size(theme::snap(ui, 12.0))
                            .color(palette.secondary),
                    );
                }
                if let Some(path) = inventory
                    .first_failed_path
                    .as_deref()
                    .filter(|path| !path.is_empty())
                {
                    detail(ui, "doctor.maven.first_failed", path);
                }
                if !tool.source_host.is_empty() {
                    detail(ui, "doctor.tools.source", &tool.source_host);
                }
            } else {
                if tool.state == "missing-offline" {
                    ui.label(self.language.text("doctor.shader.supplied_by_release"));
                }
                if !tool.destination_path.is_empty() {
                    detail(
                        ui,
                        if tool.state == "missing-offline" {
                            "doctor.shader.placement"
                        } else {
                            "doctor.shader.path"
                        },
                        &tool.destination_path,
                    );
                }
                if tool.state == "missing-offline" && !tool.inventory.expected_files.is_empty() {
                    detail(
                        ui,
                        "doctor.shader.expected",
                        &tool.inventory.expected_files.join(" · "),
                    );
                } else if tool.state == "tampered" && !tool.inventory.differing_files.is_empty() {
                    detail(
                        ui,
                        "doctor.shader.differing",
                        &tool.inventory.differing_files.join(" · "),
                    );
                }
                if matches!(tool.state.as_str(), "missing-offline" | "tampered") {
                    let choose = ui.add_enabled(
                        self.tools_task.is_none(),
                        Button::new(self.language.text("doctor.shader.choose")),
                    );
                    keep_focused_visible(&mut self.content_has_focus, choose.clone());
                    if choose.clicked()
                        && let Some(path) = rfd::FileDialog::new().pick_folder()
                    {
                        let mut settings = self.tool_path_settings.clone();
                        settings.shader_toolchain = path.display().to_string();
                        self.set_tool_path_settings(settings);
                        ui.ctx().request_repaint();
                        return;
                    }
                }
            }
            ui.add_space(3.0);
        }
    }

    fn render_tool_path_settings(&mut self, ui: &mut Ui, palette: Palette) {
        let mut settings = self.tool_path_settings.clone();
        let folders = CollapsingHeader::new(self.language.text("doctor.paths"))
            .id_salt("build-tool-paths")
            .show(ui, |ui| {
                ui.add_enabled_ui(self.tools_task.is_none(), |ui| {
                    for (key, path) in [
                        ("doctor.cache_dir", &mut settings.toolchain_dir),
                        ("doctor.toolchain", &mut settings.shader_toolchain),
                    ] {
                        ui.push_id(key, |ui| {
                            let label = self.language.text(key);
                            let title = ui.label(RichText::new(label).strong());
                            ui.horizontal_wrapped(|ui| {
                                let width = (ui.available_width() - 122.0)
                                    .max(96.0)
                                    .min(ui.available_width());
                                let field = ui.add_sized(
                                    [width, 28.0],
                                    egui::TextEdit::singleline(path)
                                        .hint_text(self.language.text("doctor.paths.default")),
                                );
                                keep_focused_visible(
                                    &mut self.content_has_focus,
                                    field.labelled_by(title.id),
                                );
                                let browse = ui.button(self.language.text("path.browse"));
                                keep_focused_visible(&mut self.content_has_focus, browse.clone());
                                if browse.clicked()
                                    && let Some(selection) = rfd::FileDialog::new().pick_folder()
                                {
                                    *path = selection.display().to_string();
                                }
                                let clear = ui.add_enabled(
                                    !path.is_empty(),
                                    Button::new(self.language.text("path.clear")),
                                );
                                keep_focused_visible(&mut self.content_has_focus, clear.clone());
                                if clear.clicked() {
                                    path.clear();
                                }
                            });
                        });
                    }
                });
            });
        keep_focused_visible(&mut self.content_has_focus, folders.header_response);
        if settings != self.tool_path_settings {
            ui.ctx().request_repaint();
        }
        self.set_tool_path_settings(settings);
        if self.tool_settings_save_failed {
            ui.colored_label(
                palette.error,
                self.language.text("doctor.paths.save_failed"),
            );
        }
    }

    fn render_verification(&mut self, ui: &mut Ui, palette: Palette) {
        const DISPLAY_FAILURES: usize = 3;

        let run = self.current_verification();
        let lang = self.language;
        ui.push_id("official-file-check", |ui| {
            ui.spacing_mut().item_spacing.y = 4.0;
            if self.doctor_error.is_some() {
                ui.colored_label(palette.warning, lang.text("verify.process_error"));
                return;
            }
            let Some(run) = run else {
                ui.label(lang.text("verify.pending"));
                return;
            };
            if run.running() {
                let width = ui.available_width().min(420.0);
                if let Some(progress) = &run.progress {
                    let fraction = if progress.total > 0 {
                        progress.checked as f32 / progress.total as f32
                    } else {
                        0.0
                    };
                    ui.add(
                        egui::ProgressBar::new(fraction)
                            .desired_width(width)
                            .text(format!("{} / {}", progress.checked, progress.total)),
                    );
                } else {
                    ui.add(
                        egui::ProgressBar::new(0.0)
                            .desired_width(width)
                            .text(lang.text("verify.running")),
                    );
                }
            }
            if run.error {
                ui.colored_label(palette.error, lang.text("verify.process_error"));
            }
            if run.has_failures() {
                Frame::new()
                    .fill(palette.tint(palette.error, 0.08))
                    .stroke(Stroke::new(1.0, palette.error))
                    .corner_radius(CornerRadius::same(5))
                    .inner_margin(4.0)
                    .show(ui, |ui| {
                        ui.label(palette.verification_caption(ui, lang.text("verify.repair")));
                    });
            }
            for (index, version) in run.inputs.versions.iter().enumerate() {
                ui.push_id(version, |ui| {
                    if run.inputs.versions.len() > 1 {
                        ui.label(palette.verification_caption(ui, version).strong());
                    }
                    let groups = if let Some(report) = run.reports.get(index) {
                        report.groups.as_slice()
                    } else if index == run.reports.len() {
                        run.active_groups.as_slice()
                    } else {
                        &[]
                    };
                    let mut failures = Vec::new();
                    ui.columns_const(|columns: &mut [Ui; 2]| {
                        for (column_index, group) in VerifyGroup::ALL.into_iter().enumerate() {
                            let label_key = format!("verify.{}", group.as_str());
                            let label = lang.text(&label_key);
                            let result = groups.iter().find(|result| result.group == group);
                            let (icon, color, status) = match result {
                                Some(result) if result.passed() => {
                                    (Icon::Check, palette.accent, "verify.pass")
                                }
                                Some(result)
                                    if result
                                        .failed
                                        .iter()
                                        .any(|item| item.reason == "prerequisite-failed") =>
                                {
                                    (Icon::Ring, palette.warning, "verify.skipped")
                                }
                                Some(_) => (Icon::Cross, palette.error, "verify.fail"),
                                None if index == run.reports.len()
                                    && run.running()
                                    && run.progress.as_ref().is_some_and(|p| p.group == group) =>
                                {
                                    (Icon::Ring, palette.info, "verify.running")
                                }
                                None => (Icon::Ring, palette.secondary, "verify.waiting"),
                            };
                            let text = format!("{label}: {}", lang.text(status));
                            if let Some(result) = result.filter(|result| {
                                !result.passed()
                                    && !result
                                        .failed
                                        .iter()
                                        .all(|item| item.reason == "prerequisite-failed")
                            }) {
                                failures.push((group, text.clone(), result));
                            }
                            columns[column_index % 2].horizontal(|ui| {
                                icons::show(ui, icon, color, 13.0);
                                ui.add(
                                    egui::Label::new(palette.verification_caption(ui, text)).wrap(),
                                );
                            });
                        }
                    });
                    for (group, text, result) in failures {
                        let caption = egui::WidgetText::from(
                            palette.verification_caption(ui, text).color(palette.error),
                        );
                        let caption_width = ui.available_width()
                            - ui.spacing().indent
                            - ui.spacing().button_padding.x;
                        let caption = caption.into_galley(
                            ui,
                            Some(egui::TextWrapMode::Wrap),
                            caption_width.max(0.0),
                            TextStyle::Button,
                        );
                        CollapsingHeader::new(caption)
                            .id_salt(group.as_str())
                            .default_open(true)
                            .show(ui, |ui| {
                                for file in result.failed.iter().take(DISPLAY_FAILURES) {
                                    let path = &file.path;
                                    let reason_key = format!("verify.reason.{}", file.reason);
                                    let reason = lang.text(&reason_key);
                                    let reason = if reason == reason_key {
                                        lang.text("verify.reason.other")
                                    } else {
                                        reason
                                    };
                                    ui.add(
                                        egui::Label::new(palette.verification_caption(
                                            ui,
                                            format!("{path} ({reason})"),
                                        ))
                                        .wrap(),
                                    );
                                    if file.reason.starts_with("mappings-")
                                        || path == "client-mappings.txt"
                                    {
                                        ui.label(palette.verification_caption(
                                            ui,
                                            lang.text("doctor.mapping.hint"),
                                        ));
                                    }
                                }
                                if result.failure_count > DISPLAY_FAILURES as u64 {
                                    ui.label(
                                        palette.verification_caption(
                                            ui,
                                            lang.text("verify.more").replace(
                                                "{count}",
                                                &(result.failure_count - DISPLAY_FAILURES as u64)
                                                    .to_string(),
                                            ),
                                        ),
                                    );
                                }
                            });
                    }
                });
            }
        });
    }

    fn render_detect(&mut self, ui: &mut Ui, palette: Palette) {
        self.verification_input_focused = false;
        let title = if self.show_official_check {
            "verify.title"
        } else {
            "doctor.title"
        };
        ui.horizontal_wrapped(|ui| {
            self.audited_label(
                ui,
                title,
                egui::Label::new(egui::RichText::new(self.language.text(title)).heading()),
            );
            let key = if self.show_official_check {
                "verify.environment"
            } else {
                "verify.jump"
            };
            let jump = ui
                .scope(|ui| {
                    ui.spacing_mut().button_padding = Vec2::new(4.0, 2.0);
                    ui.add(
                        Button::new(
                            RichText::new(self.language.text(key))
                                .color(palette.accent)
                                .underline(),
                        )
                        .frame(false)
                        .min_size(Vec2::splat(24.0)),
                    )
                })
                .inner;
            theme::focus_ring(ui, &jump, palette.accent);
            if jump.clicked() {
                self.show_official_check = !self.show_official_check;
                self.page_scroll_offset = 0.0;
                ui.scroll_to_cursor(Some(Align::Min));
                ui.ctx().request_repaint();
            }
        });
        if self.show_official_check {
            self.render_verification(ui, palette);
            return;
        }
        if self.doctor_error.is_none() || ui.ctx().content_rect().height() >= 300.0 {
            self.audited_label(
                ui,
                "doctor.subtitle",
                egui::Label::new(
                    RichText::new(self.language.text("doctor.subtitle")).color(palette.secondary),
                )
                .wrap(),
            );
            ui.add_space(4.0);
        }
        // The wizard page already scrolls. A second vertical viewport under the
        // header can collapse to only a few points in the compact layout.
        ui.scope(|ui| {
            if self.doctor_cancelled {
                ui.label(self.language.text("doctor.cancelled"));
            } else if let Some(error) = self.doctor_error.clone() {
                let title = self.language.text("doctor.failed");
                let fix = self.language.text("doctor.fix.java");
                self.error_banner(ui, palette, title, &error, fix);
            }
            self.render_tool_path_settings(ui, palette);
            if self.doctor_cancelled || self.doctor_error.is_some() {
                return;
            }
            if self.tool_check_at.is_some() {
                ui.label(self.language.text("doctor.paths.checking"));
            }
            self.render_local_tool_reports(ui, palette);
            if self
                .doctor_report
                .as_ref()
                .is_some_and(|report| !report.tools.is_empty())
            {
                self.render_build_tools(ui, palette);
                ui.add_space(8.0);
                ui.separator();
            }
            if let Some(report) = &self.doctor_report {
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
                        .unwrap_or_else(|| self.language.text("doctor.value.unknown").to_owned()),
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
                        .unwrap_or_else(|| self.language.text("doctor.value.unknown").to_owned()),
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
                        .unwrap_or_else(|| self.language.text("doctor.value.not_found").to_owned()),
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
                        .unwrap_or_else(|| self.language.text("verify.client_match").to_owned());
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
                        .size(theme::snap(ui, 12.0))
                        .color(palette.secondary),
                );
                ui.horizontal(|ui| {
                    let field_width = (ui.available_width() - 92.0).max(120.0);
                    let field = ui.add_sized(
                        [field_width, 32.0],
                        egui::TextEdit::singleline(&mut self.config.mappings_path)
                            .hint_text(self.language.text("doctor.mapping.placeholder")),
                    );
                    self.verification_input_focused |= field.has_focus();
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
                    .size(theme::snap(ui, 12.0))
                    .color(palette.secondary),
            );
            ui.horizontal(|ui| {
                let field_width = (ui.available_width() - 92.0).max(120.0);
                let field = ui.add_sized(
                    [field_width, 32.0],
                    egui::TextEdit::singleline(&mut self.minecraft_dir)
                        .hint_text(self.language.text("doctor.minecraft_dir.placeholder")),
                );
                self.verification_input_focused |= field.has_focus();
                keep_focused_visible(&mut self.content_has_focus, field);
                let browse = ui.button(self.language.text("path.browse"));
                keep_focused_visible(&mut self.content_has_focus, browse.clone());
                if browse.clicked()
                    && let Some(path) = rfd::FileDialog::new().pick_folder()
                {
                    self.minecraft_dir = path.display().to_string();
                }
            });
        });
    }

    fn error_banner(&mut self, ui: &mut Ui, palette: Palette, title: &str, cause: &str, fix: &str) {
        let compact = self.effective_zoom >= 1.4;
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
        if let Some(timestamp) = self.terms_accepted_at {
            ui.horizontal_wrapped(|ui| {
                let note = self
                    .language
                    .text("terms.accepted_on")
                    .replace("{time}", &terms_acceptance::display_time(timestamp));
                ui.label(RichText::new(note).small().color(palette.secondary));
                let revoke = ui.button(self.language.text("terms.revoke"));
                keep_focused_visible(&mut self.content_has_focus, revoke.clone());
                if revoke.clicked() {
                    self.revoke_terms_acceptance();
                }
            });
        } else {
            self.audited_label(
                ui,
                "terms.subtitle",
                egui::Label::new(
                    RichText::new(self.language.text("terms.subtitle")).color(palette.secondary),
                )
                .wrap(),
            );
        }
        if let Some(error) = self.terms_error {
            ui.colored_label(palette.error, self.language.text(error));
        }
        ui.add_space(8.0);
        let content = terms_text(self.language);
        let output = ScrollArea::vertical()
            .id_salt(("terms-scroll", self.language.code()))
            .auto_shrink([false, false])
            .scroll_bar_visibility(
                egui::containers::scroll_area::ScrollBarVisibility::VisibleWhenNeeded,
            )
            .show(ui, |ui| {
                ui.add_space(8.0);
                for paragraph in content.split("\n\n") {
                    ui.label(RichText::new(paragraph).size(theme::snap(ui, 14.0)));
                    ui.add_space(12.0);
                }
                theme::scroll_end_padding(ui);
            });
        let max_scroll = (output.content_size.y - output.inner_rect.height()).max(0.0);
        if output.state.clamped_offset().y >= max_scroll - 2.0 || max_scroll <= 2.0 {
            self.terms_scrolled = true;
        }
    }

    fn reload_terms_acceptance(&mut self) {
        self.terms_hash = terms_text(self.language).sha256();
        let accepted_at = self.terms_record_path.as_deref().and_then(|path| {
            terms_acceptance::load(path, &self.terms_hash, terms_acceptance::now())
        });
        self.restore_terms_acceptance(accepted_at);
    }

    fn restore_terms_acceptance(&mut self, accepted_at: Option<u64>) {
        self.terms_accepted_at = accepted_at;
        self.terms_checked = accepted_at.is_some();
        self.terms_scrolled = self.terms_checked;
        self.terms_error = None;
    }

    fn accept_terms(&mut self) {
        let timestamp = terms_acceptance::now();
        if let Some(path) = &self.terms_record_path {
            if terms_acceptance::save(path, &self.terms_hash, timestamp).is_err() {
                self.terms_error = Some("terms.save_failed");
                return;
            }
            self.terms_accepted_at = Some(timestamp);
        }
        self.terms_error = None;
        self.terms_checked = true;
    }

    fn revoke_terms_acceptance(&mut self) {
        if let Some(path) = &self.terms_record_path
            && terms_acceptance::revoke(path).is_err()
        {
            self.terms_error = Some("terms.revoke_failed");
            return;
        }
        self.restore_terms_acceptance(None);
    }

    fn render_config(&mut self, ui: &mut Ui, palette: Palette) {
        let viewport = ui.clip_rect();
        let content_width = ui.available_width().min(viewport.width()).max(0.0);
        let panel_inner_width = (content_width - 24.0).max(0.0);
        ui.set_width(content_width);
        ui.set_max_width(content_width);
        ui.horizontal_wrapped(|ui| {
            self.audited_label(
                ui,
                "config.title",
                egui::Label::new(egui::RichText::new(self.language.text("config.title")).heading()),
            );
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
                let backend_label = config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.backend"),
                    true,
                );
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

                let quality_label = config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.quality"),
                    true,
                );
                if columns == 1 {
                    ui.end_row();
                }
                let quality = ComboBox::from_id_salt("config-quality")
                    .selected_text(self.language.text(
                        ["config.low", "config.mid", "config.high", "config.ultra"]
                            [self.config.quality.min(3)],
                    ))
                    .width((cell_width * 0.72).clamp(64.0, 240.0))
                    .show_ui(ui, |ui| {
                        for (index, key) in
                            ["config.low", "config.mid", "config.high", "config.ultra"]
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

                self.config.distance = self.config.distance.clamp(2, 32);
                let distance_label = config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.distance"),
                    true,
                );
                if columns == 1 {
                    ui.end_row();
                }
                ui.vertical(|ui| {
                    ui.horizontal(|ui| {
                        let value_width = cell_width.min(68.0);
                        let slider_width =
                            (cell_width - value_width - ui.spacing().item_spacing.x).max(0.0);
                        let response = keep_focused_visible(
                            &mut self.content_has_focus,
                            ui.add_sized(
                                [slider_width, 28.0],
                                egui::Slider::new(&mut self.config.distance, 2..=32)
                                    .show_value(false),
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
                    ui.add(
                        egui::Label::new(
                            RichText::new(self.language.text("config.distance_hint"))
                                .size(theme::snap(ui, 11.0))
                                .color(palette.secondary),
                        )
                        .wrap(),
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
        CollapsingHeader::new(self.language.text("config.advanced")).show(ui, |ui| {
            ui.add_enabled_ui(self.tools_task.is_none(), |ui| {
                let response = wrapped_checkbox(
                    ui,
                    &mut self.config.strict_kernel,
                    self.language.text("config.strict_kernel"),
                );
                if response.response.changed() {
                    self.start_doctor();
                }
                keep_focused_visible(&mut self.content_has_focus, response.response);
            });
            ui.label(self.language.text("config.strict_kernel_hint"));
        });
        ui.add_space(4.0);
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
            .inner_margin(egui::Margin::symmetric(10, 5))
            .show(ui, |ui| {
                ui.horizontal_wrapped(|ui| {
                    icons::show(ui, icon, color, 16.0);
                    ui.label(RichText::new(message).color(color));
                });
            });
        let caption = self.language.text("config.cli_limit").to_owned();
        let response = self.audited_label(
            ui,
            "config.cli_limit",
            egui::Label::new(
                RichText::new(caption.as_str())
                    .size(theme::snap(ui, 12.0))
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
                config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.builtin_pack"),
                    false,
                );
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

                config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.resource_path"),
                    true,
                );
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
                    .add_sized([26.0, 26.0], Button::new(""))
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
                let loader_label = config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.loader"),
                    false,
                );
                if columns == 1 {
                    ui.end_row();
                }
                ui.horizontal(|ui| {
                    let combo_width =
                        ((cell_width - ui.spacing().item_spacing.x) * 0.6).clamp(64.0, 180.0);
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
                ui.end_row();
            });
        // Keep the warning out of the two-column grid. A third auto-sized cell
        // made the grid's wrapped width (and therefore its repaint) unstable.
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
                                .size(theme::snap(ui, 12.0))
                                .color(palette.warning),
                        );
                    });
                });
        }
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

                config_field_label(
                    ui,
                    self.language,
                    palette,
                    cell_width,
                    self.language.text("config.mod_path"),
                    false,
                );
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
                                RichText::new(self.language.text(key))
                                    .strong()
                                    .size(theme::snap(ui, 12.0)),
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
                                        .size(theme::snap(ui, 12.0))
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
                            .add_sized([26.0, 26.0], Button::new(""))
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
                                            .size(theme::snap(ui, 12.0))
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
        if !matches!(self.build_state, BuildState::Failed | BuildState::Cancelled)
            || ui.ctx().content_rect().height() >= 300.0
        {
            self.audited_label(
                ui,
                "build.subtitle",
                egui::Label::new(
                    RichText::new(self.language.text("build.subtitle")).color(palette.secondary),
                )
                .wrap(),
            );
        }
        if self.build_state == BuildState::Idle
            && let Some(key) = self.build_gate()
        {
            ui.add(
                egui::Label::new(RichText::new(self.language.text(key)).color(palette.warning))
                    .wrap(),
            );
        }
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
            self.output_dir = self.work_dir.join("builds").to_string_lossy().into_owned();
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
                            .size(theme::snap(ui, 12.0)),
                        );
                    }
                    for line in &self.build_logs {
                        ui.label(RichText::new(line).monospace().size(theme::snap(ui, 11.0)));
                    }
                    let copy = ui.button(self.language.text("build.copy_log"));
                    keep_focused_visible(&mut self.content_has_focus, copy.clone());
                    if copy.clicked() {
                        copy_diagnostics(ui.ctx(), &self.diagnostics());
                    }
                    if ui
                        .button(self.language.text("build.save_diagnostics"))
                        .clicked()
                        && let Some(path) = rfd::FileDialog::new()
                            .set_file_name("gaius-diagnostics.txt")
                            .save_file()
                        && let Err(error) = write_diagnostics(&path, &self.diagnostics())
                    {
                        self.build_logs.push_back(format!(
                            "{}: {error}",
                            self.language.text("build.save_diagnostics_failed")
                        ));
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
            let row_width = ui.available_width().min(ui.clip_rect().width()).min(588.0);
            let label_width = (row_width * 0.25).clamp(72.0, 148.0).min(row_width);
            let value_width = (row_width - label_width - ui.spacing().item_spacing.x).max(0.0);
            self.render_version_results(ui, palette, row_width, label_width, value_width);
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
                                        .size(theme::snap(ui, 14.0)),
                                ),
                            );
                            self.audited_label(
                                ui,
                                "complete.title",
                                egui::Label::new(
                                    RichText::new(self.language.text("complete.title"))
                                        .size(theme::snap(ui, 10.0))
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
                let label_width = (summary_width * 0.25).clamp(72.0, 148.0).min(summary_width);
                let summary_row_height = if summary_width < 360.0 { 18.0 } else { 24.0 };
                let column_spacing = ui.spacing().item_spacing.x;
                let value_width = (summary_width - label_width - column_spacing).max(0.0);
                let (location_row_rect, _) =
                    ui.allocate_exact_size(Vec2::new(summary_width, 28.0), egui::Sense::hover());
                let location_label_rect =
                    Rect::from_min_size(location_row_rect.min, Vec2::new(label_width, 28.0));
                let location_value_rect = Rect::from_min_size(
                    egui::pos2(
                        location_row_rect.min.x + label_width + column_spacing,
                        location_row_rect.min.y,
                    ),
                    Vec2::new(value_width, 28.0),
                );
                let location_label = scope_at_rect(ui, location_label_rect, |ui| {
                    ui.add(
                        egui::Label::new(self.language.text("complete.location"))
                            .halign(Align::Min),
                    )
                });
                let copy_width = 62.0f32.min(value_width);
                let path_width = (value_width - copy_width - column_spacing).max(0.0);
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
                let location_value = scope_at_rect(ui, location_value_rect, |ui| {
                    ui.spacing_mut().button_padding.y = 4.0;
                    ui.horizontal(|ui| {
                        let path = ui
                            .add_sized(
                                [path_cell_width, 28.0],
                                egui::Label::new(display)
                                    .halign(Align::Min)
                                    .selectable(true),
                            )
                            .on_hover_text(self.output_dir.as_str());
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
                        (path, copy)
                    })
                    .inner
                });
                let (location_path, location_copy) = location_value.inner;
                let location_label = location_label.inner;
                self.layout_rects.completion_labels[1] = Some(location_label.rect);
                self.layout_rects.completion_values[1] = Some(location_path.rect);
                self.layout_rects.completion_copy = Some(location_copy.rect);

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
                self.render_version_results(ui, palette, summary_width, label_width, value_width);
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
                self.audited_label(
                    ui,
                    "complete.options_not_applied",
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

    fn render_version_results(
        &mut self,
        ui: &mut Ui,
        palette: Palette,
        row_width: f32,
        label_width: f32,
        value_width: f32,
    ) {
        if self.build_results.is_empty() {
            return;
        }
        ui.add_space(8.0);
        let heading = add_left_cell(
            ui,
            Vec2::new(row_width, 22.0),
            egui::Label::new(RichText::new(self.language.text("complete.artifacts")).strong())
                .halign(Align::Min),
        );
        self.layout_rects.completion_artifacts = Some(heading.rect);
        for (index, result) in self.build_results.iter().enumerate() {
            let (status, color) = if result.exit_code != 0 {
                (self.language.text("complete.status.failed"), palette.error)
            } else {
                (
                    self.language.text("complete.status.success"),
                    palette.accent,
                )
            };
            let path = result.output_dir.display().to_string();
            let row_height = 28.0;
            let (row_rect, _) =
                ui.allocate_exact_size(Vec2::new(row_width, row_height), egui::Sense::hover());
            let column_gap = ui.spacing().item_spacing.x;
            let label_rect = Rect::from_min_size(row_rect.min, Vec2::new(label_width, row_height));
            let value_rect = Rect::from_min_size(
                egui::pos2(row_rect.min.x + label_width + column_gap, row_rect.min.y),
                Vec2::new(value_width, row_height),
            );
            scope_at_rect(ui, label_rect, |ui| {
                ui.horizontal(|ui| {
                    ui.spacing_mut().item_spacing.x = 4.0;
                    ui.add(
                        egui::Label::new(
                            RichText::new(&result.version)
                                .strong()
                                .size(theme::snap(ui, 13.0)),
                        )
                        .truncate(),
                    );
                    Frame::new()
                        .fill(palette.tint(color, 0.06))
                        .stroke(Stroke::new(1.0, color))
                        .corner_radius(4)
                        .inner_margin(egui::Margin::symmetric(4, 2))
                        .show(ui, |ui| {
                            ui.label(
                                RichText::new(status)
                                    .strong()
                                    .size(theme::snap(ui, 10.0))
                                    .color(color),
                            );
                        });
                });
            });
            let button_text = self.language.text("complete.open_folder.short");
            let button_font = TextStyle::Button.resolve(ui.style());
            let button_text_width = ui.fonts_mut(|fonts| {
                fonts
                    .layout_no_wrap(button_text.to_owned(), button_font, palette.text)
                    .size()
                    .x
            });
            let column_gap = ui.spacing().item_spacing.x;
            let button_width =
                (button_text_width + ui.spacing().button_padding.x * 2.0 + 2.0).min(value_width);
            let path_width = (value_width - button_width - column_gap).max(0.0);
            let path_font = TextStyle::Body.resolve(ui.style());
            let display = fit_middle_ellipsis(ui, &path, path_width, path_font, palette.text);
            let path_rect = Rect::from_min_size(value_rect.min, Vec2::new(path_width, row_height));
            scope_at_rect(ui, path_rect, |ui| {
                ui.add(egui::Label::new(display).halign(Align::Min).truncate())
                    .on_hover_text(path.as_str())
            });
            let open_rect = Rect::from_min_size(
                egui::pos2(path_rect.right() + column_gap, value_rect.min.y + 1.0),
                Vec2::new(button_width, row_height - 2.0),
            );
            let open = ui
                .scope(|ui| {
                    ui.spacing_mut().button_padding.y = 4.0;
                    ui.put(open_rect, Button::new(button_text))
                })
                .inner
                .on_hover_text(self.language.text("complete.open_folder"));
            keep_focused_visible(&mut self.content_has_focus, open.clone());
            if result.exit_code == 0 && open.clicked() {
                open_path(&result.output_dir);
            }
            if let Some(rect) = self.layout_rects.completion_artifact_labels.get_mut(index) {
                *rect = Some(label_rect);
            }
            if let Some(rect) = self.layout_rects.completion_artifact_paths.get_mut(index) {
                *rect = Some(path_rect);
            }
            if let Some(rect) = self
                .layout_rects
                .completion_artifact_open_buttons
                .get_mut(index)
            {
                *rect = Some(open.rect);
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
        if !self.tools_ready_for_selection() {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("doctor.tools.blocked").to_owned());
            return;
        }
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
        if !self.verification_passed() {
            self.build_state = BuildState::Failed;
            self.build_error = Some(self.language.text("verify.blocked").to_owned());
            return;
        }
        if self.output_dir.is_empty() {
            self.output_dir = self.work_dir.join("builds").to_string_lossy().into_owned();
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
        let mut settings = BuildSettings::from_ui_indices(
            self.config.quality,
            self.config.distance,
            self.config.backend,
            self.config
                .resource_paths
                .iter()
                .map(|pack| PathBuf::from(&pack.path))
                .collect(),
        );
        settings.strict_kernel = self.config.strict_kernel;
        let task = PatcherClient.start_build(
            self.selected_versions.clone(),
            output,
            (!mappings_path.is_empty()).then(|| PathBuf::from(mappings_path)),
            self.tool_path_settings.paths(),
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
            || !self.config.resource_paths.is_empty()
            || self.config.strict_kernel
    }

    fn write_build_options(&self) -> Result<(), String> {
        #[derive(Serialize)]
        struct BuildOptions<'a> {
            schema: &'static str,
            versions: &'a [String],
            applied_to_build: [&'static str; 10],
            not_applied_to_build: [&'static str; 3],
            graphics_backend: &'static str,
            quality: &'static str,
            view_distance_chunks: u32,
            strict_kernel: bool,
            built_in_pack_enabled: bool,
            built_in_pack_license_confirmed: bool,
            local_pack_path: &'a str,
            local_packs: &'a [LocalAsset],
            loader: &'static str,
            loader_license_confirmed: bool,
            local_mod_path: &'a str,
            mods: &'a [ModRow],
        }
        let backend = BuildSettings::backend_from_ui_index(self.config.backend);
        let quality = BuildSettings::quality_from_ui_index(self.config.quality);
        let loader = ["off", "fabric", "neoforge", "forge"][self.config.loader.min(3)];
        let record = BuildOptions {
            schema: "gaius-builder-options-v1",
            versions: &self.selected_versions,
            applied_to_build: [
                "output_dir",
                "mappings_path",
                "shader_toolchain_path",
                "toolchain_dir",
                "minecraft_dir",
                "graphics_backend",
                "quality",
                "view_distance",
                "local_packs",
                "strict_kernel",
            ],
            not_applied_to_build: ["built_in_pack", "loader", "mods"],
            graphics_backend: backend,
            quality,
            view_distance_chunks: BuildSettings::render_distance_from_ui(self.config.distance),
            strict_kernel: self.config.strict_kernel,
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
        let logs = self
            .build_logs
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>()
            .join("\n");
        let value = format!(
            "Gaius Builder\nPage: {}\nState: {:?}\nOutput: {}\n{}\n{}Error: {}\nLogs:\n{}",
            self.page,
            self.build_state,
            self.output_dir,
            self.tool_paths_diagnostics(),
            self.display_diagnostics(),
            self.build_error.as_deref().unwrap_or("none"),
            logs
        );
        sanitize_user_paths(&value)
    }

    fn tool_paths_diagnostics(&self) -> String {
        format!(
            "--toolchain-dir: {}\n--shader-toolchain: {}",
            self.tool_path_settings.toolchain_dir.trim(),
            self.tool_path_settings.shader_toolchain.trim(),
        )
    }

    fn doctor_diagnostics(&self) -> String {
        let report = self.doctor_report.as_ref();
        let tools = report
            .map(|report| {
                report
                    .tools
                    .iter()
                    .map(|tool| {
                        format!(
                            "{} [{}]: {} ({}) {}",
                            tool.display_name,
                            tool.required_for.join(", "),
                            tool.state,
                            tool.destination_path,
                            tool.hint
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n")
            })
            .unwrap_or_default();
        let value = format!(
            "Gaius Builder\nPage: 2\n{}\n{}Error: {}\nOutput:\n{}\n{}\nBuild tools:\n{}",
            self.tool_paths_diagnostics(),
            self.display_diagnostics(),
            self.tools_error
                .as_deref()
                .or(self.doctor_error.as_deref())
                .unwrap_or("none"),
            report
                .map(|item| item.raw_output.as_str())
                .unwrap_or_default(),
            report.map(|item| item.stderr.as_str()).unwrap_or_default(),
            tools,
        );
        sanitize_user_paths(&value)
    }

    fn action_row_height(&self, ui: &Ui, width: f32) -> f32 {
        if self.page == 3 {
            let mut job = egui::text::LayoutJob::simple(
                self.language.text("terms.accept").to_owned(),
                TextStyle::Body.resolve(ui.style()),
                ui.visuals().text_color(),
                (width - 24.0 - ui.spacing().item_spacing.x).max(1.0),
            );
            job.sections[0].format.line_height = Some(theme::snap(
                ui,
                ui.text_style_height(&TextStyle::Body) * 1.05,
            ));
            let label_height = ui.fonts_mut(|fonts| fonts.layout_job(job).size().y);
            return label_height.max(24.0) + 40.0;
        }
        let scale = self.effective_zoom.max(1.0);
        let base = match self.page {
            2 if self.show_official_check => 56.0,
            2 => 72.0,
            5 if self.build_state == BuildState::Cancelled
                && !self.cleanup_candidates.is_empty() =>
            {
                96.0
            }
            5 => 72.0,
            6 => 72.0,
            _ => 0.0,
        };
        if base > 0.0 {
            (base / scale).max(49.0)
        } else {
            0.0
        }
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
                if self.show_official_check {
                    let recheck = ui.button(self.language.text("verify.recheck"));
                    self.record_action(0, &recheck);
                    if recheck.clicked() {
                        if !self.tools_ready_for_selection() {
                            self.start_doctor();
                        }
                        self.start_verification();
                    }
                } else if self.doctor_receiver.is_some() {
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
                        self.start_verification();
                    }
                    let failed = self.doctor_error.is_some()
                        || self.doctor_cancelled
                        || (self.doctor_report.is_some() && !self.tools_ready_for_selection())
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
                            copy_diagnostics(ui.ctx(), &self.doctor_diagnostics());
                        }
                    }
                }
            }
            3 => {}
            5 => match self.build_state {
                BuildState::Idle => {
                    let start = ui
                        .add_enabled(
                            self.build_gate().is_none(),
                            Button::new(self.language.text("build.start"))
                                .min_size(Vec2::new(40.0, 40.0)),
                        )
                        .on_disabled_hover_text(
                            self.language.text(self.build_gate().unwrap_or("")),
                        );
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
                        copy_diagnostics(ui.ctx(), &self.diagnostics());
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
                        .add_enabled(
                            self.build_gate().is_none(),
                            Button::new(self.language.text(if compact {
                                "build.retry.short"
                            } else {
                                "build.retry"
                            })),
                        )
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
                            .min_size(Vec2::splat(40.0)),
                    );
                    let open_browser =
                        open_browser.on_hover_text(self.language.text("complete.open_browser"));
                    theme::focus_ring(ui, &open_browser, palette.on_accent());
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
                        copy_diagnostics(ui.ctx(), &self.diagnostics());
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
                        .add_enabled(
                            self.build_gate().is_none(),
                            Button::new(self.language.text(if compact {
                                "build.retry.short"
                            } else {
                                "build.retry"
                            })),
                        )
                        .on_hover_text(self.language.text("build.retry"));
                    self.record_action(2, &retry);
                    if retry.clicked() {
                        self.page = 5;
                        self.start_build();
                    }
                }
                _ => {
                    let start = ui
                        .add_enabled(
                            self.build_gate().is_none(),
                            Button::new(self.language.text("build.start"))
                                .min_size(Vec2::new(40.0, 40.0)),
                        )
                        .on_disabled_hover_text(
                            self.language.text(self.build_gate().unwrap_or("")),
                        );
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
        if checkbox.response.changed() {
            if accepted {
                self.accept_terms();
            } else {
                self.revoke_terms_acceptance();
            }
        }

        ui.add_space(1.0);
        let hash_height = 24.0;
        let row_width = ui.available_width().max(0.0);
        ui.allocate_ui_with_layout(
            Vec2::new(row_width, hash_height),
            Layout::left_to_right(Align::Center),
            |ui| {
                ui.spacing_mut().button_padding.y = 3.0;
                let copy_width = 32.0;
                let hash_width =
                    (ui.available_width() - copy_width - ui.spacing().item_spacing.x).max(0.0);
                let hash_text = format!("SHA-256  {}", self.terms_hash);
                let display = fit_middle_ellipsis(
                    ui,
                    &hash_text,
                    hash_width,
                    egui::FontId::monospace(theme::snap(ui, 10.0)),
                    palette.secondary,
                );
                let hash = add_left_cell(
                    ui,
                    Vec2::new(hash_width, hash_height),
                    egui::Label::new(
                        RichText::new(display)
                            .monospace()
                            .size(theme::snap(ui, 10.0))
                            .color(palette.secondary),
                    )
                    .halign(Align::Min)
                    .truncate(),
                );
                self.layout_rects.terms_hash_label = Some(hash.rect);
                self.record_text_audit("terms.hash", hash.rect, ui.clip_rect());
                hash.on_hover_text(self.terms_hash.as_str());
                let copy = ui
                    .add_sized([copy_width, hash_height], Button::new(""))
                    .on_hover_text(self.language.text("terms.hash.copy"));
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
                    let status_width = (ui.available_width() - 230.0).max(0.0);
                    ui.add_sized(
                        [status_width, 24.0],
                        egui::Label::new(RichText::new(label).color(palette.secondary)).truncate(),
                    )
                    .on_hover_text(label);
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        let next = ui.add_enabled(
                            self.can_advance(),
                            Button::new(self.language.text(if self.page == 6 {
                                "nav.finish"
                            } else {
                                "nav.next"
                            }))
                            .min_size(Vec2::splat(40.0)),
                        );
                        let next = if self.page == 2 {
                            next.on_disabled_hover_text(self.language.text(
                                if self.verification_passed() {
                                    "doctor.tools.blocked"
                                } else {
                                    "verify.blocked"
                                },
                            ))
                        } else {
                            next
                        };
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
            2 => self.check_page_ready(),
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
            if let Some(run) = self.current_verification() {
                if run.error || run.has_failures() {
                    return (
                        Icon::Cross,
                        self.language.text("verify.status_failed"),
                        palette.error,
                    );
                }
                if run.running() {
                    return (
                        Icon::Ring,
                        self.language.text("status.checking"),
                        palette.info,
                    );
                }
            }
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
            if !self.tools_ready_for_selection() {
                return (
                    Icon::Ring,
                    self.language.text("doctor.tools.not_ready"),
                    palette.warning,
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
            let clip = ui.clip_rect();
            let response = ui.add(
                egui::Label::new(
                    RichText::new(self.language.text("footer.disclaimer"))
                        .size(theme::snap(ui, 11.5))
                        .color(palette.secondary),
                )
                .wrap()
                .halign(Align::Center),
            );
            self.record_text_audit("footer.disclaimer", response.rect, clip);
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
                    let native = context.native_pixels_per_point().unwrap_or(1.0);
                    ui.label(format!(
                        "{}: {:.0}% ({native:.2} px/pt)",
                        self.language.text("scale.native"),
                        native * 100.0
                    ));
                    ui.label(format!(
                        "{}: {:.0}% ({} {:.0}%)",
                        self.language.text("scale.effective"),
                        context.pixels_per_point() * 100.0,
                        self.language.text("scale.label"),
                        self.effective_zoom * 100.0
                    ));
                    if (self.effective_zoom - self.config.ui_scale.factor()).abs() > 0.001 {
                        ui.label(self.language.text("scale.limited"));
                    }
                    if ui.button(self.language.text("scale.copy_dpi")).clicked() {
                        copy_diagnostics(
                            context,
                            &format!(
                                "{}Fonts: {}",
                                self.display_diagnostics(),
                                self.fonts_report(context)
                            ),
                        );
                    }
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new(self.language.text("footer.disclaimer"))
                            .size(theme::snap(ui, 12.0))
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
            output_dir: PathBuf::from(
                "./gaius-output/releases/2026-11-17/26.3/portable-html-with-worker-wasm-and-assets",
            ),
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

    fn mock_three_version_results() -> Vec<VersionBuildResult> {
        VERSION_IDS
            .into_iter()
            .map(|version| VersionBuildResult {
                version: version.to_owned(),
                output_dir: PathBuf::from(format!(
                    "./gaius-output/releases/2026-11-17/{version}/portable-html-with-worker-wasm-and-assets"
                )),
                exit_code: 0,
                error: None,
                hashes: HashComparison {
                    state: HashState::Unknown,
                    mismatches: Vec::new(),
                },
            })
            .collect()
    }

    fn mock_failed(&mut self, phase: &str, reason: &str) {
        self.build_state = BuildState::Failed;
        self.build_error = Some(format!("{phase}: {reason}"));
    }
}

impl eframe::App for BuilderApp {
    fn logic(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        if let Some((deadline, completion)) = &self.dpi_watch {
            if let Some(render) = frame.wgpu_render_state() {
                let _ = render.device.poll(eframe::wgpu::PollType::Poll);
            }
            if completion.get().is_some() {
                self.dpi_watch = None;
            } else if Instant::now() >= *deadline {
                let _ = completion.set(Err(
                    "timed out waiting for the rendered DPI preview".to_owned()
                ));
                ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                self.dpi_watch = None;
            } else {
                ctx.request_repaint_after(Duration::from_millis(25));
            }
        }
    }

    fn raw_input_hook(&mut self, _ctx: &egui::Context, input: &mut egui::RawInput) {
        if self.read_only_preview {
            input.events.clear();
        }
    }

    fn on_exit(&mut self) {
        if let Some(desktop) = &mut self.desktop {
            desktop.save();
        }
    }

    fn ui(&mut self, ui: &mut Ui, frame: &mut eframe::Frame) {
        let context = ui.ctx().clone();
        if let Some(at) = self.tool_check_at {
            let wait = at.saturating_duration_since(Instant::now());
            if wait.is_zero() {
                self.start_doctor();
            } else {
                context.request_repaint_after(wait);
            }
        }
        self.poll_doctor();
        self.poll_verification();
        self.poll_tools_install();
        self.poll_build();
        self.handle_keys(&context);
        let system_theme = context.system_theme();
        let zoom = ui_scale::input(&context, self.config.ui_scale);
        if zoom != self.config.ui_scale {
            self.change_zoom(zoom);
        }
        let viewport = context.content_rect();
        self.effective_zoom = context.zoom_factor();
        let ppp = context.pixels_per_point();
        let effective = (self.theme_mode, system_theme, ppp);
        if self.theme_applied != Some(effective) {
            theme::apply(&context, self.theme_mode);
            theme::snap_styles(&context, ppp);
            self.theme_applied = Some(effective);
        }

        context.style_mut_of(self.theme_mode.effective(system_theme), |style| {
            style.animation_time = if self.config.reduce_motion { 0.0 } else { 0.15 };
            style.spacing.scroll = egui::style::ScrollStyle::solid();
            style.spacing.scroll.bar_width = 8.0;
            style.spacing.scroll.bar_inner_margin = 2.0;
            style.spacing.scroll.foreground_color = true;
        });
        let palette = self.theme_mode.palette(system_theme);
        ui.reset_style();
        ui.painter()
            .rect_filled(viewport, CornerRadius::ZERO, palette.background);

        let scale = self.effective_zoom.max(1.0);
        let bounds = viewport.shrink(12.0 / scale);
        ui.set_clip_rect(viewport);
        let (top_rect, top_content_rect) =
            scope_at_rect(ui, bounds, |ui| self.render_top_bar(ui, palette)).inner;
        #[cfg(not(test))]
        let _ = top_content_rect;
        self.compact_rail = bounds.width() < 570.0;
        let rail_width = if self.compact_rail {
            58.0
        } else {
            205.0f32.min(bounds.width() * 0.30)
        };
        let column_gap = 12.0 / scale;
        let bottom_height = (24.0 + 36.0 / scale).max(52.0);
        let footer_font_size = theme::snap(ui, 11.5);
        let footer_galley = ui.painter().layout(
            self.language.text("footer.disclaimer").to_owned(),
            egui::FontId::proportional(footer_font_size),
            palette.secondary,
            bounds.width(),
        );
        let footer_height = (footer_galley.size().y + 12.0 / scale).max(34.0 / scale);
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
        let requested_action_height =
            self.action_row_height(ui, bounds.width() - rail_width - column_gap);
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
            completion_artifact_labels: [None; 3],
            completion_artifact_paths: [None; 3],
            completion_artifact_open_buttons: [None; 3],
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
                    let content = ui.min_rect();
                    theme::scroll_end_padding(ui);
                    content
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
        let window = self
            .desktop
            .as_mut()
            .map(|desktop| desktop.sample(frame, &context));
        let fitted =
            ui_scale::fit_zoom(self.requested_zoom(), viewport.size() * self.effective_zoom);
        if fitted != self.effective_zoom {
            context.set_zoom_factor(fitted);
        } else if let Some(window) = window {
            self.request_dpi_report(&context, window);
        }
        if self.doctor_receiver.is_some()
            || self.build_task.is_some()
            || self.tools_task.is_some()
            || self
                .verification
                .as_ref()
                .is_some_and(|run| run.task.is_some())
            || (self.live_verification && self.page == 2 && self.current_verification().is_none())
        {
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
    let line_height = theme::snap(ui, ui.text_style_height(&TextStyle::Body) * 1.05);
    let mark_size = 24.0;
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
        if response.has_focus() {
            ui.visuals().widgets.active.weak_bg_fill
        } else if *checked {
            ui.visuals().selection.bg_fill
        } else {
            visuals.bg_fill
        },
        if response.has_focus() {
            visuals.bg_stroke
        } else {
            visuals.fg_stroke
        },
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
    let display = DisplayOptions {
        work: None,
        scale: Some(scale),
    };
    render_screenshot_at_dpi(
        path,
        page,
        theme_mode,
        language,
        size,
        (1.0, &display),
        state,
    )
    .map(|_| ())
}

fn screenshot_harness(
    page: usize,
    theme: ScreenTheme,
    language: Language,
    size: [u32; 2],
    (native_ppp, display): (f32, &DisplayOptions),
    scenario: ScreenshotState,
) -> Result<(egui_kittest::Harness<'static, BuilderApp>, f32), String> {
    let work_dir = display.work_dir();
    let zoom = display.zoom(&desktop::Settings::load(&work_dir.join("ui-settings.json")));
    let requested = zoom.factor();
    if !(0.0 < requested && requested <= 4.0 && (0.75..=4.0).contains(&native_ppp)) {
        return Err("unsupported screenshot native PPP or zoom".to_owned());
    }
    let native_size = Vec2::new(size[0] as f32, size[1] as f32);
    let scale = ui_scale::fit_zoom(requested, native_size);
    let size = native_size / scale;
    let mut harness = egui_kittest::Harness::builder()
        .with_size(size)
        .with_pixels_per_point(native_ppp)
        .with_theme(match theme {
            ScreenTheme::Dark => Theme::Dark,
            ScreenTheme::Light => Theme::Light,
        })
        .with_context_setup(move |ctx| ctx.options_mut(|options| options.zoom_factor = scale))
        .allow_missing_glyphs()
        .with_render_cursor(false)
        .build_eframe(move |context| {
            let mut app = BuilderApp::with_state(
                &context.egui_ctx,
                language,
                theme.mode(),
                page,
                false,
                scenario,
            );
            app.work_dir.clone_from(&work_dir);
            app.apply_startup_zoom(zoom);
            app
        });
    harness.set_size(size);
    Ok((harness, scale))
}

/// Render a window sized in native logical points and return its zoom diagnostics.
pub fn render_screenshot_at_dpi(
    path: &Path,
    page: usize,
    theme_mode: ScreenTheme,
    language: Language,
    size: [u32; 2],
    (native_ppp, display): (f32, &DisplayOptions),
    state: &str,
) -> Result<serde_json::Value, String> {
    use egui_kittest::kittest::Queryable;

    let scenario = ScreenshotState::parse(state)?;
    if matches!(
        scenario,
        ScreenshotState::TermsAccepted | ScreenshotState::TermsPreAccepted
    ) && page != 3
    {
        return Err("terms acceptance screenshots must use page 3".to_owned());
    }
    if matches!(
        scenario,
        ScreenshotState::VerifyOk
            | ScreenshotState::VerifyRunning
            | ScreenshotState::VerifyFailedIndex
    ) && page != 2
    {
        return Err("official file check screenshots must use page 2".to_owned());
    }
    if scenario == ScreenshotState::ModsScrolled && page != 4 {
        return Err("mods-scrolled screenshots must use page 4".to_owned());
    }
    if scenario == ScreenshotState::ResourceConfig && page != 4 {
        return Err("config-resources screenshots must use page 4".to_owned());
    }
    if scenario == ScreenshotState::ModConfig && page != 4 {
        return Err("config-mods screenshots must use page 4".to_owned());
    }
    if matches!(
        scenario,
        ScreenshotState::ToolchainField
            | ScreenshotState::HostToolsMissing
            | ScreenshotState::HostToolsMismatch
            | ScreenshotState::HostToolsReady
            | ScreenshotState::ShaderVerified
            | ScreenshotState::ShaderMissing
            | ScreenshotState::ShaderMissingScrolled
            | ScreenshotState::ShaderTampered
            | ScreenshotState::MavenPartial
            | ScreenshotState::MavenReady
    ) && page != 2
    {
        return Err("toolchain and Maven screenshots must use page 2".to_owned());
    }
    if scenario == ScreenshotState::PreflightError && page != 5 {
        return Err("real-preflight-error screenshots must use page 5".to_owned());
    }
    if scenario == ScreenshotState::DiagnosticsChinese && page != 5 {
        return Err("diagnostics-chinese screenshots must use page 5".to_owned());
    }
    if matches!(
        scenario,
        ScreenshotState::BuildSuccess | ScreenshotState::BuildSuccessThree
    ) && page != 6
    {
        return Err("build-success screenshots must use page 6".to_owned());
    }
    let (mut harness, scale) = screenshot_harness(
        page,
        theme_mode,
        language,
        size,
        (native_ppp, display),
        scenario,
    )?;
    if scenario == ScreenshotState::PreflightError {
        let (progress, logs, error) = run_real_toolchain_preflight()?;
        let app = harness.state_mut();
        app.build_progress = progress;
        app.build_logs = logs.into();
        app.build_error = Some(error);
    }
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
        harness
            .get_by_label("local/example-mod-1.jar")
            .scroll_to_me();
        harness.run();
        let layout = harness.state().layout_rects;
        let Some((viewport, row)) = layout.config_scroll_viewport.zip(layout.mod_first_row) else {
            return Err("Mods tab has no first-row layout or scroll viewport".to_owned());
        };
        if !rect_contains_rect(viewport, row) {
            let adjustment = if row.max.y > viewport.max.y {
                row.max.y - viewport.max.y
            } else {
                row.min.y - viewport.min.y
            };
            let current_offset = harness.state().page_scroll_offset;
            harness.state_mut().page_scroll_offset = (current_offset + adjustment + 1.0).max(0.0);
            harness.run();
        }
        let layout = harness.state().layout_rects;
        let Some((viewport, row)) = layout.config_scroll_viewport.zip(layout.mod_first_row) else {
            return Err("Mods tab lost its first-row layout or scroll viewport".to_owned());
        };
        if !rect_contains_rect(viewport, row) {
            return Err(format!(
                "first mod row is not fully visible in the scrolled panel: {row:?} vs {viewport:?}"
            ));
        }
    }
    if scenario == ScreenshotState::ToolchainField {
        harness.get_by_label(language.text("doctor.paths")).click();
        harness.run();
    }
    if size[0] <= 800 && scale >= 1.5 {
        let state_key = match scenario {
            ScreenshotState::HostToolsMissing => Some("doctor.tools.missing"),
            ScreenshotState::HostToolsMismatch => Some("doctor.tools.hash_mismatch"),
            ScreenshotState::HostToolsReady => Some("item.ok"),
            _ => None,
        };
        if let Some(state_key) = state_key {
            harness
                .get_by_label(format!("Binaryen 133 · {}", language.text(state_key)).as_str())
                .scroll_to_me();
            harness.run();
        }
    }
    if scenario == ScreenshotState::ShaderMissingScrolled {
        scroll_to_shader_picker(&mut harness, language);
    }
    if matches!(
        scenario,
        ScreenshotState::BuildSuccess | ScreenshotState::BuildSuccessThree
    ) && size[0] <= 800
        && scale >= 1.5
    {
        harness
            .get_by_label(language.text("complete.artifacts"))
            .scroll_to_me();
        harness.run();
    }
    let image = harness.render()?;
    image
        .save(path)
        .map_err(|error| format!("could not save screenshot {}: {error}", path.display()))?;
    let mut report = serde_json::json!({ "native_pixels_per_point": native_ppp });
    harness.state().add_zoom_report(&mut report);
    Ok(report)
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
                tool_paths: &ToolPaths::default(),
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
            "terms-pre-accepted" => Self::TermsPreAccepted,
            "verify-ok" => Self::VerifyOk,
            "verify-running" => Self::VerifyRunning,
            "verify-failed-index" => Self::VerifyFailedIndex,
            "config-resources" => Self::ResourceConfig,
            "config-mods" => Self::ModConfig,
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
            "build-success-three" => Self::BuildSuccessThree,
            "toolchain-field" => Self::ToolchainField,
            "real-preflight-error" => Self::PreflightError,
            "tools-missing" => Self::ToolsMissing,
            "tools-optional" => Self::ToolsOptional,
            "tools-strict" => Self::ToolsStrict,
            "tools-python-exe" => Self::ToolsPythonExe,
            "tools-downloading" => Self::ToolsDownloading,
            "tools-failed" => Self::ToolsFailed,
            "tools-ready" => Self::ToolsReady,
            "host-tools-missing" => Self::HostToolsMissing,
            "host-tools-mismatch" => Self::HostToolsMismatch,
            "host-tools-ready" => Self::HostToolsReady,
            "shader-verified" => Self::ShaderVerified,
            "shader-missing" => Self::ShaderMissing,
            "shader-missing-scrolled" => Self::ShaderMissingScrolled,
            "shader-tampered" => Self::ShaderTampered,
            "maven-partial" => Self::MavenPartial,
            "maven-ready" => Self::MavenReady,
            "diagnostics-chinese" => Self::DiagnosticsChinese,
            _ => return Err(format!("unknown screenshot state: {value}")),
        })
    }
}

fn scroll_to_shader_picker(
    harness: &mut egui_kittest::Harness<'_, BuilderApp>,
    language: Language,
) {
    use egui_kittest::kittest::Queryable;
    harness
        .get_by_label(language.text("doctor.shader.choose"))
        .scroll_to_me();
    harness.run();
}

fn config_field_label(
    ui: &mut Ui,
    language: Language,
    palette: Palette,
    width: f32,
    label: &str,
    applied: bool,
) -> egui::Response {
    let mut label_response = None;
    ui.vertical(|ui| {
        ui.set_width(width);
        ui.set_max_width(width);
        label_response = Some(ui.label(label));
        if applied {
            ui.label(
                RichText::new(language.text("config.applied_chip"))
                    .size(theme::snap(ui, 11.0))
                    .color(palette.accent),
            );
        } else {
            Frame::new()
                .fill(palette.tint(palette.info, 0.05))
                .stroke(Stroke::new(1.0, palette.info))
                .corner_radius(CornerRadius::same(4))
                .inner_margin(egui::Margin::symmetric(6, 2))
                .show(ui, |ui| {
                    ui.label(
                        RichText::new(language.text("config.not_applied_chip"))
                            .size(theme::snap(ui, 11.0))
                            .color(palette.info),
                    );
                });
        }
    });
    label_response.expect("configuration label is rendered")
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
        egui::FontId::proportional(theme::snap(ui, 22.0)),
        palette.text,
    );
    ui.painter().text(
        egui::pos2(center.x, center.y + 19.0),
        egui::Align2::CENTER_CENTER,
        stage,
        egui::FontId::proportional(theme::snap(ui, 12.0)),
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
                    ui.label(
                        RichText::new(value)
                            .size(theme::snap(ui, 12.0))
                            .color(palette.secondary),
                    );
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

fn human_bytes(bytes: u64) -> String {
    if bytes == 0 {
        return String::new();
    }
    if bytes >= 1_000_000_000 {
        format!("{:.1} GB", bytes as f64 / 1_000_000_000.0)
    } else if bytes >= 1_000_000 {
        format!("{:.1} MB", bytes as f64 / 1_000_000.0)
    } else {
        format!("{:.0} KB", bytes as f64 / 1_000.0)
    }
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
        .flat_map(|path| {
            let path = path.to_string_lossy();
            [
                path.to_string(),
                path.replace('\\', "/"),
                path.replace('\\', "\\\\"),
            ]
        })
        .collect::<Vec<_>>();
    let usernames = ["USER", "USERNAME"]
        .into_iter()
        .filter_map(|variable| std::env::var(variable).ok())
        .filter(|name| !name.trim().is_empty())
        .collect::<Vec<_>>();
    let mut sanitized = sanitize_paths(value, &homes, &usernames);
    sanitized = redact_profile_paths(&sanitized);
    sanitized
}

fn redact_profile_paths(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    let mut rest = value;
    loop {
        let lowered = rest.to_ascii_lowercase();
        let Some((index, marker)) = ["\\users\\", "/users/", "/home/"]
            .into_iter()
            .filter_map(|marker| lowered.find(marker).map(|index| (index, marker)))
            .min_by_key(|item| item.0)
        else {
            break;
        };
        let marker_start = index + marker.len();
        output.push_str(&rest[..marker_start]);
        output.push_str("<user-home>");
        let after = &rest[marker_start..];
        let Some(end) = after.find(['\\', '/', '\r', '\n']) else {
            rest = "";
            break;
        };
        rest = &after[end..];
    }
    output.push_str(rest);
    output
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

fn write_diagnostics(path: &Path, value: &str) -> io::Result<()> {
    let mut bytes = Vec::with_capacity(value.len() + 3);
    bytes.extend_from_slice(b"\xEF\xBB\xBF");
    bytes.extend_from_slice(value.as_bytes());
    fs::write(path, bytes)
}

fn copy_diagnostics(context: &egui::Context, value: &str) {
    context.copy_text(value.to_owned());
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

fn install_cjk_font(context: &egui::Context, use_system_fonts: bool) -> Vec<String> {
    context.set_missing_glyph_policy(egui::MissingGlyphPolicy::Tofu);
    if !use_system_fonts || std::env::var_os("GAIUS_BUILDER_NO_SYSTEM_FONTS").is_some() {
        context.set_font_providers(vec![]);
        context.set_fonts(egui::FontDefinitions::default());
        return Vec::new();
    }
    static FONTS: std::sync::OnceLock<(egui::FontDefinitions, Vec<String>)> =
        std::sync::OnceLock::new();
    let (definitions, sources) = FONTS.get_or_init(system_font_definitions);
    context.set_fonts(definitions.clone());
    sources.clone()
}

fn system_font_definitions() -> (egui::FontDefinitions, Vec<String>) {
    #[cfg(windows)]
    let windows_fonts =
        PathBuf::from(std::env::var_os("WINDIR").unwrap_or_else(|| "C:/Windows".into()))
            .join("Fonts");
    #[cfg(not(windows))]
    let windows_fonts = PathBuf::from("C:/Windows/Fonts");
    let msyh_ttc = windows_fonts.join("msyh.ttc");
    let msyh_ttf = windows_fonts.join("msyh.ttf");
    let simhei = windows_fonts.join("simhei.ttf");
    let segoe_symbols = windows_fonts.join("seguisym.ttf");
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
        (msyh_ttc.to_str().unwrap_or_default(), 0),
        (msyh_ttf.to_str().unwrap_or_default(), 0),
        (simhei.to_str().unwrap_or_default(), 0),
        ("/System/Library/Fonts/PingFang.ttc", 0),
        ("/Library/Fonts/Arial Unicode.ttf", 0),
    ];
    let symbol_candidates = [
        (segoe_symbols.to_str().unwrap_or_default(), 0),
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
    let mut sources = Vec::new();
    let mut append_font = |name: &str, path: &str, index: u32| -> bool {
        if let Ok(bytes) = fs::read(path) {
            if let Some(file) = Path::new(path).file_name() {
                sources.push(format!("{name}: {}#{index}", file.to_string_lossy()));
            }
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
    (fonts, sources)
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

    #[test]
    fn offscreen_preview_resolves_saved_zoom_corrupt_settings_and_cli_override() {
        let work = std::env::temp_dir().join(format!("gaius-preview-zoom-{}", std::process::id()));
        fs::create_dir(&work).unwrap();
        let settings = work.join("ui-settings.json");
        let cases = [
            (r#"{"zoom":"150"}"#, None, 1.5, 1.5, false, "settings"),
            ("{broken", None, 1.0, 1.0, true, "default"),
            (r#"{"zoom":"auto"}"#, None, 1.0, 1.0, true, "settings"),
            ("{}", None, 1.0, 1.0, true, "default"),
            (r#"{"zoom":"150"}"#, Some(2.5), 2.5, 2.0, false, "cli"),
        ];
        for (saved, scale, requested, effective, auto, source) in cases {
            fs::write(&settings, saved).unwrap();
            let display = DisplayOptions {
                work: Some(work.clone()),
                scale,
            };
            let (mut harness, _) = screenshot_harness(
                1,
                ScreenTheme::Dark,
                Language::Chinese,
                [800, 540],
                (2.0, &display),
                ScreenshotState::Normal,
            )
            .unwrap();
            harness.run();
            let mut report = serde_json::json!({});
            harness.state().add_zoom_report(&mut report);
            assert_eq!(
                report,
                serde_json::json!({
                    "requested_zoom": requested, "effective_zoom": effective,
                    "requested_zoom_auto": auto, "zoom_source": source,
                })
            );
            assert_eq!(fs::read_to_string(&settings).unwrap(), saved);
        }
        fs::remove_file(settings).unwrap();
        fs::remove_dir(work).unwrap();
    }

    #[test]
    fn completion_text_scrolls_fully_into_view_at_high_dpi() {
        let display = DisplayOptions {
            work: None,
            scale: Some(1.0),
        };
        for (theme, language, size, native_ppp) in [
            (ScreenTheme::Dark, Language::Chinese, [960, 620], 2.0),
            (ScreenTheme::Light, Language::English, [800, 540], 1.5),
        ] {
            let (mut harness, _) = screenshot_harness(
                6,
                theme,
                language,
                size,
                (native_ppp, &display),
                ScreenshotState::BuildSuccess,
            )
            .unwrap();
            let audit = |app: &BuilderApp| {
                app.layout_rects
                    .text_audits
                    .iter()
                    .flatten()
                    .find(|audit| audit.name == "complete.options_not_applied")
                    .copied()
                    .expect("final paragraph is audited")
            };
            harness.run();
            let before = audit(harness.state());
            assert!(
                before.rect.bottom() > before.clip.bottom(),
                "fixture must overflow: {before:?}"
            );
            harness.hover_at(before.clip.center());
            harness.event(egui::Event::MouseWheel {
                unit: egui::MouseWheelUnit::Point,
                delta: egui::vec2(0.0, -500.0),
                phase: egui::TouchPhase::Move,
                source: egui::MouseWheelSource::Wheel,
                modifiers: egui::Modifiers::NONE,
            });
            harness.run();
            let after = audit(harness.state());
            assert!(
                rect_contains_rect(after.clip, after.rect),
                "final paragraph clipped: {after:?}"
            );
            assert!(
                (after.clip.bottom() - after.rect.bottom()) * native_ppp >= 7.5,
                "final paragraph needs descender clearance beyond its text box: {after:?}"
            );
        }
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

    fn collect_text_galleys(
        shape: &egui::Shape,
        clip: Rect,
        output: &mut Vec<(String, bool, usize, Rect, Rect)>,
    ) {
        match shape {
            egui::Shape::Text(text) => {
                let displayed_chars = text
                    .galley
                    .rows
                    .iter()
                    .map(|row| row.row.glyphs.len())
                    .sum();
                output.push((
                    text.galley.text().to_owned(),
                    text.galley.elided,
                    displayed_chars,
                    text.galley.rect.translate(text.pos.to_vec2()),
                    clip,
                ));
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
            for (_, _, _, galley, clip) in rendered
                .iter()
                .filter(|(_, _, _, galley, _)| rects_approximately_equal(*galley, audit.rect))
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

    fn assert_full_footer_disclaimer(
        harness: &egui_kittest::Harness<'_, BuilderApp>,
        page: usize,
        size: [u32; 2],
        scale: f32,
        language: Language,
    ) {
        let layout = harness.state().layout_rects;
        let audit = layout
            .text_audits
            .iter()
            .flatten()
            .find(|audit| audit.name == "footer.disclaimer")
            .expect("full footer disclaimer is audited");
        assert!(
            rect_contains_rect(layout.footer, audit.rect),
            "footer disclaimer text rect is clipped at page {page}, size {size:?}, scale {scale}, language {language:?}: {:?} vs {:?}",
            audit.rect,
            layout.footer
        );
        assert!(
            rect_contains_rect(audit.clip, audit.rect),
            "footer disclaimer exceeds its clip at page {page}, size {size:?}, scale {scale}, language {language:?}: {:?} vs {:?}",
            audit.rect,
            audit.clip
        );
        assert!(
            !rects_overlap(layout.footer, layout.bottom_bar),
            "footer overlaps the bottom bar at page {page}, size {size:?}, scale {scale}, language {language:?}: {:?} vs {:?}",
            layout.footer,
            layout.bottom_bar
        );

        let expected = language.text("footer.disclaimer");
        let mut rendered = Vec::new();
        for clipped in &harness.output().shapes {
            collect_text_galleys(&clipped.shape, clipped.clip_rect, &mut rendered);
        }
        let expected_chars = expected.chars().count();
        assert!(
            rendered
                .iter()
                .any(|(text, elided, displayed_chars, rect, clip)| {
                    rects_approximately_equal(*rect, audit.rect)
                        && text == expected
                        && !elided
                        && *displayed_chars >= expected_chars
                        && rect_contains_rect(*clip, *rect)
                }),
            "footer disclaimer renders fewer than {expected_chars} characters or is elided at page {page}, size {size:?}, scale {scale}, language {language:?}"
        );
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
    fn saved_terms_restore_both_gates_and_revoke_clears_them() {
        let mut app = test_app();
        app.page = 3;
        let path = std::env::temp_dir().join(format!("terms-restart-{}.json", std::process::id()));
        app.terms_record_path = Some(path.clone());
        terms_acceptance::save(&path, &app.terms_hash, terms_acceptance::now()).unwrap();
        app.reload_terms_acceptance();
        assert!(app.terms_scrolled && app.terms_checked && app.can_advance());
        let initial_language = app.language;
        app.language = match initial_language {
            Language::Chinese => Language::English,
            Language::English => Language::Chinese,
        };
        app.reload_terms_acceptance();
        assert!(!app.terms_scrolled && !app.terms_checked && !app.can_advance());
        app.language = initial_language;
        app.reload_terms_acceptance();
        assert!(app.can_advance());
        app.revoke_terms_acceptance();
        assert!(!path.exists());
        assert!(!app.terms_scrolled && !app.terms_checked && !app.can_advance());
    }

    #[test]
    fn verification_blocks_next_and_build_until_each_selected_group_passes() {
        let mut app = test_app();
        app.page = 2;
        app.terms_checked = true;
        for state in [
            ScreenshotState::VerifyRunning,
            ScreenshotState::VerifyFailedIndex,
        ] {
            app.mock_verification(state);
            assert!(!app.can_advance());
            assert_eq!(app.build_gate(), Some("verify.blocked"));
            app.start_build();
            assert!(app.build_task.is_none());
            assert_eq!(
                app.build_error.as_deref(),
                Some(app.language.text("verify.blocked"))
            );
        }
        app.start_verification();
        assert!(app.can_advance());
        assert!(app.build_gate().is_none());
        app.selected_versions.push("1.21.11".to_owned());
        assert!(!app.can_advance());
        app.mock_verification(ScreenshotState::VerifyOk);
        app.verification.as_mut().unwrap().reports[1] = VerifyReport::mock_failed_index();
        assert!(!app.can_advance());
        app.start_verification();
        assert!(app.can_advance());
        app.config.mappings_path = "different-mappings.txt".to_owned();
        assert!(!app.can_advance());
        app.start_verification();
        app.minecraft_dir = "different-directory".to_owned();
        assert!(!app.can_advance());
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
                        app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
                    app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
        assert!(ScreenshotState::parse("config-resources").is_ok());
        assert!(ScreenshotState::parse("config-mods").is_ok());
        assert!(ScreenshotState::parse("mods-scrolled").is_ok());
        assert!(ScreenshotState::parse("tools-missing").is_ok());
        assert!(ScreenshotState::parse("tools-downloading").is_ok());
        assert!(ScreenshotState::parse("tools-failed").is_ok());
        assert!(ScreenshotState::parse("tools-ready").is_ok());
    }

    #[test]
    fn missing_tools_block_next_and_ready_tools_allow_it() {
        let missing = test_app_state(2, ScreenshotState::ToolsMissing);
        assert!(!missing.can_advance());
        assert!(!missing.step_complete(2));
        let ready = test_app_state(2, ScreenshotState::ToolsReady);
        assert!(ready.can_advance());
        assert!(ready.step_complete(2));
    }

    #[test]
    fn absent_kernel_tools_block_next_and_build_only_in_strict_mode() {
        for (state, strict) in [
            (ScreenshotState::ToolsOptional, false),
            (ScreenshotState::ToolsStrict, true),
        ] {
            let mut app = test_app_state(2, state);
            app.terms_checked = true;
            assert_eq!(app.config.strict_kernel, strict);
            assert_eq!(app.can_advance(), !strict);
            assert_eq!(app.build_gate(), strict.then_some("doctor.tools.blocked"));
            let report = app.doctor_report.as_mut().unwrap();
            for tool in report.tools.iter_mut().filter(|tool| {
                matches!(
                    tool.id.as_str(),
                    "rust-cargo" | "wasm32-target" | "cargo-cache"
                )
            }) {
                tool.state = "present".to_owned();
            }
            assert!(app.can_advance());
            assert_eq!(app.build_gate(), None);
        }
    }

    const T9_STATES: [(&str, ScreenshotState); 5] = [
        ("shader-verified", ScreenshotState::ShaderVerified),
        ("shader-missing", ScreenshotState::ShaderMissing),
        ("shader-tampered", ScreenshotState::ShaderTampered),
        ("maven-partial", ScreenshotState::MavenPartial),
        ("maven-ready", ScreenshotState::MavenReady),
    ];

    #[test]
    fn t9_local_tool_states_gate_the_actual_next_and_build_buttons() {
        for (name, state) in T9_STATES {
            assert_eq!(ScreenshotState::parse(name).unwrap(), state);
            let ready = matches!(
                state,
                ScreenshotState::ShaderVerified | ScreenshotState::MavenReady
            );
            for (page, label) in [(2, "Next"), (5, Language::English.text("build.start"))] {
                let mut harness = egui_kittest::Harness::builder()
                    .with_size(Vec2::new(960.0, 620.0))
                    .build_eframe(|creation| {
                        let mut app = BuilderApp::with_state(
                            &creation.egui_ctx,
                            Language::English,
                            ThemeMode::Dark,
                            page,
                            false,
                            state,
                        );
                        app.terms_checked = true;
                        app
                    });
                harness.run();
                assert_eq!(
                    harness.get_by_label(label).accesskit_node().is_disabled(),
                    !ready,
                    "{name} page {page}"
                );
                if page == 2
                    && matches!(
                        state,
                        ScreenshotState::ShaderMissing | ScreenshotState::ShaderTampered
                    )
                {
                    harness.get_by_label(Language::English.text("doctor.shader.choose"));
                    harness.get_by_label(Language::English.text("doctor.retry"));
                    assert!(
                        harness
                            .query_by_label(Language::English.text("doctor.tools.download"))
                            .is_none(),
                        "{name}"
                    );
                }
            }
        }
    }

    #[test]
    fn t9_maven_confirmation_lists_the_shared_download_once_for_three_profiles() {
        let language = Language::English;
        let mut harness = egui_kittest::Harness::builder()
            .with_size(Vec2::new(960.0, 620.0))
            .build_eframe(|creation| {
                let mut app = BuilderApp::with_state(
                    &creation.egui_ctx,
                    language,
                    ThemeMode::Dark,
                    2,
                    false,
                    ScreenshotState::MavenPartial,
                );
                app.selected_versions = VERSION_IDS.map(str::to_owned).to_vec();
                app.mock_verification(ScreenshotState::Normal);
                app
            });
        harness.run();
        for version in VERSION_IDS {
            harness.get_by_label(&format!(
                "{} · {version} · {}",
                language.text("doctor.maven"),
                language.text("doctor.tools.partial")
            ));
        }
        let download = language.text("doctor.tools.download");
        harness.get_by_label(download).scroll_to_me();
        harness.run();
        harness.get_by_label(download).click();
        harness.run();
        assert!(harness.state().tools_confirm);
        assert_eq!(
            harness
                .query_all_by_label(&format!(
                    "{} · {} · Source: repo.maven.apache.org",
                    language.text("doctor.maven"),
                    human_bytes(86_143_464)
                ))
                .count(),
            1
        );
        assert!(!harness.state().can_advance());
    }

    #[test]
    fn t9_tool_state_layout_fits_both_capture_sizes_and_languages() {
        for (name, state) in T9_STATES {
            for (size, scale) in [([960, 620], 1.0), ([800, 540], 1.5)] {
                for (theme, language) in [
                    (ThemeMode::Dark, Language::Chinese),
                    (ThemeMode::Dark, Language::English),
                    (ThemeMode::Light, Language::Chinese),
                    (ThemeMode::Light, Language::English),
                ] {
                    let mut harness = egui_kittest::Harness::builder()
                        .with_size(Vec2::new(size[0] as f32, size[1] as f32))
                        .with_context_setup(move |context| context.set_zoom_factor(scale))
                        .with_render_cursor(false)
                        .build_eframe(|creation| {
                            let mut app = BuilderApp::with_state(
                                &creation.egui_ctx,
                                language,
                                theme,
                                2,
                                false,
                                state,
                            );
                            app.config.ui_scale = UiScale::for_factor(scale).unwrap();
                            app.config.reduce_motion = true;
                            app
                        });
                    harness.set_size(Vec2::new(size[0] as f32 / scale, size[1] as f32 / scale));
                    harness.run();
                    assert_audited_text_fits_clip(&harness, 2, name, size, scale, language);
                    let layout = harness.state().layout_rects;
                    assert!(
                        layout.content_used.right() <= layout.content.right() + 12.0,
                        "{name} {language:?} {size:?} {scale}"
                    );
                    assert!(rect_contains_rect(layout.viewport, layout.action_row));
                    assert!(rect_contains_rect(layout.viewport, layout.bottom_bar));
                    if state == ScreenshotState::ShaderMissing && scale == 1.5 {
                        scroll_to_shader_picker(&mut harness, language);
                        let choose = harness.get_by_label(language.text("doctor.shader.choose"));
                        let file_prefix = language
                            .text("doctor.shader.expected")
                            .split_once("{value}")
                            .unwrap()
                            .0;
                        let files = harness.get_by_label_contains(file_prefix);
                        let content = harness.state().layout_rects.content;
                        assert!(
                            rect_contains_rect(content, choose.rect()),
                            "{language:?}: picker off-screen"
                        );
                        assert!(
                            rect_contains_rect(content, files.rect()),
                            "{language:?}: file list off-screen"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn changing_tool_paths_persists_each_override_and_rejects_a_queued_old_result() {
        let file = std::env::temp_dir().join(format!("t9-gui-paths-{}.json", std::process::id()));
        let mut app = test_app_state(2, ScreenshotState::ShaderVerified);
        app.tool_settings_file = Some(file.clone());
        app.live_verification = true;
        let control = ProcessControl::default();
        let (sender, receiver) = std::sync::mpsc::channel();
        sender.send(Ok(MockBackend.doctor().unwrap())).unwrap();
        app.doctor_receiver = Some(DoctorTask {
            receiver,
            control: control.clone(),
        });
        app.doctor_control = Some(control.clone());
        let settings = ToolPathSettings {
            toolchain_dir: "/home/private-gui-user/cache".to_owned(),
            shader_toolchain: r"C:\Users\private-shader-user\modules".to_owned(),
        };
        app.set_tool_path_settings(settings.clone());
        assert!(control.is_cancelled());
        assert!(app.tool_check_at.is_some());
        app.poll_doctor();
        assert!(app.doctor_report.is_none() && !app.can_advance());
        assert_eq!(ToolPathSettings::load(&file), settings);
        for diagnostics in [app.diagnostics(), app.doctor_diagnostics()] {
            assert!(
                !diagnostics.contains("private-gui-user")
                    && !diagnostics.contains("private-shader-user")
            );
            assert!(diagnostics.contains("--toolchain-dir: /home/<user-home>/cache"));
            assert!(diagnostics.contains(r"--shader-toolchain: C:\Users\<user-home>\modules"));
        }
        app.set_tool_path_settings(ToolPathSettings {
            shader_toolchain: String::new(),
            ..settings
        });
        let restored = ToolPathSettings::load(&file).paths();
        assert!(restored.toolchain_dir.is_some());
        assert!(restored.shader_toolchain.is_none());
        fs::remove_file(file).unwrap();
    }

    fn test_app_state(page: usize, state: ScreenshotState) -> BuilderApp {
        let context = egui::Context::default();
        BuilderApp::with_state(
            &context,
            Language::English,
            ThemeMode::Dark,
            page,
            false,
            state,
        )
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
    fn reproducibility_customization_tracks_only_settings_passed_to_build() {
        let mut app = test_app();
        assert!(!app.config_is_custom());

        app.config.builtin_pack = true;
        app.config.loader = 1;
        app.config.mods.push(ModRow {
            path: "local/mod.jar".to_owned(),
            supported: CompatGrade::Experimental,
            licensed: true,
        });
        app.config.resource_path = "local/not-added.zip".to_owned();
        assert!(!app.config_is_custom());

        app.config.quality = 0;
        assert!(app.config_is_custom());
        app.config.quality = 1;
        app.config.distance = 13;
        assert!(app.config_is_custom());
        app.config.distance = 12;
        app.config.backend = 1;
        assert!(app.config_is_custom());
        app.config.backend = 0;
        app.config.resource_paths.push(LocalAsset {
            path: "local/selected.zip".to_owned(),
            licensed: true,
        });
        assert!(app.config_is_custom());
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
        for (size, scale) in [
            ([800.0, 540.0], 1.0),
            ([800.0, 540.0], 1.25),
            ([800.0, 540.0], 1.5),
            ([800.0, 540.0], 2.0),
            ([960.0, 620.0], 1.0),
            ([1280.0, 800.0], 1.0),
            ([1920.0, 1080.0], 1.0),
        ] {
            for language in [Language::Chinese, Language::English] {
                for theme in [ScreenTheme::Dark, ScreenTheme::Light] {
                    let mut harness = egui_kittest::Harness::builder()
                        .with_size(Vec2::new(size[0], size[1]))
                        .with_pixels_per_point(1.0)
                        .with_context_setup(move |context| context.set_zoom_factor(scale))
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
                                ScreenshotState::BuildSuccessThree,
                            )
                        });
                    harness.set_size(Vec2::new(size[0] / scale, size[1] / scale));
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
                            "completion labels do not share a left edge for {size:?} at {scale}x, {language:?}, {theme:?}: {labels:?}"
                        );
                    }
                    for rect in values.iter().skip(1) {
                        assert!(
                            (rect.min.x - values[0].min.x).abs() <= 0.5,
                            "completion values do not share a left edge for {size:?} at {scale}x, {language:?}, {theme:?}: {values:?}"
                        );
                    }
                    let artifacts = layout
                        .completion_artifacts
                        .expect("artifact heading has a layout rect");
                    assert!(
                        (artifacts.min.x - labels[0].min.x).abs() <= 0.5,
                        "artifact heading is not left-aligned with the completion grid for {size:?} at {scale}x, {language:?}, {theme:?}: {artifacts:?}, {:?}",
                        labels[0]
                    );
                    let copy = layout
                        .completion_copy
                        .expect("path copy button has a layout rect");
                    let path = values[1];
                    assert!(
                        copy.min.x >= path.max.x - 0.5 && copy.min.x - path.max.x <= 8.5,
                        "copy button is not directly after the path for {size:?} at {scale}x, {language:?}, {theme:?}: {path:?}, {copy:?}"
                    );
                    let mut previous_artifact_label: Option<Rect> = None;
                    for index in 0..3 {
                        let artifact_label = layout.completion_artifact_labels[index]
                            .expect("every version status cell has a layout rect");
                        let artifact_path = layout.completion_artifact_paths[index]
                            .expect("every artifact path has a layout rect");
                        let open = layout.completion_artifact_open_buttons[index]
                            .expect("every artifact folder button has a layout rect");
                        assert!(
                            (artifact_label.min.x - labels[0].min.x).abs() <= 0.5,
                            "artifact status column does not align for {size:?} at {scale}x, {language:?}, {theme:?}: {artifact_label:?}, {:?}",
                            labels[0]
                        );
                        assert!(
                            (artifact_path.min.x - values[0].min.x).abs() <= 0.5,
                            "artifact path column does not align for {size:?} at {scale}x, {language:?}, {theme:?}: {artifact_path:?}, {:?}",
                            values[0]
                        );
                        assert!(
                            (artifact_label.min.y - artifact_path.min.y).abs() <= 0.5,
                            "artifact status and path do not share a row for {size:?} at {scale}x, {language:?}, {theme:?}: {artifact_label:?}, {artifact_path:?}"
                        );
                        if let Some(previous) = previous_artifact_label {
                            assert!(
                                artifact_label.min.y >= previous.max.y - 0.5,
                                "artifact versions do not stack as separate rows for {size:?} at {scale}x, {language:?}, {theme:?}: {previous:?}, {artifact_label:?}"
                            );
                        }
                        previous_artifact_label = Some(artifact_label);
                        assert!(
                            open.min.x >= artifact_path.max.x - 0.5
                                && open.min.x - artifact_path.max.x <= 8.5,
                            "artifact folder button is not directly after its path for {size:?} at {scale}x, {language:?}, {theme:?}: {artifact_path:?}, {open:?}"
                        );
                    }
                }
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
                app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
                        app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
        let viewports = [
            ([960, 620], 1.0, "960x620", "100"),
            ([800, 540], 1.5, "800x540", "150"),
        ];
        let mut index = String::from(
            "# 完成页截图\n\n| 窗口 | 缩放 | 主题 | 语言 | 截图 |\n|---|---:|---|---|---|\n",
        );
        for language in [Language::Chinese, Language::English] {
            for theme in [ScreenTheme::Dark, ScreenTheme::Light] {
                let theme_code = theme_slug(theme);
                let language_code = language.code();
                for (size, scale, size_label, scale_label) in viewports {
                    let name = format!(
                        "completion-{theme_code}-{language_code}-{size_label}-{scale_label}.png"
                    );
                    render_screenshot(
                        &directory.join(&name),
                        6,
                        theme,
                        language,
                        size,
                        scale,
                        "build-success",
                    )
                    .expect("completion page screenshot renders");
                    index.push_str(&format!(
                        "| {size_label} | {scale_label}% | {theme_code} | {language_code} | [{name}]({name}) |\n"
                    ));
                }
            }
        }
        let three_versions = "completion-three-versions-dark-zh-1280x800-100.png";
        render_screenshot(
            &directory.join(three_versions),
            6,
            ScreenTheme::Dark,
            Language::Chinese,
            [1280, 800],
            1.0,
            "build-success-three",
        )
        .expect("three-version completion screenshot renders");
        index.push_str(&format!(
            "\n三版本成功结果：[{three_versions}]({three_versions})\n"
        ));
        fs::write(directory.join("INDEX.md"), index).expect("completion gallery index is writable");
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
    fn diagnostics_redact_chinese_windows_user_paths_and_keep_unicode_project_path() {
        let value = sanitize_paths("failure at C:\\Users\\张三\\桌面\\构建目录\\26.3", &[], &[]);
        let value = redact_profile_paths(&value);
        assert_eq!(
            value,
            "failure at C:\\Users\\<user-home>\\桌面\\构建目录\\26.3"
        );
    }

    #[test]
    fn diagnostics_file_uses_utf8_bom() {
        let root = std::env::temp_dir().join(format!("gaius-diagnostics-{}", std::process::id()));
        let path = root.join("diagnostics.txt");
        fs::create_dir_all(&root).unwrap();
        write_diagnostics(&path, "路径 C:\\工作\\构建").unwrap();
        let bytes = fs::read(&path).unwrap();
        assert!(bytes.starts_with(b"\xEF\xBB\xBF"));
        assert_eq!(
            std::str::from_utf8(&bytes[3..]).unwrap(),
            "路径 C:\\工作\\构建"
        );
        fs::remove_dir_all(root).unwrap();
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
                for (name, state) in [
                    ("config-settings", "normal"),
                    ("config-resources", "config-resources"),
                    ("config-mods", "config-mods"),
                ] {
                    render_screenshot(
                        &directory.join(format!(
                            "{name}-{}-{}-960x620.png",
                            theme_slug(theme),
                            language.code()
                        )),
                        4,
                        theme,
                        language,
                        [960, 620],
                        1.0,
                        state,
                    )
                    .expect("configuration-tab screenshot renders");
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
                app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
                app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
    fn full_footer_disclaimer_fits_every_size_scale_and_language() {
        let sizes = [
            ([800, 540], "800x540"),
            ([960, 620], "960x620"),
            ([1280, 800], "1280x800"),
            ([1920, 1080], "1920x1080"),
        ];
        let scales = [(1.0, "100"), (1.25, "125"), (1.5, "150"), (2.0, "200")];

        for language in [Language::Chinese, Language::English] {
            for (size, size_label) in sizes {
                for (scale, _) in scales {
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
                                4,
                                false,
                            );
                            app.config.ui_scale = UiScale::for_factor(scale).unwrap();
                            app.config.reduce_motion = true;
                            app
                        });
                    harness.set_size(Vec2::new(size[0] as f32 / scale, size[1] as f32 / scale));
                    harness.run();

                    assert_full_footer_disclaimer(&harness, 4, size, scale, language);

                    if size == [800, 540] && scale >= 1.5 {
                        assert!(
                            harness.state().layout_rects.content_scroll_needed,
                            "central content should scroll at {size_label}, scale {scale}, language {language:?}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn native_dpi_layout_and_text_grid_matrix() {
        use egui::accesskit::{Action, Role};
        let cases = [
            (1, ScreenshotState::Normal),
            (2, ScreenshotState::Normal),
            (3, ScreenshotState::Normal),
            (4, ScreenshotState::Normal),
            (5, ScreenshotState::Normal),
            (6, ScreenshotState::Normal),
            (4, ScreenshotState::ModConfig),
            (6, ScreenshotState::BuildSuccess),
        ];
        let appearances = [
            (ThemeMode::Dark, Language::Chinese),
            (ThemeMode::Light, Language::English),
            (ThemeMode::Light, Language::Chinese),
            (ThemeMode::Dark, Language::English),
        ];
        for (native_index, native) in [1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]
            .into_iter()
            .enumerate()
        {
            for (zoom_index, choice) in [
                UiScale::Auto,
                UiScale::P125,
                UiScale::P150,
                UiScale::P200,
                UiScale::P250,
            ]
            .into_iter()
            .enumerate()
            {
                for (case_index, (page, state)) in cases.into_iter().enumerate() {
                    let (theme, language) =
                        appearances[(native_index + zoom_index + case_index) % appearances.len()];
                    let size = if (zoom_index + case_index) % 2 == 0 {
                        [800, 540]
                    } else {
                        [960, 620]
                    };
                    let native_size = Vec2::new(size[0] as f32, size[1] as f32);
                    let zoom = ui_scale::fit_zoom(choice.factor(), native_size);
                    let mut harness = egui_kittest::Harness::builder()
                        .with_size(native_size / zoom)
                        .with_pixels_per_point(native)
                        .with_context_setup(move |ctx| {
                            ctx.options_mut(|options| options.zoom_factor = zoom)
                        })
                        .build_eframe(|creation| {
                            let mut app = BuilderApp::with_state(
                                &creation.egui_ctx,
                                language,
                                theme,
                                page,
                                false,
                                state,
                            );
                            app.config.ui_scale = choice;
                            app.config.reduce_motion = true;
                            app
                        });
                    harness.set_size(native_size / zoom).run();
                    let ppp = native * zoom;
                    assert!((harness.output().pixels_per_point - ppp).abs() < 0.001);
                    let layout = harness.state().layout_rects;
                    assert!(
                        (layout.viewport.size() * zoom - native_size)
                            .abs()
                            .max_elem()
                            * native
                            <= 1.0,
                        "{native}x{zoom}, {size:?}: viewport {:?}",
                        layout.viewport
                    );
                    let case = format!(
                        "{native}×{zoom}, {size:?}, {page}/{state:?}, {language:?}/{theme:?}"
                    );
                    for panel in [
                        layout.top_bar,
                        layout.rail,
                        layout.content,
                        layout.footer,
                        layout.bottom_bar,
                    ] {
                        assert!(
                            rect_contains_rect(layout.viewport, panel),
                            "{case}: panel outside viewport: {panel:?}"
                        );
                    }
                    assert!(
                        rect_contains_rect(layout.top_bar, layout.top_bar_content),
                        "{case}: clipped toolbar"
                    );
                    assert_full_footer_disclaimer(&harness, page, size, zoom, language);
                    for action in layout.action_widgets.iter().flatten() {
                        assert!(
                            rect_contains_rect(layout.action_row, *action),
                            "{case}: clipped pinned action {action:?} vs {:?}",
                            layout.action_row
                        );
                        assert!(
                            action.height() >= 23.99 && action.width() >= 23.99,
                            "{case}: small pinned action {action:?}"
                        );
                    }
                    let next = layout.nav_next.unwrap();
                    assert!(
                        rect_contains_rect(layout.bottom_bar, next)
                            && next.width() >= 39.99
                            && next.height() >= 39.99,
                        "{case}: Next is small or clipped: {next:?}"
                    );
                    if page == 5 || state == ScreenshotState::BuildSuccess {
                        let primary = layout.action_widgets[0].unwrap();
                        assert!(
                            primary.size().min_elem() >= 39.99,
                            "{case}: small primary action"
                        );
                    }
                    for node in harness.root().children_recursive() {
                        let a11y = node.accesskit_node();
                        if a11y.is_disabled()
                            || !a11y.data().supports_action(Action::Click)
                            || !matches!(
                                a11y.role(),
                                Role::Button
                                    | Role::CheckBox
                                    | Role::ComboBox
                                    | Role::RadioButton
                                    | Role::Tab
                            )
                        {
                            continue;
                        }
                        let rect = node.rect();
                        assert!(
                            rect.size().min_elem() >= 23.9,
                            "{case}: small {:?} {:?}: {rect:?}",
                            a11y.role(),
                            a11y.label()
                        );
                    }
                    for audit in layout.text_audits.iter().flatten() {
                        if rects_overlap(audit.rect, audit.clip) {
                            assert!(
                                audit.rect.left() >= audit.clip.left() - 0.5
                                    && audit.rect.right() <= audit.clip.right() + 0.5,
                                "{case}: horizontally clipped text: {}",
                                audit.name
                            );
                        }
                    }
                    for clipped in &harness.output().shapes {
                        assert_pixel_text(&clipped.shape, ppp, &case);
                    }
                }
            }
        }
    }

    fn assert_pixel_text(shape: &egui::Shape, ppp: f32, case: &str) {
        let is_integral = |points: f32| (points * ppp - (points * ppp).round()).abs() < 0.005;
        match shape {
            egui::Shape::Text(text) => {
                let job = &text.galley.job;
                for section in &job.sections {
                    let size = section.format.font_id.size;
                    assert!(
                        is_integral(size),
                        "{case}: fractional font size {size} at {ppp} PPP: {:?}",
                        job.text
                    );
                }
                for row in &text.galley.rows {
                    // The galley may include subpixel advance widths; row baselines and
                    // line heights are grid-aligned by epaint when hinting is enabled.
                    assert!(
                        is_integral(row.pos.y),
                        "{case}: fractional text row position: {:?}",
                        job.text
                    );
                }
            }
            egui::Shape::Vec(shapes) => {
                for shape in shapes {
                    assert_pixel_text(shape, ppp, case);
                }
            }
            _ => {}
        }
    }

    #[test]
    fn moving_between_native_dpis_preserves_zoom_and_reflows() {
        let zoom = 1.5;
        let size = Vec2::new(800.0, 540.0) / zoom;
        let mut ctx = egui::Context::default();
        let mut harness = egui_kittest::Harness::builder()
            .with_size(size)
            .with_pixels_per_point(1.25)
            .with_context_setup(move |ctx| ctx.options_mut(|options| options.zoom_factor = zoom))
            .build_eframe(|creation| {
                ctx = creation.egui_ctx.clone();
                let mut app = BuilderApp::with_state(
                    &creation.egui_ctx,
                    Language::Chinese,
                    ThemeMode::Light,
                    4,
                    false,
                    ScreenshotState::ModConfig,
                );
                app.config.ui_scale = UiScale::P150;
                app
            });
        for native in [1.25, 2.5, 1.5, 2.0, 1.25] {
            harness
                .input_mut()
                .viewports
                .get_mut(&egui::ViewportId::ROOT)
                .unwrap()
                .native_pixels_per_point = Some(native);
            harness.set_size(size).run();
            let app = harness.state();
            assert_eq!(app.config.ui_scale, UiScale::P150);
            assert!(app.compact_rail);
            let ppp = native * zoom;
            assert_eq!(harness.output().pixels_per_point, ppp);
            assert_eq!(app.theme_applied.unwrap().2, ppp);
            assert!(rect_contains_rect(
                app.layout_rects.viewport,
                app.layout_rects.nav_next.unwrap()
            ));
            let fonts = app.fonts_report(&ctx);
            if app
                .font_sources
                .iter()
                .any(|font| font.starts_with("system-cjk-fallback"))
            {
                assert_eq!(fonts["cjk_probe_has_glyphs"], true);
            }
            if app
                .font_sources
                .iter()
                .any(|font| font.starts_with("system-symbol-fallback"))
            {
                assert_eq!(fonts["symbol_probe_has_glyphs"], true);
            }
            for clipped in &harness.output().shapes {
                assert_pixel_text(&clipped.shape, ppp, "mixed-DPI monitor move");
            }
        }
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
            ("tools-missing", ScreenshotState::ToolsMissing),
            ("tools-optional", ScreenshotState::ToolsOptional),
            ("tools-strict", ScreenshotState::ToolsStrict),
            ("tools-python-exe", ScreenshotState::ToolsPythonExe),
            ("tools-downloading", ScreenshotState::ToolsDownloading),
            ("tools-failed", ScreenshotState::ToolsFailed),
            ("tools-ready", ScreenshotState::ToolsReady),
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
                            app.config.ui_scale = UiScale::for_factor(scale).unwrap();
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
                        app.config.ui_scale = UiScale::for_factor(2.0).unwrap();
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

    #[test]
    fn view_distance_first_run_hint_is_accessible_in_both_languages() {
        for language in [Language::Chinese, Language::English] {
            let mut harness = egui_kittest::Harness::builder()
                .with_size(Vec2::new(960.0, 620.0))
                .with_render_cursor(false)
                .build_eframe(|creation| {
                    BuilderApp::with_state(
                        &creation.egui_ctx,
                        language,
                        ThemeMode::Dark,
                        4,
                        false,
                        ScreenshotState::Normal,
                    )
                });
            harness.run();
            let hint = harness.get_by_label(language.text("config.distance_hint"));
            assert!(!hint.accesskit_node().is_hidden());
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
