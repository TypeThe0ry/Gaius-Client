//! Local patcher process adapter.
//!
//! Build progress uses JSON Lines. Each stdout line is one JSON object with
//! `schema: "gaius-patcher-progress-v1"`, a phase, step, percentage, English
//! message, and optional error. Unknown JSON fields and non-JSON stdout lines
//! are ignored for control flow and retained in bounded raw output.
//! `tools status --json` emits one `gaius-patcher-tools-v1` record per tool.
//! `required` is a boolean, `requiredReason` is its reason code, and
//! `requiredFor` scopes required rows to profiles. `resolvedCommand` can
//! identify the Python invocation that the patcher selected. States
//! are `present`, `missing`, `hash-mismatch`, and `unsupported`. Size zero
//! indicates an unmeasured size. Install events use a separate schema,
//! `gaius-patcher-tools-progress-v1`, with aggregate `pct`, `bytes`,
//! `totalBytes`, `id`, `step`, and nullable `error` fields.
//! `doctor <version> --verify-files --json` emits UTF-8 JSON Lines with
//! `schema: "gaius-patcher-verify-v1"`. Each `progress` or `group` event has
//! a group ID and `checked`/`total` counters. A terminal `group` has a `failed`
//! array of relative paths, nullable expected/actual hashes, and reasons;
//! optional `failureCount` records the count when details are capped.

use std::env;
use std::ffi::OsString;
use std::io::{self, BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{ChildStdout, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

const CAPTURE_LIMIT: usize = 256 * 1024;
const STREAM_LINE_LIMIT: usize = 16 * 1024;
const VERIFY_LINE_LIMIT: usize = 1024 * 1024;
const VERIFY_SCHEMA: &str = "gaius-patcher-verify-v1";
const LINE_TRUNCATION_MARKER: &str = " …[line truncated]";

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckState {
    Ready,
    Warning,
    Missing,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DoctorItem {
    pub value: String,
    pub state: CheckState,
    pub exists: Option<bool>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct VersionStatus {
    pub id: String,
    pub installed: bool,
    pub verified: bool,
    pub reason: Option<String>,
    pub required_java: Option<u32>,
    pub state: CheckState,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq)]
#[serde(rename_all = "kebab-case")]
pub enum VerifyGroup {
    Client,
    Libraries,
    AssetIndex,
    AssetObjects,
}

impl VerifyGroup {
    pub const ALL: [Self; 4] = [
        Self::Client,
        Self::Libraries,
        Self::AssetIndex,
        Self::AssetObjects,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Client => "client",
            Self::Libraries => "libraries",
            Self::AssetIndex => "asset-index",
            Self::AssetObjects => "asset-objects",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq)]
pub struct VerifyProgress {
    pub group: VerifyGroup,
    pub checked: u64,
    pub total: u64,
}

impl VerifyProgress {
    pub fn mock_running() -> Self {
        Self {
            group: VerifyGroup::AssetObjects,
            checked: 320,
            total: 1000,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq)]
pub struct VerifyFailure {
    pub path: String,
    #[serde(deserialize_with = "Deserialize::deserialize")]
    pub expected: Option<String>,
    #[serde(deserialize_with = "Deserialize::deserialize")]
    pub actual: Option<String>,
    pub reason: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VerifyGroupResult {
    pub group: VerifyGroup,
    pub checked: u64,
    pub total: u64,
    pub failed: Vec<VerifyFailure>,
    pub failure_count: u64,
}

impl VerifyGroupResult {
    pub fn passed(&self) -> bool {
        self.checked == self.total && self.failure_count == 0 && self.failed.is_empty()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VerifyReport {
    pub groups: Vec<VerifyGroupResult>,
}

impl VerifyReport {
    pub fn passed(&self) -> bool {
        self.groups.len() == VerifyGroup::ALL.len()
            && VerifyGroup::ALL.iter().all(|group| {
                self.groups
                    .iter()
                    .any(|result| result.group == *group && result.passed())
            })
    }

    pub fn mock_ok() -> Self {
        Self {
            groups: VerifyGroup::ALL
                .into_iter()
                .zip([1, 85, 1, 1000])
                .map(|(group, count)| VerifyGroupResult {
                    group,
                    checked: count,
                    total: count,
                    failed: Vec::new(),
                    failure_count: 0,
                })
                .collect(),
        }
    }

    pub fn mock_failed_index() -> Self {
        let mut report = Self::mock_ok();
        let index = &mut report.groups[VerifyGroup::AssetIndex as usize];
        index.failed.push(VerifyFailure {
            path: "assets/indexes/34.json".to_owned(),
            expected: Some("1".repeat(40)),
            actual: Some("2".repeat(40)),
            reason: "sha1-mismatch".to_owned(),
        });
        index.failure_count = 1;
        let objects = &mut report.groups[VerifyGroup::AssetObjects as usize];
        objects.checked = 0;
        objects.total = 0;
        objects.failed.push(VerifyFailure {
            path: "assets/indexes/34.json".to_owned(),
            expected: None,
            actual: None,
            reason: "prerequisite-failed".to_owned(),
        });
        objects.failure_count = 1;
        report
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DoctorReport {
    pub java: Option<DoctorItem>,
    pub memory: Option<DoctorItem>,
    pub game_dir: Option<DoctorItem>,
    pub profiles_dir: Option<String>,
    pub work_dir: Option<DoctorItem>,
    pub versions: Vec<VersionStatus>,
    #[serde(default)]
    pub tools: Vec<ToolStatus>,
    pub warnings: Vec<String>,
    pub raw_output: String,
    pub stderr: String,
    pub exit_code: i32,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolStatus {
    pub schema: String,
    pub id: String,
    pub display_name: String,
    pub required: bool,
    pub required_reason: ToolRequirement,
    pub required_for: Vec<String>,
    #[serde(default)]
    pub resolved_command: Option<String>,
    pub state: String,
    pub bytes: u64,
    pub source_host: String,
    #[serde(default)]
    pub source_url: String,
    pub destination_path: String,
    pub expected_sha256: String,
    #[serde(default)]
    pub installable: bool,
    #[serde(default)]
    pub hint: String,
    #[serde(flatten)]
    pub inventory: ToolInventory,
}

#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ToolInventory {
    pub checked_files: Option<u64>,
    pub total_files: Option<u64>,
    pub first_failed_path: Option<String>,
    pub total_bytes: Option<u64>,
    pub expected_files: Vec<String>,
    pub differing_files: Vec<String>,
    pub sdk_platforms: Vec<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum ToolRequirement {
    RequiredDefault,
    RequiredStrictKernel,
    Optional,
}

impl ToolStatus {
    pub fn ready(&self) -> bool {
        if self.id == "shader-toolchain" {
            self.state == "verified"
        } else {
            self.state == "present"
        }
    }

    pub fn can_install(&self) -> bool {
        self.installable && !matches!(self.id.as_str(), "shader-toolchain" | "emscripten-sdk")
    }

    pub fn blocks(&self, versions: &[String]) -> bool {
        self.id != "emscripten-sdk"
            && self.required
            && !self.ready()
            && self
                .required_for
                .iter()
                .any(|version| versions.contains(version))
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ToolPaths {
    pub toolchain_dir: Option<PathBuf>,
    pub shader_toolchain: Option<PathBuf>,
}

impl ToolPaths {
    fn append_args(&self, args: &mut Vec<OsString>, include_shader: bool) {
        if let Some(path) = &self.toolchain_dir {
            args.extend([
                OsString::from("--toolchain-dir"),
                path.as_os_str().to_owned(),
            ]);
        }
        if include_shader && let Some(path) = &self.shader_toolchain {
            args.extend([
                OsString::from("--shader-toolchain"),
                path.as_os_str().to_owned(),
            ]);
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct BuildProgress {
    pub schema: String,
    pub phase: String,
    pub step: String,
    pub pct: u8,
    pub message: String,
    pub error: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
}

#[derive(Clone, Debug)]
pub struct BuildLogLine {
    pub version: String,
    pub line: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum HashState {
    Match,
    Mismatch,
    Unknown,
}

#[derive(Clone, Debug)]
pub struct HashComparison {
    pub state: HashState,
    pub mismatches: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct VersionBuildResult {
    pub version: String,
    pub output_dir: PathBuf,
    pub exit_code: i32,
    pub error: Option<String>,
    pub hashes: HashComparison,
}

#[derive(Clone, Debug)]
pub struct CommandOutput {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    pub pid: u32,
    pub versions: Vec<VersionBuildResult>,
}

pub struct BuildRequest<'a> {
    pub version: &'a str,
    pub output: &'a Path,
    pub mappings: Option<&'a Path>,
    pub tool_paths: &'a ToolPaths,
    pub minecraft_dir: Option<&'a Path>,
    pub settings: &'a BuildSettings,
}

#[derive(Clone, Debug, Default)]
pub struct BuildSettings {
    pub quality: Option<String>,
    pub render_distance: Option<u32>,
    pub backend: Option<String>,
    pub resource_packs: Vec<PathBuf>,
    pub strict_kernel: bool,
}

impl BuildSettings {
    pub fn quality_from_ui_index(index: usize) -> &'static str {
        ["low", "mid", "high", "ultra"][index.min(3)]
    }

    pub fn backend_from_ui_index(index: usize) -> &'static str {
        ["webgpu", "webgl2", "auto"][index.min(2)]
    }

    pub fn render_distance_from_ui(value: u32) -> u32 {
        value.clamp(2, 32)
    }

    pub fn from_ui_indices(
        quality: usize,
        render_distance: u32,
        backend: usize,
        resource_packs: Vec<PathBuf>,
    ) -> Self {
        Self {
            quality: Some(Self::quality_from_ui_index(quality).to_owned()),
            render_distance: Some(Self::render_distance_from_ui(render_distance)),
            backend: Some(Self::backend_from_ui_index(backend).to_owned()),
            resource_packs,
            strict_kernel: false,
        }
    }
}

#[derive(Clone, Debug)]
pub struct PatcherError(pub String);

impl std::fmt::Display for PatcherError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for PatcherError {}

pub trait PatcherBackend {
    fn doctor(&self) -> Result<DoctorReport, PatcherError>;
}

#[derive(Clone, Default)]
pub struct PatcherClient;

impl PatcherBackend for PatcherClient {
    fn doctor(&self) -> Result<DoctorReport, PatcherError> {
        self.doctor_with_control(ProcessControl::default(), &ToolPaths::default(), false)
    }
}

impl PatcherClient {
    pub fn start_verify(
        &self,
        version: String,
        minecraft_dir: Option<PathBuf>,
        mappings: Option<PathBuf>,
    ) -> VerifyTask {
        let control = ProcessControl::default();
        let worker_control = control.clone();
        let (progress_tx, progress) = mpsc::sync_channel(64);
        let (completions_tx, completions) = mpsc::channel();
        let (result_tx, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("gaius-verify-files".to_owned())
            .spawn(move || {
                let mut args = vec![OsString::from("doctor"), OsString::from(version)];
                if let Some(dir) = minecraft_dir {
                    args.extend([OsString::from("--minecraft-dir"), dir.into_os_string()]);
                }
                if let Some(path) = mappings {
                    args.extend([OsString::from("--mappings"), path.into_os_string()]);
                }
                args.extend([OsString::from("--verify-files"), OsString::from("--json")]);
                let output = run_patcher_with_stdout(
                    &args,
                    worker_control.clone(),
                    move |pipe| {
                        let mut stream = VerifyStream::default();
                        stream_bounded_lines(pipe, VERIFY_LINE_LIMIT, |bytes, truncated| {
                            match stream.read_line(bytes, truncated) {
                                Some(VerifyUpdate::Progress(event)) => {
                                    let _ = progress_tx.try_send(event);
                                }
                                Some(VerifyUpdate::Completed(group)) => {
                                    let _ = completions_tx.send(group.clone());
                                }
                                None => {}
                            }
                        })?;
                        Ok((String::new(), stream))
                    },
                    |_| {},
                    false,
                );
                let result = if worker_control.is_cancelled() {
                    Err(PatcherError("Official file check was cancelled.".to_owned()))
                } else {
                    output
                        .map_err(|_| {
                            PatcherError(
                                "Could not run the official file check. Check the Java and patcher setup, then try again."
                                    .to_owned(),
                            )
                        })
                        .and_then(|(_, stream, status)| stream.finish(status.code()))
                };
                let _ = result_tx.send(result);
            })
            .expect("file verification worker thread must start");
        VerifyTask {
            receiver,
            progress,
            completions,
            control,
        }
    }

    pub fn start_doctor(&self, tool_paths: ToolPaths, strict_kernel: bool) -> DoctorTask {
        let control = ProcessControl::default();
        let worker_control = control.clone();
        let (sender, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("gaius-doctor".to_owned())
            .spawn(move || {
                let report = Self.doctor_with_control(worker_control, &tool_paths, strict_kernel);
                let _ = sender.send(report);
            })
            .expect("doctor worker thread must start");
        DoctorTask { receiver, control }
    }

    pub fn start_tools_install(
        &self,
        only: Vec<String>,
        tool_paths: ToolPaths,
        strict_kernel: bool,
    ) -> ToolInstallTask {
        let control = ProcessControl::default();
        let worker_control = control.clone();
        let (progress_tx, progress) = mpsc::channel();
        let (result_tx, result) = mpsc::channel();
        thread::Builder::new()
            .name("gaius-tools-install".to_owned())
            .spawn(move || {
                let mut args = vec![
                    OsString::from("tools"),
                    OsString::from("install"),
                    OsString::from("--json-progress"),
                ];
                if !only.is_empty() {
                    args.extend([OsString::from("--only"), OsString::from(only.join(","))]);
                }
                tool_paths.append_args(&mut args, true);
                if strict_kernel {
                    args.push(OsString::from("--strict-kernel"));
                }
                let output = run_patcher(
                    &args,
                    worker_control.clone(),
                    move |event| {
                        let _ = progress_tx.send(event);
                    },
                    |_| {},
                    false,
                );
                let result = output.and_then(|output| {
                    if worker_control.is_cancelled() {
                        return Err(PatcherError("tool installation cancelled".to_owned()));
                    }
                    if output.exit_code != 0 {
                        return Err(PatcherError(last_error(&output.stderr, &output.stdout)));
                    }
                    Self.tools_status(worker_control, &tool_paths, strict_kernel)
                });
                let _ = result_tx.send(result);
            })
            .expect("tools install worker thread must start");
        ToolInstallTask {
            receiver: result,
            progress,
            control,
        }
    }

    pub fn run_build(
        &self,
        request: BuildRequest<'_>,
        control: ProcessControl,
        on_progress: impl FnMut(BuildProgress) + Send + 'static,
        on_log: impl FnMut(String) + Send + 'static,
    ) -> Result<CommandOutput, PatcherError> {
        let args = build_args(
            request.version,
            request.output,
            request.mappings,
            request.tool_paths,
            request.minecraft_dir,
            request.settings,
        );
        run_patcher(&args, control, on_progress, on_log, true)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn start_build(
        &self,
        versions: Vec<String>,
        output: PathBuf,
        mappings: Option<PathBuf>,
        tool_paths: ToolPaths,
        minecraft_dir: Option<PathBuf>,
        settings: BuildSettings,
        control: ProcessControl,
    ) -> BuildTask {
        let worker_control = control.clone();
        let (progress_tx, progress) = mpsc::channel();
        let (log_tx, logs) = mpsc::sync_channel(128);
        let (result_tx, result) = mpsc::channel();
        thread::Builder::new()
            .name("gaius-build".to_owned())
            .spawn(move || {
                let scenario = env::var("GAIUS_BUILDER_MOCK").ok();
                if scenario.as_deref().is_none_or(str::is_empty) {
                    let checked = Self
                        .tools_status(worker_control.clone(), &tool_paths, settings.strict_kernel)
                        .and_then(|tools| {
                            let missing: Vec<_> = tools
                                .iter()
                                .filter(|tool| tool.blocks(&versions))
                                .map(|tool| tool.display_name.as_str())
                                .collect();
                            if missing.is_empty() {
                                Ok(())
                            } else {
                                Err(PatcherError(format!(
                                    "required build tools are not ready: {}",
                                    missing.join(", ")
                                )))
                            }
                        });
                    if let Err(error) = checked {
                        let result = if worker_control.is_cancelled() {
                            Ok(CommandOutput {
                                stdout: String::new(),
                                stderr: "Build cancelled during tool verification.".to_owned(),
                                exit_code: 130,
                                pid: 0,
                                versions: Vec::new(),
                            })
                        } else {
                            Err(error)
                        };
                        let _ = result_tx.send(result);
                        return;
                    }
                }
                let result = {
                    let mut combined = CommandOutput {
                        stdout: String::new(),
                        stderr: String::new(),
                        exit_code: 0,
                        pid: 0,
                        versions: Vec::new(),
                    };
                    for version in versions {
                        if worker_control.is_cancelled() {
                            break;
                        }
                        let version_output = output.join(&version);
                        let progress_version = version.clone();
                        let log_version = version.clone();
                        let tx = progress_tx.clone();
                        let log_tx = log_tx.clone();
                        let progress_log_tx = log_tx.clone();
                        let version_mappings = (version == "1.21.11")
                            .then_some(mappings.as_deref())
                            .flatten();
                        let result =
                            if let Some(scenario) = scenario.as_deref().filter(|v| !v.is_empty()) {
                                run_mock_build(scenario, &worker_control, &tx, &version)
                            } else {
                                Self.run_build(
                                    BuildRequest {
                                        version: &version,
                                        output: &version_output,
                                        mappings: version_mappings,
                                        tool_paths: &tool_paths,
                                        minecraft_dir: minecraft_dir.as_deref(),
                                        settings: &settings,
                                    },
                                    worker_control.clone(),
                                    move |mut event| {
                                        event.version = Some(progress_version.clone());
                                        let _ = tx.send(event);
                                    },
                                    move |line| {
                                        let _ = progress_log_tx.send(BuildLogLine {
                                            version: log_version.clone(),
                                            line,
                                        });
                                    },
                                )
                            };
                        match result {
                            Ok(output) => {
                                combined.stdout = append_limited(combined.stdout, &output.stdout);
                                combined.stderr = append_limited(combined.stderr, &output.stderr);
                                combined.pid = output.pid;
                                let hashes = if output.exit_code == 0 {
                                    let hashes = compare_expected_hashes(&version_output, &version);
                                    let _ = progress_tx.send(BuildProgress {
                                        schema: "gaius-patcher-progress-v1".to_owned(),
                                        phase: "compare".to_owned(),
                                        step: match hashes.state {
                                            HashState::Match => "match",
                                            HashState::Mismatch => "mismatch",
                                            HashState::Unknown => "missing",
                                        }
                                        .to_owned(),
                                        pct: 100,
                                        message: match hashes.state {
                                            HashState::Match => {
                                                "Build files match expected hashes".to_owned()
                                            }
                                            HashState::Mismatch => {
                                                "Build files do not match expected hashes"
                                                    .to_owned()
                                            }
                                            HashState::Unknown => {
                                                "expected-hashes.json is missing or unreadable"
                                                    .to_owned()
                                            }
                                        },
                                        error: None,
                                        version: Some(version.clone()),
                                    });
                                    hashes
                                } else {
                                    HashComparison {
                                        state: HashState::Unknown,
                                        mismatches: Vec::new(),
                                    }
                                };
                                combined.exit_code |= output.exit_code;
                                combined.versions.push(VersionBuildResult {
                                    version,
                                    output_dir: version_output,
                                    exit_code: output.exit_code,
                                    error: (output.exit_code != 0)
                                        .then(|| last_build_error(&output.stderr, &output.stdout)),
                                    hashes,
                                });
                            }
                            Err(error) => {
                                combined.exit_code = 1;
                                combined.stderr =
                                    append_limited(combined.stderr, &error.to_string());
                                let _ = log_tx.send(BuildLogLine {
                                    version: version.clone(),
                                    line: error.to_string(),
                                });
                                combined.versions.push(VersionBuildResult {
                                    version,
                                    output_dir: version_output,
                                    exit_code: 1,
                                    error: Some(error.to_string()),
                                    hashes: HashComparison {
                                        state: HashState::Unknown,
                                        mismatches: Vec::new(),
                                    },
                                });
                            }
                        }
                        if worker_control.is_cancelled() {
                            break;
                        }
                    }
                    Ok(combined)
                };
                let _ = result_tx.send(result);
            })
            .expect("build worker thread must start");
        BuildTask {
            result,
            progress,
            logs,
            control,
        }
    }

    fn doctor_with_control(
        &self,
        control: ProcessControl,
        tool_paths: &ToolPaths,
        strict_kernel: bool,
    ) -> Result<DoctorReport, PatcherError> {
        let output = run_patcher(
            &[OsString::from("doctor")],
            control.clone(),
            |_| {},
            |_| {},
            false,
        )?;
        let mut report = parse_doctor_output(&output.stdout, &output.stderr, output.exit_code);
        report.tools = self.tools_status(control, tool_paths, strict_kernel)?;
        Ok(report)
    }

    fn tools_status(
        &self,
        control: ProcessControl,
        tool_paths: &ToolPaths,
        strict_kernel: bool,
    ) -> Result<Vec<ToolStatus>, PatcherError> {
        if control.is_cancelled() {
            return Err(PatcherError("tool check cancelled".to_owned()));
        }
        let mut args = ["tools", "status", "--json", "--offline"]
            .map(OsString::from)
            .to_vec();
        tool_paths.append_args(&mut args, true);
        if strict_kernel {
            args.push(OsString::from("--strict-kernel"));
        }
        let output = run_patcher(&args, control, |_| {}, |_| {}, false)?;
        if output.exit_code != 0 {
            return Err(PatcherError(last_build_error(
                &output.stderr,
                &output.stdout,
            )));
        }
        parse_tools_status(&output.stdout)
    }
}

fn build_args(
    version: &str,
    output: &Path,
    mappings: Option<&Path>,
    tool_paths: &ToolPaths,
    minecraft_dir: Option<&Path>,
    settings: &BuildSettings,
) -> Vec<OsString> {
    let mut args = vec![
        OsString::from("build"),
        OsString::from(version),
        OsString::from("--out"),
        output.as_os_str().to_owned(),
        OsString::from("--json-progress"),
        OsString::from("--accept-terms"),
        OsString::from("--offline"),
    ];
    if settings.strict_kernel {
        args.push(OsString::from("--strict-kernel"));
    }
    if version == "1.21.11"
        && let Some(path) = mappings
    {
        args.extend([OsString::from("--mappings"), path.as_os_str().to_owned()]);
    }
    tool_paths.append_args(&mut args, version == "26.3");
    if let Some(path) = minecraft_dir {
        args.extend([
            OsString::from("--minecraft-dir"),
            path.as_os_str().to_owned(),
        ]);
    }
    if let Some(quality) = settings.quality.as_deref() {
        args.extend([OsString::from("--quality"), OsString::from(quality)]);
    }
    if let Some(distance) = settings.render_distance {
        args.extend([
            OsString::from("--render-distance"),
            OsString::from(distance.to_string()),
        ]);
    }
    if let Some(backend) = settings.backend.as_deref() {
        args.extend([OsString::from("--backend"), OsString::from(backend)]);
    }
    for pack in &settings.resource_packs {
        args.extend([
            OsString::from("--resource-pack"),
            pack.as_os_str().to_owned(),
        ]);
    }
    args
}

pub struct DoctorTask {
    pub receiver: mpsc::Receiver<Result<DoctorReport, PatcherError>>,
    pub control: ProcessControl,
}

pub struct VerifyTask {
    /// The only authoritative result; delivered after the process and both pipes close.
    pub receiver: mpsc::Receiver<Result<VerifyReport, PatcherError>>,
    pub progress: mpsc::Receiver<VerifyProgress>,
    /// Provisional group results for display while other groups are being checked.
    pub completions: mpsc::Receiver<VerifyGroupResult>,
    pub control: ProcessControl,
}

pub struct ToolInstallTask {
    pub receiver: mpsc::Receiver<Result<Vec<ToolStatus>, PatcherError>>,
    pub progress: mpsc::Receiver<BuildProgress>,
    pub control: ProcessControl,
}

pub struct BuildTask {
    pub result: mpsc::Receiver<Result<CommandOutput, PatcherError>>,
    pub progress: mpsc::Receiver<BuildProgress>,
    pub logs: mpsc::Receiver<BuildLogLine>,
    pub control: ProcessControl,
}

#[derive(Clone, Default)]
pub struct ProcessControl {
    shared: Arc<ProcessShared>,
}

#[derive(Default)]
struct ProcessShared {
    pid: AtomicU32,
    cancel_requested: AtomicBool,
    killer: Mutex<Option<mpsc::Sender<bool>>>,
}

impl ProcessControl {
    pub fn pid(&self) -> Option<u32> {
        match self.shared.pid.load(Ordering::Acquire) {
            0 => None,
            pid => Some(pid),
        }
    }

    pub fn cancel(&self) {
        self.shared.cancel_requested.store(true, Ordering::Release);
        if let Ok(killer) = self.shared.killer.lock()
            && let Some(killer) = killer.as_ref()
        {
            let _ = killer.send(true);
        }
    }

    pub fn is_cancelled(&self) -> bool {
        self.shared.cancel_requested.load(Ordering::Acquire)
    }

    fn attach(&self, pid: u32, killer: mpsc::Sender<bool>) {
        self.shared.pid.store(pid, Ordering::Release);
        if let Ok(mut slot) = self.shared.killer.lock() {
            *slot = Some(killer.clone());
        }
        if self.shared.cancel_requested.load(Ordering::Acquire) {
            let _ = killer.send(true);
        }
    }

    fn finish(&self) {
        self.shared.pid.store(0, Ordering::Release);
        if let Ok(mut slot) = self.shared.killer.lock()
            && let Some(killer) = slot.take()
        {
            let _ = killer.send(false);
        }
    }
}

#[derive(Default)]
pub struct MockBackend;

impl PatcherBackend for MockBackend {
    fn doctor(&self) -> Result<DoctorReport, PatcherError> {
        let mut report =
            parse_doctor_output(include_str!("../tests/fixtures/doctor-output.txt"), "", 0);
        report.tools = mock_tool_statuses(false);
        Ok(report)
    }
}

pub fn mock_tool_statuses(strict_kernel: bool) -> Vec<ToolStatus> {
    const MAVEN_FILES: u64 = 363;
    const MAVEN_BYTES: u64 = 86_143_464;
    [
        ("jdk-25", "JDK 25", "github.com", 141_341_188, true, ""),
        (
            "maven",
            "Apache Maven 3.9.11",
            "archive.apache.org",
            9_160_848,
            true,
            "",
        ),
        (
            "vineflower",
            "Vineflower 1.11.2",
            "repo1.maven.org",
            1_856_529,
            true,
            "",
        ),
        (
            "llvm",
            "LLVM 21.1.8",
            "github.com, archive.ubuntu.com",
            2_030_629_054,
            true,
            "",
        ),
        (
            "binaryen",
            "Binaryen 133",
            "github.com",
            111_200_018,
            true,
            "",
        ),
        (
            "remapper",
            "AutoRenamingTool 2.0.18",
            "maven.neoforged.net",
            756_920,
            true,
            "1.21.11",
        ),
        (
            "teavm-classlib",
            "TeaVM classlib 0.15.0",
            "repo1.maven.org",
            17_718_996,
            true,
            "",
        ),
        (
            "teavm-core",
            "TeaVM core 0.15.0",
            "repo1.maven.org",
            2_956_367,
            true,
            "",
        ),
        (
            "teavm-interop",
            "TeaVM interop 0.15.0",
            "repo1.maven.org",
            12_599,
            true,
            "",
        ),
        (
            "teavm-jso",
            "TeaVM jso 0.15.0",
            "repo1.maven.org",
            8_437,
            true,
            "",
        ),
        (
            "teavm-jso-apis",
            "TeaVM jso-apis 0.15.0",
            "repo1.maven.org",
            177_814,
            true,
            "",
        ),
        (
            "teavm-platform",
            "TeaVM platform 0.15.0",
            "repo1.maven.org",
            101_636,
            true,
            "",
        ),
        (
            "teavm-maven-plugin",
            "TeaVM Maven plugin 0.15.0",
            "repo1.maven.org",
            21_053,
            true,
            "",
        ),
        (
            "maven-repository",
            "Offline Maven repository",
            "repo.maven.apache.org",
            MAVEN_BYTES,
            true,
            "26.3",
        ),
        (
            "maven-repository",
            "Offline Maven repository",
            "repo.maven.apache.org",
            MAVEN_BYTES,
            true,
            "26.2",
        ),
        (
            "maven-repository",
            "Offline Maven repository",
            "repo.maven.apache.org",
            MAVEN_BYTES,
            true,
            "1.21.11",
        ),
        (
            "shader-toolchain",
            "Prebuilt shader toolchain",
            "",
            0,
            false,
            "26.3",
        ),
        ("emscripten-sdk", "Emscripten SDK", "", 0, false, ""),
        ("bash", "Bash", "gnu.org", 0, false, ""),
        ("node", "Node.js", "nodejs.org", 0, false, ""),
        ("python", "Python 3", "python.org", 0, false, ""),
        ("jq", "jq", "jqlang.org", 0, false, ""),
        ("shell-tools", "Shell utilities", "gnu.org", 0, false, ""),
        ("rust-cargo", "Rust/Cargo 1.99.1", "rustup.rs", 0, false, ""),
        (
            "wasm32-target",
            "wasm32-unknown-unknown",
            "rustup.rs",
            0,
            false,
            "",
        ),
        (
            "cargo-cache",
            "Offline Cargo dependencies",
            "doc.rust-lang.org",
            0,
            false,
            "",
        ),
    ]
    .into_iter()
    .map(
        |(id, display_name, source_host, bytes, installable, only_version)| {
            let kernel = matches!(id, "rust-cargo" | "wasm32-target" | "cargo-cache");
            let required_reason = match (id, kernel, strict_kernel) {
                ("emscripten-sdk", _, _) | (_, true, false) => ToolRequirement::Optional,
                (_, false, _) => ToolRequirement::RequiredDefault,
                (_, true, true) => ToolRequirement::RequiredStrictKernel,
            };
            let required = required_reason != ToolRequirement::Optional;
            let (state, path, inventory) = match id {
                "shader-toolchain" => (
                    "verified",
                    "port/work/tools/shader-toolchain",
                    ToolInventory {
                        expected_files: [
                            "gaius-shader-toolchain.json",
                            "gaius-shader-toolchain.js",
                            "gaius-shaderc.js",
                            "gaius-shaderc.wasm",
                            "gaius-spvc.js",
                            "gaius-spvc.wasm",
                        ]
                        .map(str::to_owned)
                        .to_vec(),
                        ..ToolInventory::default()
                    },
                ),
                "maven-repository" => (
                    "present",
                    "port/work/maven-repository",
                    ToolInventory {
                        checked_files: Some(MAVEN_FILES),
                        total_files: Some(MAVEN_FILES),
                        total_bytes: Some(MAVEN_BYTES),
                        ..ToolInventory::default()
                    },
                ),
                "emscripten-sdk" => (
                    "informational",
                    "",
                    ToolInventory {
                        sdk_platforms: ["linux-x64".to_owned(), "windows-x64".to_owned()].to_vec(),
                        ..ToolInventory::default()
                    },
                ),
                _ => ("present", "", ToolInventory::default()),
            };
            ToolStatus {
                schema: "gaius-patcher-tools-v1".to_owned(),
                id: id.to_owned(),
                display_name: display_name.to_owned(),
                required,
                required_reason,
                resolved_command: (id == "python").then(|| "python3".to_owned()),
                required_for: if !required {
                    Vec::new()
                } else if only_version.is_empty() {
                    vec!["26.3".to_owned(), "26.2".to_owned(), "1.21.11".to_owned()]
                } else {
                    vec![only_version.to_owned()]
                },
                state: state.to_owned(),
                bytes,
                source_host: source_host.to_owned(),
                source_url: String::new(),
                destination_path: path.to_owned(),
                expected_sha256: String::new(),
                installable,
                hint: String::new(),
                inventory,
            }
        },
    )
    .collect()
}

pub fn parse_progress_line(line: &str) -> Option<BuildProgress> {
    let value: Value = serde_json::from_str(line).ok()?;
    let object = value.as_object()?;
    let schema = object.get("schema")?.as_str()?;
    if schema == "gaius-patcher-tools-progress-v1" {
        let pct = object
            .get("pct")
            .and_then(Value::as_u64)
            .unwrap_or(0)
            .min(100) as u8;
        let bytes = object.get("bytes").and_then(Value::as_u64).unwrap_or(0);
        let total = object
            .get("totalBytes")
            .and_then(Value::as_u64)
            .unwrap_or(0);
        return Some(BuildProgress {
            schema: schema.to_owned(),
            phase: "tools".to_owned(),
            step: object
                .get("step")
                .and_then(Value::as_str)
                .unwrap_or("download")
                .to_owned(),
            pct,
            message: format!(
                "{}: {bytes}/{total}",
                object.get("id").and_then(Value::as_str).unwrap_or("")
            ),
            error: object
                .get("error")
                .and_then(Value::as_str)
                .map(str::to_owned),
            version: None,
        });
    }
    if schema != "gaius-patcher-progress-v1" {
        return None;
    }
    let pct = object
        .get("pct")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(100) as u8;
    Some(BuildProgress {
        schema: "gaius-patcher-progress-v1".to_owned(),
        phase: object
            .get("phase")
            .and_then(Value::as_str)
            .unwrap_or("unknown")
            .to_owned(),
        step: object
            .get("step")
            .and_then(Value::as_str)
            .unwrap_or("unknown")
            .to_owned(),
        pct,
        message: object
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned(),
        error: object
            .get("error")
            .and_then(Value::as_str)
            .map(str::to_owned),
        version: object
            .get("version")
            .and_then(Value::as_str)
            .map(str::to_owned),
    })
}

pub fn parse_doctor_output(stdout: &str, stderr: &str, exit_code: i32) -> DoctorReport {
    let mut report = DoctorReport {
        java: None,
        memory: None,
        game_dir: None,
        profiles_dir: None,
        work_dir: None,
        versions: Vec::new(),
        tools: Vec::new(),
        warnings: Vec::new(),
        raw_output: stdout.to_owned(),
        stderr: stderr.to_owned(),
        exit_code,
    };

    for line in stdout.lines() {
        let trimmed = line.trim();
        if let Some(value) = field_value(trimmed, "Java:") {
            report.java = Some(DoctorItem {
                value: value.to_owned(),
                state: CheckState::Ready,
                exists: None,
            });
        } else if let Some(value) = field_value(trimmed, "Memory:") {
            let low = number_before_unit(value, "GB").is_some_and(|gib| gib < 16.0);
            report.memory = Some(DoctorItem {
                value: value.to_owned(),
                state: if low || value == "unknown" {
                    CheckState::Warning
                } else {
                    CheckState::Ready
                },
                exists: None,
            });
        } else if let Some(value) = field_value(trimmed, "Game dir:") {
            let exists = !value.ends_with(" (not found)");
            report.game_dir = Some(DoctorItem {
                value: value
                    .strip_suffix(" (not found)")
                    .unwrap_or(value)
                    .to_owned(),
                state: if exists {
                    CheckState::Ready
                } else {
                    CheckState::Missing
                },
                exists: Some(exists),
            });
        } else if let Some(value) = field_value(trimmed, "Profiles:") {
            report.profiles_dir = Some(value.to_owned());
        } else if let Some(value) = field_value(trimmed, "Work dir:") {
            let unknown = value.contains("free space unknown");
            let low = value.contains(" free)")
                && number_before_unit(value, "GB").is_some_and(|gib| gib < 20.0);
            report.work_dir = Some(DoctorItem {
                value: value.to_owned(),
                state: if unknown || low {
                    CheckState::Warning
                } else {
                    CheckState::Ready
                },
                exists: None,
            });
        } else if trimmed.starts_with("warning:") {
            report.warnings.push(trimmed.to_owned());
        } else if let Some(version) = parse_version_line(trimmed) {
            report.versions.push(version);
        }
    }
    report
}

fn field_value<'a>(line: &'a str, name: &str) -> Option<&'a str> {
    line.strip_prefix(name).map(str::trim)
}

fn number_before_unit(value: &str, unit: &str) -> Option<f64> {
    let index = value.find(unit)?;
    value[..index]
        .split_whitespace()
        .last()?
        .parse::<f64>()
        .ok()
}

fn parse_version_line(line: &str) -> Option<VersionStatus> {
    let line = line.trim();
    let (id, status) = line.split_once(char::is_whitespace)?;
    if !id
        .chars()
        .all(|character| character.is_ascii_digit() || character == '.')
        || !id.contains('.')
    {
        return None;
    }
    let status = status.trim();
    let required_java = status
        .find("[needs Java ")
        .and_then(|start| status.get(start + 12..))
        .and_then(|rest| rest.split_once('+').map(|(digits, _)| digits))
        .and_then(|digits| digits.parse().ok());
    let core_status = status.split(" [needs Java ").next().unwrap_or(status);
    let installed = !core_status.starts_with("not installed:");
    let verified = core_status.contains("client jar verified");
    let reason = if verified {
        None
    } else if let Some(reason) = core_status.strip_prefix("not installed:") {
        Some(reason.trim().to_owned())
    } else {
        Some(core_status.to_owned())
    };
    let state = if required_java.is_some() || (installed && !verified) {
        CheckState::Warning
    } else if installed {
        CheckState::Ready
    } else {
        CheckState::Missing
    };
    Some(VersionStatus {
        id: id.to_owned(),
        installed,
        verified,
        reason,
        required_java,
        state,
    })
}

#[derive(Clone)]
enum Launcher {
    Source(PathBuf),
    Jar(PathBuf),
}

fn resolve_launcher() -> Result<(OsString, Launcher), PatcherError> {
    let java = resolve_java()?;
    if let Some(jar) = env::var_os("GAIUS_PATCHER_JAR") {
        let jar = PathBuf::from(jar);
        if !jar.is_file() {
            return Err(PatcherError(format!(
                "GAIUS_PATCHER_JAR does not exist: {}",
                jar.display()
            )));
        }
        return Ok((java, Launcher::Jar(jar)));
    }
    if let Some(source) = env::var_os("GAIUS_PATCHER_SOURCE") {
        let source = PathBuf::from(source);
        if !source.is_file() {
            return Err(PatcherError(format!(
                "GAIUS_PATCHER_SOURCE does not exist: {}",
                source.display()
            )));
        }
        return Ok((java, Launcher::Source(source)));
    }

    let mut roots = Vec::new();
    if let Ok(executable) = env::current_exe() {
        roots.extend(executable.ancestors().map(Path::to_path_buf));
    }
    if let Ok(directory) = env::current_dir() {
        roots.extend(directory.ancestors().map(Path::to_path_buf));
    }
    roots.dedup();
    let source = roots
        .into_iter()
        .map(|root| root.join("patcher/GaiusPatcher.java"))
        .find(|candidate| candidate.is_file())
        .ok_or_else(|| {
            PatcherError(
                "could not locate patcher/GaiusPatcher.java; set GAIUS_PATCHER_JAR to a patcher jar"
                    .to_owned(),
            )
        })?;
    Ok((java, Launcher::Source(source)))
}

fn resolve_java() -> Result<OsString, PatcherError> {
    if let Some(java) = env::var_os("GAIUS_PATCHER_JAVA")
        && !java.is_empty()
    {
        return Ok(java);
    }
    if let Some(java) = env::var_os("JAVA")
        && !java.is_empty()
    {
        return Ok(java);
    }
    if let Some(home) = env::var_os("JAVA_HOME") {
        let mut path = PathBuf::from(home).join("bin").join("java");
        if cfg!(windows) {
            path.set_extension("exe");
        }
        if path.is_file() {
            return Ok(path.into_os_string());
        }
    }
    Ok(OsString::from("java"))
}

fn run_patcher(
    args: &[OsString],
    control: ProcessControl,
    mut on_progress: impl FnMut(BuildProgress) + Send + 'static,
    on_log: impl FnMut(String) + Send + 'static,
    inhibit_sleep: bool,
) -> Result<CommandOutput, PatcherError> {
    run_patcher_with_stdout(
        args,
        control,
        move |pipe| read_stdout(pipe, &mut on_progress).map(|stdout| (stdout, ())),
        on_log,
        inhibit_sleep,
    )
    .map(|(output, (), _)| output)
}

fn run_patcher_with_stdout<T: Send + 'static>(
    args: &[OsString],
    control: ProcessControl,
    read_stdout: impl FnOnce(ChildStdout) -> io::Result<(String, T)> + Send + 'static,
    mut on_log: impl FnMut(String) + Send + 'static,
    inhibit_sleep: bool,
) -> Result<(CommandOutput, T, ExitStatus), PatcherError> {
    #[cfg(windows)]
    let _sleep_guard = inhibit_sleep.then(WindowsSleepGuard::new);
    let (java, launcher) = resolve_launcher()?;
    let inhibit_sleep =
        inhibit_sleep && env::var_os("GAIUS_BUILDER_DISABLE_SLEEP_INHIBIT").is_none();
    let mut command = if inhibit_sleep {
        sleep_inhibitor_command(java)
    } else {
        Command::new(java)
    };
    command.args([
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    ]);
    match launcher {
        Launcher::Source(source) => {
            command.arg(source);
        }
        Launcher::Jar(jar) => {
            command.arg("-jar").arg(jar);
        }
    }
    command
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }

    let mut child = command
        .spawn()
        .map_err(|error| PatcherError(format!("could not start the Java patcher: {error}")))?;
    let pid = child.id();
    let leaf = (args.first().is_some_and(|arg| arg == "tools")
        && args.get(1).is_some_and(|arg| arg == "install"))
        || (args.first().is_some_and(|arg| arg == "doctor")
            && args.iter().any(|arg| arg == "--verify-files"));
    let (killer_tx, killer_rx) = mpsc::channel();
    control.attach(pid, killer_tx);
    let killer = if leaf {
        None
    } else {
        Some(thread::spawn(move || {
            if matches!(killer_rx.recv(), Ok(true)) {
                terminate_process_group(pid, false);
                thread::sleep(Duration::from_secs(2));
                terminate_process_group(pid, true);
            }
        }))
    };

    let stdout_pipe = child
        .stdout
        .take()
        .ok_or_else(|| PatcherError("Java patcher stdout was not piped".to_owned()))?;
    let stderr_pipe = child
        .stderr
        .take()
        .ok_or_else(|| PatcherError("Java patcher stderr was not piped".to_owned()))?;
    let stdout_reader = thread::spawn(move || read_stdout(stdout_pipe));
    let stderr_reader = thread::spawn(move || read_stderr(stderr_pipe, &mut on_log));

    let status = if leaf {
        loop {
            if control.is_cancelled() {
                let _ = child.kill();
                break child.wait();
            }
            match child.try_wait() {
                Ok(Some(status)) => break Ok(status),
                Ok(None) => thread::sleep(Duration::from_millis(50)),
                Err(error) => break Err(error),
            }
        }
    } else {
        child.wait()
    }
    .map_err(|error| PatcherError(format!("could not wait for Java patcher: {error}")));
    control.finish();
    if let Some(killer) = killer {
        let _ = killer.join();
    }
    let (stdout, parsed) = stdout_reader
        .join()
        .map_err(|_| PatcherError("Java patcher stdout reader stopped unexpectedly".to_owned()))?
        .map_err(|error| PatcherError(format!("could not read Java patcher stdout: {error}")))?;
    let stderr = stderr_reader
        .join()
        .map_err(|_| PatcherError("Java patcher stderr reader stopped unexpectedly".to_owned()))?
        .map_err(|error| PatcherError(format!("could not read Java patcher stderr: {error}")))?;
    let status = status?;
    Ok((
        CommandOutput {
            stdout,
            stderr,
            exit_code: status.code().unwrap_or(1),
            pid,
            versions: Vec::new(),
        },
        parsed,
        status,
    ))
}

fn sleep_inhibitor_command(java: OsString) -> Command {
    #[cfg(target_os = "linux")]
    {
        if Command::new("systemd-inhibit")
            .arg("--version")
            .output()
            .is_ok()
        {
            let mut command = Command::new("systemd-inhibit");
            command.args([
                "--what=sleep",
                "--mode=block",
                "--why=Gaius Builder build",
                "--",
            ]);
            command.arg(java);
            return command;
        }
    }
    #[cfg(target_os = "macos")]
    {
        if Command::new("caffeinate").arg("-h").output().is_ok() {
            let mut command = Command::new("caffeinate");
            command.args(["-dimsu"]);
            command.arg(java);
            return command;
        }
    }
    Command::new(java)
}

#[cfg(windows)]
struct WindowsSleepGuard;

#[cfg(windows)]
impl WindowsSleepGuard {
    fn new() -> Self {
        const ES_CONTINUOUS: u32 = 0x8000_0000;
        const ES_SYSTEM_REQUIRED: u32 = 0x0000_0001;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn SetThreadExecutionState(execution_state: u32) -> u32;
        }
        unsafe {
            SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
        }
        Self
    }
}

#[cfg(windows)]
impl Drop for WindowsSleepGuard {
    fn drop(&mut self) {
        const ES_CONTINUOUS: u32 = 0x8000_0000;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn SetThreadExecutionState(execution_state: u32) -> u32;
        }
        unsafe {
            SetThreadExecutionState(ES_CONTINUOUS);
        }
    }
}

fn run_mock_build(
    scenario: &str,
    control: &ProcessControl,
    progress: &mpsc::Sender<BuildProgress>,
    version: &str,
) -> Result<CommandOutput, PatcherError> {
    for (phase, pct, message) in [
        ("fetch", 8, "Validating local files"),
        ("prepare", 32, "Applying source patches"),
        ("compile", 72, "Compiling client"),
        ("publish", 94, "Writing output and hashes"),
    ] {
        let _ = progress.send(BuildProgress {
            schema: "gaius-patcher-progress-v1".to_owned(),
            phase: phase.to_owned(),
            step: phase.to_owned(),
            pct,
            message: message.to_owned(),
            error: None,
            version: Some(version.to_owned()),
        });
        thread::sleep(Duration::from_millis(90));
        if scenario == "cancel" && pct >= 32 {
            control.cancel();
            return Ok(CommandOutput {
                stdout: String::new(),
                stderr: "Build cancelled by user.".to_owned(),
                exit_code: 130,
                pid: 0,
                versions: Vec::new(),
            });
        }
    }
    if matches!(scenario, "fail-hash" | "fail-build" | "unsupported-mod") {
        let message = match scenario {
            "fail-hash" => "client file hash did not match the pinned profile",
            "unsupported-mod" => "selected mod is unsupported for this profile",
            _ => "compiler exited with code 1",
        };
        let _ = progress.send(BuildProgress {
            schema: "gaius-patcher-progress-v1".to_owned(),
            phase: "error".to_owned(),
            step: "failed".to_owned(),
            pct: 100,
            message: message.to_owned(),
            error: Some(message.to_owned()),
            version: Some(version.to_owned()),
        });
        return Ok(CommandOutput {
            stdout: String::new(),
            stderr: message.to_owned(),
            exit_code: 1,
            pid: 0,
            versions: Vec::new(),
        });
    }
    let _ = progress.send(BuildProgress {
        schema: "gaius-patcher-progress-v1".to_owned(),
        phase: "publish".to_owned(),
        step: "complete".to_owned(),
        pct: 100,
        message: "Build completed.".to_owned(),
        error: None,
        version: Some(version.to_owned()),
    });
    Ok(CommandOutput {
        stdout: "mock build complete".to_owned(),
        stderr: String::new(),
        exit_code: 0,
        pid: 0,
        versions: Vec::new(),
    })
}

fn read_stdout(
    pipe: impl io::Read,
    on_progress: &mut impl FnMut(BuildProgress),
) -> io::Result<String> {
    read_bounded_lines(pipe, |line| {
        if let Some(progress) = parse_progress_line(line.trim_end()) {
            on_progress(progress);
        }
    })
}

#[derive(Deserialize)]
struct VerifyLine {
    schema: String,
    #[serde(flatten)]
    event: VerifyEvent,
}

#[derive(Deserialize)]
#[serde(tag = "event", rename_all = "lowercase")]
enum VerifyEvent {
    Progress(VerifyProgress),
    Group(VerifyGroupEvent),
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VerifyGroupEvent {
    group: VerifyGroup,
    checked: u64,
    total: u64,
    failed: Vec<VerifyFailure>,
    failure_count: Option<u64>,
}

#[derive(Default)]
struct VerifyStream {
    groups: [Option<VerifyGroupResult>; VerifyGroup::ALL.len()],
    malformed: bool,
}

enum VerifyUpdate<'a> {
    Progress(VerifyProgress),
    Completed(&'a VerifyGroupResult),
}

impl VerifyStream {
    fn read_line(&mut self, bytes: &[u8], truncated: bool) -> Option<VerifyUpdate<'_>> {
        if self.malformed || (!truncated && bytes.iter().all(u8::is_ascii_whitespace)) {
            return None;
        }
        let Ok(line) = serde_json::from_slice::<VerifyLine>(bytes) else {
            self.malformed = true;
            return None;
        };
        if truncated || line.schema != VERIFY_SCHEMA {
            self.malformed = true;
            return None;
        }
        match line.event {
            VerifyEvent::Progress(event)
                if event.checked <= event.total && self.groups[event.group as usize].is_none() =>
            {
                Some(VerifyUpdate::Progress(event))
            }
            VerifyEvent::Group(event) => {
                let slot = &mut self.groups[event.group as usize];
                let reported = event.failed.len() as u64;
                let failure_count = event.failure_count.unwrap_or(reported);
                if slot.is_some()
                    || event.checked > event.total
                    || (failure_count == 0 && event.checked != event.total)
                    || failure_count < reported
                    || event.failed.iter().any(|failure| {
                        !is_relative_verify_path(&failure.path) || failure.reason.trim().is_empty()
                    })
                {
                    self.malformed = true;
                    None
                } else {
                    let completed = slot.insert(VerifyGroupResult {
                        group: event.group,
                        checked: event.checked,
                        total: event.total,
                        failed: event.failed,
                        failure_count,
                    });
                    Some(VerifyUpdate::Completed(completed))
                }
            }
            _ => {
                self.malformed = true;
                None
            }
        }
    }

    fn finish(self, exit_code: Option<i32>) -> Result<VerifyReport, PatcherError> {
        if self.malformed {
            return Err(PatcherError(
                "The patcher sent invalid official file check results. Check the patcher version, then try again."
                    .to_owned(),
            ));
        }
        let mut groups = Vec::with_capacity(VerifyGroup::ALL.len());
        for (group, result) in VerifyGroup::ALL.into_iter().zip(self.groups) {
            groups.push(result.ok_or_else(|| {
                PatcherError(format!(
                    "The official file check did not finish (missing {}). Please try again.",
                    group.as_str()
                ))
            })?);
        }
        let report = VerifyReport { groups };
        match exit_code {
            Some(0) => Ok(report),
            Some(_) if !report.passed() => Ok(report),
            _ => Err(PatcherError(
                "The patcher stopped unexpectedly during the official file check. Please try again."
                    .to_owned(),
            )),
        }
    }
}

fn is_relative_verify_path(path: &str) -> bool {
    !path.trim().is_empty()
        && !path.contains(':')
        && !path.chars().any(char::is_control)
        && path
            .split(['/', '\\'])
            .all(|part| !matches!(part, "" | "." | ".."))
}

fn read_stderr(pipe: impl Read, on_log: &mut impl FnMut(String)) -> io::Result<String> {
    read_bounded_lines(pipe, |line| on_log(line.to_owned()))
}

fn read_bounded_lines(pipe: impl Read, mut on_line: impl FnMut(&str)) -> io::Result<String> {
    let mut output = String::new();
    stream_bounded_lines(pipe, STREAM_LINE_LIMIT, |bytes, truncated| {
        emit_bounded_line(bytes, truncated, &mut output, &mut on_line);
    })?;
    Ok(output)
}

fn stream_bounded_lines(
    pipe: impl Read,
    line_limit: usize,
    mut on_line: impl FnMut(&[u8], bool),
) -> io::Result<()> {
    let mut reader = BufReader::new(pipe);
    let mut line = Vec::with_capacity(STREAM_LINE_LIMIT.min(line_limit));
    let mut truncated = false;
    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            if !line.is_empty() || truncated {
                on_line(&line, truncated);
            }
            break;
        }
        let newline = available.iter().position(|byte| *byte == b'\n');
        let content_len = newline.unwrap_or(available.len());
        let remaining = line_limit.saturating_sub(line.len());
        let kept = content_len.min(remaining);
        line.extend_from_slice(&available[..kept]);
        truncated |= kept < content_len;
        let consumed = content_len + usize::from(newline.is_some());
        reader.consume(consumed);
        if newline.is_some() {
            on_line(&line, truncated);
            line.clear();
            truncated = false;
        }
    }
    Ok(())
}

fn emit_bounded_line(
    bytes: &[u8],
    truncated: bool,
    output: &mut String,
    on_line: &mut impl FnMut(&str),
) {
    let mut line = decode_child_output(bytes);
    if truncated {
        line.push_str(LINE_TRUNCATION_MARKER);
    }
    line.push('\n');
    on_line(&line);
    *output = append_limited(std::mem::take(output), &line);
}

fn decode_child_output(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(value) => value.to_owned(),
        Err(_) => encoding_rs::GBK.decode(bytes).0.into_owned(),
    }
}

#[cfg(test)]
mod encoding_tests {
    use super::*;

    #[test]
    fn decodes_utf8_and_gbk_paths() {
        let text = "C:\\Users\\张三\\工作";
        assert_eq!(decode_child_output(text.as_bytes()), text);
        let (encoded, _, _) = encoding_rs::GBK.encode(text);
        assert_eq!(decode_child_output(&encoded), text);
        let mut lines = Vec::new();
        read_bounded_lines(std::io::Cursor::new(encoded.as_ref()), |line| {
            lines.push(line.to_owned())
        })
        .unwrap();
        assert_eq!(lines.concat().trim(), text);
        let mut utf8_lines = Vec::new();
        read_bounded_lines(std::io::Cursor::new(text.as_bytes()), |line| {
            utf8_lines.push(line.to_owned())
        })
        .unwrap();
        assert_eq!(utf8_lines.concat().trim(), text);
    }

    #[test]
    fn tools_status_accepts_unknown_json_fields() {
        let parsed =
            parse_tools_status(include_str!("../tests/fixtures/tools-status.jsonl")).unwrap();
        assert_eq!(parsed[0].display_name, "Maven");
        assert_eq!(parsed[0].destination_path, "C:/用户/tools/maven");
        assert_eq!(parsed[0].inventory, ToolInventory::default());
        assert_eq!(parsed[1].inventory.checked_files, Some(2));
        assert_eq!(parsed[1].inventory.total_files, Some(3));
        assert_eq!(parsed[1].inventory.total_bytes, Some(32));
        assert_eq!(
            parsed[1].inventory.first_failed_path.as_deref(),
            Some("org/example/example.jar")
        );
        assert!(parsed[1].blocks(&["26.2".to_owned()]));
        assert!(!parsed[1].blocks(&["26.3".to_owned()]));
        assert_eq!(parsed[1].id, parsed[2].id);
        let shader = &parsed[3];
        assert_eq!(shader.destination_path, "/work/shader-toolchain");
        assert_eq!(
            shader.inventory.expected_files,
            ["gaius-shaderc.js", "gaius-shaderc.wasm"]
        );
        assert_eq!(shader.inventory.differing_files, ["gaius-shaderc.wasm"]);
        assert!(shader.source_url.is_empty());
        let sdk = &parsed[4];
        assert!(!sdk.blocks(&["26.3".to_owned()]));
        assert_eq!(sdk.inventory.sdk_platforms, ["linux-x64", "windows-x64"]);
    }
}

pub fn parse_tools_status(stdout: &str) -> Result<Vec<ToolStatus>, PatcherError> {
    let tools: Vec<ToolStatus> = stdout
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| {
            let status: ToolStatus = serde_json::from_str(line)
                .map_err(|error| PatcherError(format!("invalid tools status JSON: {error}")))?;
            if status.schema != "gaius-patcher-tools-v1" {
                return Err(PatcherError(format!(
                    "unsupported tools schema: {}",
                    status.schema
                )));
            }
            Ok(status)
        })
        .collect::<Result<_, _>>()?;
    if tools.is_empty() {
        return Err(PatcherError(
            "Java patcher returned no tools status records".to_owned(),
        ));
    }
    Ok(tools)
}

fn append_limited(mut output: String, next: &str) -> String {
    output.push_str(next);
    if output.len() > CAPTURE_LIMIT {
        let mut start = output.len() - CAPTURE_LIMIT;
        while !output.is_char_boundary(start) {
            start += 1;
        }
        output.drain(..start);
    }
    output
}

fn last_build_error(stderr: &str, stdout: &str) -> String {
    stderr
        .lines()
        .rev()
        .find(|line| !line.trim().is_empty())
        .map(str::to_owned)
        .or_else(|| {
            stdout.lines().rev().find_map(|line| {
                parse_progress_line(line).and_then(|event| {
                    event
                        .error
                        .or_else(|| (!event.message.is_empty()).then_some(event.message))
                })
            })
        })
        .unwrap_or_else(|| "The patcher exited with an error.".to_owned())
}

fn last_error(stderr: &str, stdout: &str) -> String {
    stdout
        .lines()
        .rev()
        .find_map(|line| {
            let value: Value = serde_json::from_str(line).ok()?;
            (value.get("schema")?.as_str()? == "gaius-patcher-tools-progress-v1").then(|| {
                value
                    .get("error")
                    .and_then(Value::as_str)
                    .or_else(|| value.get("message").and_then(Value::as_str))
                    .unwrap_or_default()
                    .to_owned()
            })
        })
        .filter(|message| !message.is_empty())
        .or_else(|| {
            stderr
                .lines()
                .rev()
                .find(|line| !line.trim().is_empty())
                .map(str::to_owned)
        })
        .unwrap_or_else(|| "tool installation failed".to_owned())
}

pub fn compare_expected_hashes(output: &Path, version: &str) -> HashComparison {
    let manifest_path = output.join("expected-hashes.json");
    let Ok(bytes) = std::fs::read(manifest_path) else {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    };
    let Ok(manifest) = serde_json::from_slice::<Value>(&bytes) else {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    };
    if manifest.get("schema").and_then(Value::as_str) != Some("gaius-patcher-hashes-v1")
        || manifest.get("version").and_then(Value::as_str) != Some(version)
    {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    }
    let Some(html) = manifest.get("html").and_then(Value::as_object) else {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    };
    let Some(site) = manifest.get("site").and_then(Value::as_array) else {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    };
    if site.is_empty() {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    }

    let mut expected = Vec::with_capacity(site.len() + 1);
    if let (Some(path), Some(hash)) = (
        html.get("path").and_then(Value::as_str),
        html.get("sha256").and_then(Value::as_str),
    ) {
        expected.push((
            PathBuf::from(path),
            hash.to_ascii_lowercase(),
            output.to_path_buf(),
        ));
    } else {
        return HashComparison {
            state: HashState::Unknown,
            mismatches: Vec::new(),
        };
    }
    for file in site {
        let Some(file) = file.as_object() else {
            return HashComparison {
                state: HashState::Unknown,
                mismatches: Vec::new(),
            };
        };
        let (Some(path), Some(hash)) = (
            file.get("path").and_then(Value::as_str),
            file.get("sha256").and_then(Value::as_str),
        ) else {
            return HashComparison {
                state: HashState::Unknown,
                mismatches: Vec::new(),
            };
        };
        expected.push((
            PathBuf::from(path),
            hash.to_ascii_lowercase(),
            output.join("site"),
        ));
    }

    let mut mismatches = Vec::new();
    for (relative, expected_hash, root) in expected {
        let label = format!("{}", relative.display());
        let Some(path) = safe_manifest_path(&root, &relative) else {
            mismatches.push(label);
            continue;
        };
        match hash_file(&path) {
            Ok(actual) if actual == expected_hash => {}
            _ => mismatches.push(label),
        }
    }
    HashComparison {
        state: if mismatches.is_empty() {
            HashState::Match
        } else {
            HashState::Mismatch
        },
        mismatches,
    }
}

fn safe_manifest_path(root: &Path, relative: &Path) -> Option<PathBuf> {
    if relative.as_os_str().is_empty()
        || relative
            .components()
            .any(|component| !matches!(component, std::path::Component::Normal(_)))
    {
        return None;
    }
    let root = root.canonicalize().ok()?;
    let path = root.join(relative).canonicalize().ok()?;
    path.starts_with(&root).then_some(path)
}

fn hash_file(path: &Path) -> io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hash.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

#[cfg(unix)]
fn terminate_process_group(pid: u32, force: bool) {
    let signal = if force { libc::SIGKILL } else { libc::SIGTERM };
    // The child was created as the leader of a new process group.
    unsafe {
        libc::kill(-(pid as i32), signal);
    }
}

#[cfg(windows)]
fn terminate_process_group(pid: u32, _force: bool) {
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .status();
}

#[cfg(not(any(unix, windows)))]
fn terminate_process_group(_pid: u32, _force: bool) {}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(unix)]
    use std::sync::MutexGuard;
    use std::time::SystemTime;

    static PATCHER_ENV_LOCK: Mutex<()> = Mutex::new(());

    #[cfg(unix)]
    struct FakePatcherEnv {
        _guard: MutexGuard<'static, ()>,
        previous: Vec<(String, Option<OsString>)>,
    }

    #[cfg(unix)]
    impl FakePatcherEnv {
        fn new(scenario: &str) -> Self {
            let guard = PATCHER_ENV_LOCK.lock().expect("test environment lock");
            let source =
                Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/fake-patcher.py");
            let values = [
                (
                    "GAIUS_PATCHER_JAVA",
                    Some(source.with_file_name("fake-java").into_os_string()),
                ),
                ("GAIUS_PATCHER_SOURCE", Some(source.into_os_string())),
                ("GAIUS_PATCHER_JAR", None),
                ("GAIUS_BUILDER_MOCK", None),
                (
                    "GAIUS_BUILDER_DISABLE_SLEEP_INHIBIT",
                    Some(OsString::from("1")),
                ),
                (
                    "GAIUS_FAKE_PATCHER_SCENARIO",
                    Some(OsString::from(scenario)),
                ),
            ];
            let previous = values
                .iter()
                .map(|(key, value)| {
                    let prior = env::var_os(key);
                    unsafe {
                        match value {
                            Some(value) => env::set_var(key, value),
                            None => env::remove_var(key),
                        }
                    }
                    ((*key).to_owned(), prior)
                })
                .collect();
            Self {
                _guard: guard,
                previous,
            }
        }
    }

    #[cfg(unix)]
    impl Drop for FakePatcherEnv {
        fn drop(&mut self) {
            for (key, value) in &self.previous {
                unsafe {
                    match value {
                        Some(value) => env::set_var(key, value),
                        None => env::remove_var(key),
                    }
                }
            }
        }
    }

    fn test_output(label: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .expect("system clock")
            .as_nanos();
        let path = env::temp_dir().join(format!(
            "patchgui-t6-{label}-{}-{unique}",
            std::process::id()
        ));
        std::fs::create_dir_all(&path).expect("create isolated test output");
        path
    }

    #[cfg(unix)]
    fn run_fake(
        scenario: &str,
        label: &str,
    ) -> (
        CommandOutput,
        Vec<BuildProgress>,
        Vec<BuildLogLine>,
        PathBuf,
    ) {
        let _environment = FakePatcherEnv::new(scenario);
        let root = test_output(label);
        let task = PatcherClient.start_build(
            vec!["26.2".to_owned()],
            root.clone(),
            None,
            ToolPaths::default(),
            None,
            BuildSettings::default(),
            ProcessControl::default(),
        );
        let output = task
            .result
            .recv_timeout(Duration::from_secs(10))
            .expect("fake patcher result")
            .expect("backend result");
        (
            output,
            task.progress.try_iter().collect(),
            task.logs.try_iter().collect(),
            root,
        )
    }

    #[cfg(unix)]
    #[test]
    fn backend_tool_preflight_obeys_strict_kernel_requirements() {
        let _environment = FakePatcherEnv::new("missing-kernel-tools");
        let root = test_output("kernel-preflight");
        for strict_kernel in [false, true] {
            let output = root.join(if strict_kernel { "strict" } else { "default" });
            let task = PatcherClient.start_build(
                vec!["26.2".to_owned()],
                output.clone(),
                None,
                ToolPaths::default(),
                None,
                BuildSettings {
                    strict_kernel,
                    ..BuildSettings::default()
                },
                ProcessControl::default(),
            );
            let result = task.result.recv_timeout(Duration::from_secs(10)).unwrap();
            if strict_kernel {
                let error = result.unwrap_err().0;
                for id in ["rust-cargo", "wasm32-target", "cargo-cache"] {
                    assert!(error.contains(id), "{error}");
                }
                assert!(!output.exists());
            } else {
                assert_eq!(result.unwrap().exit_code, 0);
                assert!(output.join("26.2/Gaius-26.2.html").exists());
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn t9_tool_paths_reach_status_install_and_build_as_separate_arguments() {
        let _environment = FakePatcherEnv::new("tool-paths");
        let paths = ToolPaths {
            toolchain_dir: Some(PathBuf::from("fixture shared tools")),
            shader_toolchain: Some(PathBuf::from("fixture shader modules")),
        };
        let doctor = PatcherClient.start_doctor(paths.clone(), false);
        assert!(
            doctor
                .receiver
                .recv_timeout(Duration::from_secs(10))
                .unwrap()
                .unwrap()
                .tools[0]
                .ready()
        );
        let install = PatcherClient.start_tools_install(
            vec!["maven-repository".to_owned()],
            paths.clone(),
            false,
        );
        assert!(
            install
                .receiver
                .recv_timeout(Duration::from_secs(10))
                .unwrap()
                .unwrap()[0]
                .ready()
        );
        let output = test_output("tool-paths");
        let task = PatcherClient.start_build(
            vec!["26.3".to_owned()],
            output.clone(),
            None,
            paths,
            None,
            BuildSettings::default(),
            ProcessControl::default(),
        );
        assert_eq!(
            task.result
                .recv_timeout(Duration::from_secs(10))
                .unwrap()
                .unwrap()
                .exit_code,
            0
        );
        std::fs::remove_dir_all(output).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn fake_child_decodes_gbk_and_utf8_chinese_paths() {
        let _environment = FakePatcherEnv::new("encoding");
        let output = run_patcher(
            &[OsString::from("doctor")],
            ProcessControl::default(),
            |_| {},
            |_| {},
            false,
        )
        .expect("fake patcher process should run");
        assert!(
            output.stdout.contains("C:\\Users\\张三\\工作"),
            "{}",
            output.stdout
        );
        assert!(
            output.stderr.contains("日志 C:\\Users\\张三\\文件"),
            "{}",
            output.stderr
        );
    }

    #[cfg(unix)]
    #[test]
    fn official_verify_streams_progress_and_group_completions_before_exit() {
        let _environment = FakePatcherEnv::new("verify-wait");
        let task = PatcherClient.start_verify(
            "26.3".to_owned(),
            Some(PathBuf::from("fixture launcher")),
            None,
        );
        let timeout = Duration::from_secs(3);
        let progress = task.progress.recv_timeout(timeout).unwrap();
        assert_eq!(progress.group, VerifyGroup::Client);
        assert_eq!(progress.checked, 0);
        for expected in VerifyGroup::ALL {
            let group = task.completions.recv_timeout(timeout).unwrap();
            assert_eq!(group.group, expected);
            assert!(group.passed());
        }
        assert!(matches!(
            task.receiver.try_recv(),
            Err(mpsc::TryRecvError::Empty)
        ));

        task.control.cancel();
        let error = task.receiver.recv_timeout(timeout).unwrap().unwrap_err();
        assert!(error.0.contains("cancelled"));
        assert_eq!(task.control.pid(), None);
        assert!(matches!(
            task.receiver.recv_timeout(timeout),
            Err(mpsc::RecvTimeoutError::Disconnected)
        ));
    }

    #[cfg(unix)]
    #[test]
    fn official_verify_preserves_completed_failures_on_nonzero_exit() {
        let _environment = FakePatcherEnv::new("verify-failed");
        let task = PatcherClient.start_verify(
            "1.21.11".to_owned(),
            Some(PathBuf::from("fixture launcher")),
            Some(PathBuf::from("fixture mappings.txt")),
        );
        let report = task
            .receiver
            .recv_timeout(Duration::from_secs(3))
            .unwrap()
            .unwrap();
        assert_eq!(task.control.pid(), None);
        assert!(!report.passed());
        assert_eq!(report.groups[2].failed[0].path, "assets/indexes/34.json");
        assert_eq!(report.groups[3].failed[0].reason, "prerequisite-failed");
        assert_eq!(report.groups[3].failed[0].actual, None);
        assert_eq!(report.groups[3].failure_count, 2);
    }

    fn passing_verify_stream() -> VerifyStream {
        let mut stream = VerifyStream::default();
        for group in VerifyGroup::ALL.into_iter().rev() {
            let line = serde_json::json!({
                "schema": VERIFY_SCHEMA, "event": "group", "group": group.as_str(),
                "checked": 1, "total": 1, "failed": [], "futureField": true
            });
            assert!(matches!(
                stream.read_line(line.to_string().as_bytes(), false),
                Some(VerifyUpdate::Completed(result)) if result.group == group
            ));
        }
        stream
    }

    #[test]
    fn official_verify_requires_complete_and_successful_exit_for_passes() {
        assert!(passing_verify_stream().finish(Some(0)).unwrap().passed());
        assert!(passing_verify_stream().finish(Some(1)).is_err());
        assert!(passing_verify_stream().finish(None).is_err());
        assert!(VerifyStream::default().finish(Some(0)).is_err());
    }

    #[test]
    fn official_verify_rejects_protocol_errors_even_after_all_groups_pass() {
        let duplicate = serde_json::json!({
            "schema": VERIFY_SCHEMA, "event": "group", "group": "client",
            "checked": 1, "total": 1, "failed": []
        });
        let wrong_schema = duplicate
            .to_string()
            .replace(VERIFY_SCHEMA, "unknown-schema");
        let duplicate = duplicate.to_string();
        for (line, truncated) in [
            (duplicate.as_bytes(), false),
            (wrong_schema.as_bytes(), false),
            (b"old patcher output", false),
            (b"\xff", false),
            (b" ", true),
        ] {
            let mut stream = passing_verify_stream();
            assert!(stream.read_line(line, truncated).is_none());
            assert!(stream.finish(Some(0)).is_err());
        }
    }

    #[test]
    fn official_verify_rejects_non_relative_paths_and_bad_counts() {
        for path in [
            "/home/me/file",
            r"C:\Users\me\file",
            r"\server\file",
            "assets/../file",
        ] {
            assert!(!is_relative_verify_path(path), "{path}");
        }
        assert!(is_relative_verify_path("assets/indexes/34.json"));
        for (checked, total, failure_count, failed, valid) in [
            (2, 1, 0, Vec::new(), false),
            (0, 1, 0, Vec::new(), false),
            (
                1,
                1,
                0,
                vec![serde_json::json!({
                    "path": "assets/indexes/34.json", "expected": null, "actual": null, "reason": "missing"
                })],
                false,
            ),
            (
                0,
                0,
                1,
                vec![serde_json::json!({
                    "path": "assets/indexes/34.json", "expected": null, "actual": null, "reason": "prerequisite-failed"
                })],
                true,
            ),
            (
                1,
                1,
                1,
                vec![serde_json::json!({
                    "path": "assets/indexes/34.json", "reason": "missing"
                })],
                false,
            ),
        ] {
            let mut stream = VerifyStream::default();
            let record = serde_json::json!({
                "schema": VERIFY_SCHEMA, "event": "group", "group": "asset-objects",
                "checked": checked, "total": total, "failureCount": failure_count, "failed": failed,
            });
            assert_eq!(
                stream
                    .read_line(record.to_string().as_bytes(), false)
                    .is_some(),
                valid
            );
        }
    }

    #[test]
    fn parses_captured_doctor_output() {
        let report = MockBackend.doctor().expect("fixture should parse");
        assert!(
            report
                .java
                .as_ref()
                .is_some_and(|item| item.state == CheckState::Ready)
        );
        assert!(report.memory.is_some());
        assert_eq!(
            report.game_dir.as_ref().and_then(|item| item.exists),
            Some(false)
        );
        assert_eq!(report.versions.len(), 3);
        assert!(report.versions.iter().all(|version| !version.installed));
        assert!(
            report
                .versions
                .iter()
                .all(|version| version.reason.is_some())
        );
        assert_eq!(report.exit_code, 0);
    }

    #[test]
    fn detects_version_verification_and_java_warnings() {
        let report = parse_doctor_output(
            "  26.3 installed, but the client jar does not match the pinned SHA-1 [needs Java 25+]\n",
            "",
            1,
        );
        let version = &report.versions[0];
        assert!(version.installed);
        assert!(!version.verified);
        assert_eq!(version.required_java, Some(25));
        assert_eq!(version.state, CheckState::Warning);
    }

    #[test]
    fn parses_build_progress_json_lines() {
        let event = parse_progress_line(
            r#"{"schema":"gaius-patcher-progress-v1","phase":"future_phase","step":"future-step","pct":107,"message":"Compiling","error":null,"futureField":{"a":1}}"#,
        )
        .expect("progress event should parse");
        assert_eq!(event.phase, "future_phase");
        assert_eq!(event.pct, 100);
        assert!(event.error.is_none());
        assert!(parse_progress_line("non-json progress noise").is_none());
        assert!(parse_progress_line(r#"{"type":"progress"}"#).is_none());
    }

    #[test]
    fn stderr_reader_caps_unterminated_lines_and_continues_after_truncation() {
        let mut input = vec![b'x'; STREAM_LINE_LIMIT * 8];
        input.extend_from_slice(b"\nnext line\n");
        let mut lines = Vec::new();
        let captured = read_stderr(&input[..], &mut |line| lines.push(line.to_owned()))
            .expect("read bounded stderr lines");
        assert_eq!(lines.len(), 2);
        assert!(lines[0].contains(LINE_TRUNCATION_MARKER));
        assert!(lines[0].len() <= STREAM_LINE_LIMIT + LINE_TRUNCATION_MARKER.len() + 1);
        assert_eq!(lines[1], "next line\n");
        assert!(captured.len() <= STREAM_LINE_LIMIT + LINE_TRUNCATION_MARKER.len() + 1 + 10);
    }

    #[test]
    fn offline_build_passes_local_mappings_only_for_12111() {
        let output = Path::new("out/1.21.11");
        let mappings = Path::new("local/client_mappings.txt");
        let values = |args: Vec<OsString>| {
            args.into_iter()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>()
        };
        let paths = ToolPaths {
            toolchain_dir: Some(PathBuf::from("local/tools cache")),
            shader_toolchain: Some(PathBuf::from("local/shader-toolchain")),
        };
        let minecraft = Path::new("local/.minecraft");
        let settings = BuildSettings::default();
        let args = values(build_args(
            "1.21.11",
            output,
            Some(mappings),
            &paths,
            Some(minecraft),
            &settings,
        ));
        assert!(
            args.windows(2)
                .any(|pair| pair == ["--mappings", "local/client_mappings.txt"])
        );
        assert!(args.iter().any(|arg| arg == "--offline"));
        assert!(
            args.windows(2)
                .any(|pair| pair == ["--minecraft-dir", "local/.minecraft"])
        );
        assert!(!args.iter().any(|arg| arg == "--shader-toolchain"));
        assert!(
            args.windows(2)
                .any(|pair| pair == ["--toolchain-dir", "local/tools cache"])
        );

        let other = values(build_args(
            "26.3",
            Path::new("out/26.3"),
            Some(mappings),
            &paths,
            None,
            &settings,
        ));
        assert!(!other.iter().any(|arg| arg == "--mappings"));
        assert!(
            other
                .windows(2)
                .any(|pair| pair == ["--shader-toolchain", "local/shader-toolchain"])
        );
    }

    #[test]
    fn build_settings_are_passed_as_cli_arguments_and_packs_repeat() {
        let settings = BuildSettings {
            quality: Some("ultra".to_owned()),
            render_distance: Some(24),
            backend: Some("webgpu".to_owned()),
            strict_kernel: true,
            resource_packs: vec![
                PathBuf::from("packs/one.zip"),
                PathBuf::from("packs/two.zip"),
            ],
        };
        let args = build_args(
            "26.2",
            Path::new("out/26.2"),
            None,
            &ToolPaths::default(),
            None,
            &settings,
        )
        .into_iter()
        .map(|arg| arg.to_string_lossy().into_owned())
        .collect::<Vec<_>>();
        assert!(args.iter().any(|arg| arg == "--strict-kernel"));
        for pair in [
            ["--quality", "ultra"],
            ["--render-distance", "24"],
            ["--backend", "webgpu"],
            ["--resource-pack", "packs/one.zip"],
        ] {
            assert!(args.windows(2).any(|actual| actual == pair));
        }
        assert_eq!(
            args.iter()
                .filter(|arg| arg.as_str() == "--resource-pack")
                .count(),
            2
        );
        assert!(args.iter().any(|arg| arg == "packs/two.zip"));
    }

    #[test]
    fn ui_setting_indices_map_to_supported_cli_arguments() {
        for (index, expected) in ["low", "mid", "high", "ultra"].into_iter().enumerate() {
            let settings = BuildSettings::from_ui_indices(index, 12, 0, Vec::new());
            assert_eq!(settings.quality.as_deref(), Some(expected));
            let args = build_args(
                "26.2",
                Path::new("out"),
                None,
                &ToolPaths::default(),
                None,
                &settings,
            )
            .into_iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect::<Vec<_>>();
            assert!(args.windows(2).any(|pair| pair == ["--quality", expected]));
        }

        for (index, expected) in ["webgpu", "webgl2", "auto"].into_iter().enumerate() {
            let settings = BuildSettings::from_ui_indices(1, 12, index, Vec::new());
            assert_eq!(settings.backend.as_deref(), Some(expected));
            let args = build_args(
                "26.2",
                Path::new("out"),
                None,
                &ToolPaths::default(),
                None,
                &settings,
            )
            .into_iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect::<Vec<_>>();
            assert!(args.windows(2).any(|pair| pair == ["--backend", expected]));
        }

        for (distance, expected) in [(0, 2), (2, 2), (12, 12), (32, 32), (40, 32)] {
            let settings = BuildSettings::from_ui_indices(1, distance, 0, Vec::new());
            assert_eq!(settings.render_distance, Some(expected));
            let args = build_args(
                "26.2",
                Path::new("out"),
                None,
                &ToolPaths::default(),
                None,
                &settings,
            )
            .into_iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect::<Vec<_>>();
            assert!(
                args.windows(2)
                    .any(|pair| { pair == ["--render-distance", expected.to_string().as_str()] })
            );
        }
    }

    #[test]
    fn mock_build_covers_terminal_scenarios() {
        for scenario in [
            "ok",
            "fail-hash",
            "fail-build",
            "unsupported-mod",
            "diverged",
            "custom",
            "cancel",
        ] {
            let control = ProcessControl::default();
            let (sender, receiver) = mpsc::channel();
            let result =
                run_mock_build(scenario, &control, &sender, "26.2").expect("mock returns a result");
            assert_eq!(
                result.exit_code == 0,
                matches!(scenario, "ok" | "diverged" | "custom")
            );
            let progress_count = receiver.try_iter().count();
            assert!(progress_count >= if scenario == "cancel" { 2 } else { 4 });
            if scenario == "cancel" {
                assert!(control.is_cancelled());
            }
        }
    }

    #[cfg(unix)]
    #[test]
    fn fake_backend_handles_noise_unknown_fields_stderr_and_hash_match() {
        let (output, progress, logs, root) = run_fake("success", "success");
        assert_eq!(output.exit_code, 0);
        assert!(output.stdout.contains("non-json stdout noise"));
        assert!(progress.iter().any(|event| event.phase == "future_phase"));
        assert!(
            logs.iter()
                .any(|line| line.line.contains("stderr: build started"))
        );
        assert!(
            logs.iter()
                .any(|line| line.line.contains("stderr: build complete"))
        );
        assert_eq!(output.versions[0].hashes.state, HashState::Match);
        assert!(
            progress
                .iter()
                .any(|event| event.phase == "compare" && event.step == "match")
        );
        std::fs::remove_dir_all(root).expect("remove owned test output");
    }

    #[cfg(unix)]
    #[test]
    fn fake_patcher_receives_every_ui_setting_and_repeated_local_packs() {
        let _environment = FakePatcherEnv::new("success");
        let root = test_output("configured-build");
        let first_pack = root.join("first.zip");
        let second_pack = root.join("second pack.zip");
        std::fs::write(&first_pack, b"first pack").expect("write first pack fixture");
        std::fs::write(&second_pack, b"second pack").expect("write second pack fixture");
        for (case, quality_index, backend_index, distance) in
            [(0, 0, 0, 2), (1, 1, 1, 12), (2, 2, 2, 32), (3, 3, 0, 24)]
        {
            let case_root = root.join(format!("case-{case}"));
            let settings = BuildSettings::from_ui_indices(
                quality_index,
                distance,
                backend_index,
                vec![first_pack.clone(), second_pack.clone()],
            );
            let task = PatcherClient.start_build(
                vec!["26.2".to_owned()],
                case_root.clone(),
                None,
                ToolPaths::default(),
                None,
                settings,
                ProcessControl::default(),
            );
            let output = task
                .result
                .recv_timeout(Duration::from_secs(10))
                .expect("fake patcher result")
                .expect("patcher result");
            assert_eq!(output.exit_code, 0);
            let args: Vec<String> = serde_json::from_slice(
                &std::fs::read(case_root.join("26.2/received-args.json")).expect("captured args"),
            )
            .expect("captured arguments parse");
            for (name, value) in [
                ("--quality", ["low", "mid", "high", "ultra"][quality_index]),
                ("--render-distance", ["2", "12", "32", "24"][case]),
                ("--backend", ["webgpu", "webgl2", "auto"][backend_index]),
            ] {
                let index = args
                    .iter()
                    .position(|arg| arg == name)
                    .expect("setting flag passed");
                assert_eq!(args[index + 1], value);
            }
            let received_packs = args
                .windows(2)
                .filter(|pair| pair[0] == "--resource-pack")
                .map(|pair| pair[1].as_str())
                .collect::<Vec<_>>();
            assert_eq!(received_packs.len(), 2);
            assert_eq!(received_packs[0], first_pack.to_string_lossy());
            assert_eq!(received_packs[1], second_pack.to_string_lossy());
            let expected_tail = [
                "--resource-pack",
                first_pack.to_str().unwrap(),
                "--resource-pack",
                second_pack.to_str().unwrap(),
            ];
            assert!(
                args.windows(expected_tail.len())
                    .any(|window| window == expected_tail)
            );
        }
        std::fs::remove_dir_all(root).expect("remove owned test output");
    }

    #[cfg(unix)]
    #[test]
    fn fake_backend_reports_preflight_and_mid_build_errors() {
        for (scenario, label, message) in [
            (
                "preflight",
                "preflight",
                "26.3 requires a prebuilt shader toolchain",
            ),
            (
                "mid-error",
                "mid-error",
                "compiler stopped after the progress event",
            ),
        ] {
            let (output, progress, logs, root) = run_fake(scenario, label);
            assert_ne!(output.exit_code, 0);
            assert!(progress.iter().any(|event| {
                event.phase == "error"
                    && event
                        .error
                        .as_deref()
                        .is_some_and(|error| error.contains(message))
            }));
            assert!(!logs.is_empty());
            std::fs::remove_dir_all(root).expect("remove owned test output");
        }
    }

    #[cfg(unix)]
    #[test]
    fn fake_backend_hash_mismatch_and_missing_manifest_are_not_green() {
        let (mismatch, _, _, mismatch_root) = run_fake("hash-mismatch", "hash-mismatch");
        assert_eq!(mismatch.exit_code, 0);
        let comparison = mismatch.versions[0].hashes.clone();
        assert_eq!(comparison.state, HashState::Mismatch);
        assert_eq!(comparison.mismatches, ["Gaius-26.2.html"]);
        std::fs::remove_dir_all(mismatch_root).expect("remove owned test output");

        let (missing, _, _, missing_root) = run_fake("hash-missing", "hash-missing");
        assert_eq!(missing.exit_code, 0);
        assert_eq!(missing.versions[0].hashes.state, HashState::Unknown);
        std::fs::remove_dir_all(missing_root).expect("remove owned test output");
    }

    #[cfg(unix)]
    #[test]
    fn fake_backend_cancel_kills_only_its_child_process() {
        let _environment = FakePatcherEnv::new("slow");
        let root = test_output("cancel");
        let sentinel = root.join("keep.txt");
        std::fs::write(&sentinel, "untouched").expect("write sentinel");
        let control = ProcessControl::default();
        let task = PatcherClient.start_build(
            vec!["26.2".to_owned()],
            root.join("output"),
            None,
            ToolPaths::default(),
            None,
            BuildSettings::default(),
            control.clone(),
        );
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        let pid = loop {
            if let Some(pid) = control.pid() {
                break pid;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "fake child did not start"
            );
            thread::sleep(Duration::from_millis(20));
        };
        control.cancel();
        let result = task
            .result
            .recv_timeout(Duration::from_secs(5))
            .expect("build result after cancel")
            .expect("worker result");
        assert!(control.is_cancelled());
        assert_ne!(result.exit_code, 0);
        assert_eq!(
            std::fs::read_to_string(&sentinel).expect("sentinel remains"),
            "untouched"
        );
        assert!(!root.join("output/26.2").exists());
        let gone = unsafe { libc::kill(pid as i32, 0) == -1 }
            && std::io::Error::last_os_error().raw_os_error() == Some(libc::ESRCH);
        assert!(gone, "recorded child PID {pid} is still alive");
        std::fs::remove_dir_all(root).expect("remove owned test output");
    }

    #[cfg(unix)]
    #[test]
    fn fake_backend_cancel_kills_descendants_after_leader_exits() {
        let _environment = FakePatcherEnv::new("slow-tree");
        let root = test_output("cancel-tree");
        let sentinel = root.join("keep.txt");
        std::fs::write(&sentinel, "untouched").expect("write sentinel");
        let output = root.join("output/26.2");
        let child_pid_path = output.join("child.pid");
        let control = ProcessControl::default();
        let task = PatcherClient.start_build(
            vec!["26.2".to_owned()],
            root.join("output"),
            None,
            ToolPaths::default(),
            None,
            BuildSettings::default(),
            control.clone(),
        );
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        let child_pid = loop {
            if let Ok(value) = std::fs::read_to_string(&child_pid_path)
                && let Ok(pid) = value.parse::<u32>()
            {
                break pid;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "descendant did not start"
            );
            thread::sleep(Duration::from_millis(20));
        };
        let leader_pid = control.pid().expect("patcher leader PID");
        control.cancel();
        let _result = task
            .result
            .recv_timeout(Duration::from_secs(8))
            .expect("build worker exits after cancelling process group")
            .expect("worker result");
        assert_eq!(
            std::fs::read_to_string(&sentinel).expect("sentinel remains"),
            "untouched"
        );
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        loop {
            let result = unsafe { libc::kill(-(leader_pid as i32), 0) };
            if result == -1 && std::io::Error::last_os_error().raw_os_error() == Some(libc::ESRCH) {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "process group {leader_pid} still exists; descendant PID {child_pid} was not reaped"
            );
            thread::sleep(Duration::from_millis(25));
        }
        std::fs::remove_dir_all(root).expect("remove owned test output");
    }
}
