//! Local patcher process adapter.
//!
//! Build progress uses JSON Lines. Each stdout line is one JSON object with
//! `schema: "gaius-patcher-progress-v1"`, a phase, step, percentage, English
//! message, and optional error. Unknown JSON fields and non-JSON stdout lines
//! are ignored for control flow and retained in bounded raw output.

use std::env;
use std::ffi::OsString;
use std::io::{self, BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

const CAPTURE_LIMIT: usize = 256 * 1024;
const STREAM_LINE_LIMIT: usize = 16 * 1024;
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

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DoctorReport {
    pub java: Option<DoctorItem>,
    pub memory: Option<DoctorItem>,
    pub game_dir: Option<DoctorItem>,
    pub profiles_dir: Option<String>,
    pub work_dir: Option<DoctorItem>,
    pub versions: Vec<VersionStatus>,
    pub warnings: Vec<String>,
    pub raw_output: String,
    pub stderr: String,
    pub exit_code: i32,
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
    pub shader_toolchain: Option<&'a Path>,
    pub minecraft_dir: Option<&'a Path>,
    pub settings: &'a BuildSettings,
}

#[derive(Clone, Debug, Default)]
pub struct BuildSettings {
    pub quality: Option<String>,
    pub render_distance: Option<u32>,
    pub backend: Option<String>,
    pub resource_packs: Vec<PathBuf>,
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
        self.doctor_with_control(ProcessControl::default())
    }
}

impl PatcherClient {
    pub fn start_doctor(&self) -> DoctorTask {
        let control = ProcessControl::default();
        let worker_control = control.clone();
        let (sender, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("gaius-doctor".to_owned())
            .spawn(move || {
                let report = Self.doctor_with_control(worker_control);
                let _ = sender.send(report);
            })
            .expect("doctor worker thread must start");
        DoctorTask { receiver, control }
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
            request.shader_toolchain,
            request.minecraft_dir,
            request.settings,
        );
        run_patcher(&args, control, on_progress, on_log, true)
    }

    pub fn start_build(
        &self,
        versions: Vec<String>,
        output: PathBuf,
        mappings: Option<PathBuf>,
        shader_toolchain: Option<PathBuf>,
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
                        let version_toolchain = (version == "26.3")
                            .then_some(shader_toolchain.as_deref())
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
                                        shader_toolchain: version_toolchain,
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

    fn doctor_with_control(&self, control: ProcessControl) -> Result<DoctorReport, PatcherError> {
        let output = run_patcher(&[OsString::from("doctor")], control, |_| {}, |_| {}, false)?;
        Ok(parse_doctor_output(
            &output.stdout,
            &output.stderr,
            output.exit_code,
        ))
    }
}

fn build_args(
    version: &str,
    output: &Path,
    mappings: Option<&Path>,
    shader_toolchain: Option<&Path>,
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
    if version == "1.21.11"
        && let Some(path) = mappings
    {
        args.extend([OsString::from("--mappings"), path.as_os_str().to_owned()]);
    }
    if version == "26.3"
        && let Some(path) = shader_toolchain
    {
        args.extend([
            OsString::from("--shader-toolchain"),
            path.as_os_str().to_owned(),
        ]);
    }
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
        Ok(parse_doctor_output(
            include_str!("../tests/fixtures/doctor-output.txt"),
            "",
            0,
        ))
    }
}

pub fn parse_progress_line(line: &str) -> Option<BuildProgress> {
    let value: Value = serde_json::from_str(line).ok()?;
    let object = value.as_object()?;
    if object.get("schema")?.as_str()? != "gaius-patcher-progress-v1" {
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
    mut on_log: impl FnMut(String) + Send + 'static,
    inhibit_sleep: bool,
) -> Result<CommandOutput, PatcherError> {
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
    let (killer_tx, killer_rx) = mpsc::channel();
    control.attach(pid, killer_tx);
    let killer = thread::spawn(move || {
        if matches!(killer_rx.recv(), Ok(true)) {
            terminate_process_group(pid, false);
            thread::sleep(Duration::from_secs(2));
            terminate_process_group(pid, true);
        }
    });

    let stdout_pipe = child
        .stdout
        .take()
        .ok_or_else(|| PatcherError("Java patcher stdout was not piped".to_owned()))?;
    let stderr_pipe = child
        .stderr
        .take()
        .ok_or_else(|| PatcherError("Java patcher stderr was not piped".to_owned()))?;
    let stdout_reader = thread::spawn(move || read_stdout(stdout_pipe, &mut on_progress));
    let stderr_reader = thread::spawn(move || read_stderr(stderr_pipe, &mut on_log));

    let status = child
        .wait()
        .map_err(|error| PatcherError(format!("could not wait for Java patcher: {error}")));
    control.finish();
    let _ = killer.join();
    let stdout = stdout_reader
        .join()
        .map_err(|_| PatcherError("Java patcher stdout reader stopped unexpectedly".to_owned()))?
        .map_err(|error| PatcherError(format!("could not read Java patcher stdout: {error}")))?;
    let stderr = stderr_reader
        .join()
        .map_err(|_| PatcherError("Java patcher stderr reader stopped unexpectedly".to_owned()))?
        .map_err(|error| PatcherError(format!("could not read Java patcher stderr: {error}")))?;
    let status = status?;
    Ok(CommandOutput {
        stdout,
        stderr,
        exit_code: status.code().unwrap_or(1),
        pid,
        versions: Vec::new(),
    })
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

fn read_stderr(pipe: impl Read, on_log: &mut impl FnMut(String)) -> io::Result<String> {
    read_bounded_lines(pipe, |line| on_log(line.to_owned()))
}

fn read_bounded_lines(pipe: impl Read, mut on_line: impl FnMut(&str)) -> io::Result<String> {
    let mut output = String::new();
    let mut reader = BufReader::new(pipe);
    let mut line = Vec::with_capacity(STREAM_LINE_LIMIT);
    let mut truncated = false;
    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            if !line.is_empty() || truncated {
                emit_bounded_line(&line, truncated, &mut output, &mut on_line);
            }
            break;
        }
        let newline = available.iter().position(|byte| *byte == b'\n');
        let content_len = newline.unwrap_or(available.len());
        let remaining = STREAM_LINE_LIMIT.saturating_sub(line.len());
        let kept = content_len.min(remaining);
        line.extend_from_slice(&available[..kept]);
        truncated |= kept < content_len;
        let consumed = content_len + usize::from(newline.is_some());
        reader.consume(consumed);
        if newline.is_some() {
            emit_bounded_line(&line, truncated, &mut output, &mut on_line);
            line.clear();
            truncated = false;
        }
    }
    Ok(output)
}

fn emit_bounded_line(
    bytes: &[u8],
    truncated: bool,
    output: &mut String,
    on_line: &mut impl FnMut(&str),
) {
    let mut line = String::from_utf8_lossy(bytes).into_owned();
    if truncated {
        line.push_str(LINE_TRUNCATION_MARKER);
    }
    line.push('\n');
    on_line(&line);
    *output = append_limited(std::mem::take(output), &line);
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
                ("GAIUS_PATCHER_JAVA", Some(OsString::from("python3"))),
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
            None,
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
        let toolchain = Path::new("local/shader-toolchain");
        let minecraft = Path::new("local/.minecraft");
        let settings = BuildSettings::default();
        let args = values(build_args(
            "1.21.11",
            output,
            Some(mappings),
            Some(toolchain),
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

        let other = values(build_args(
            "26.3",
            Path::new("out/26.3"),
            Some(mappings),
            Some(toolchain),
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
            resource_packs: vec![
                PathBuf::from("packs/one.zip"),
                PathBuf::from("packs/two.zip"),
            ],
        };
        let args = build_args("26.2", Path::new("out/26.2"), None, None, None, &settings)
            .into_iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect::<Vec<_>>();
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
    fn fake_backend_receives_configured_defaults_and_local_packs() {
        let _environment = FakePatcherEnv::new("success");
        let root = test_output("configured-build");
        let first_pack = root.join("first.zip");
        let second_pack = root.join("second pack.zip");
        std::fs::write(&first_pack, b"first pack").expect("write first pack fixture");
        std::fs::write(&second_pack, b"second pack").expect("write second pack fixture");
        let task = PatcherClient.start_build(
            vec!["26.2".to_owned()],
            root.clone(),
            None,
            None,
            None,
            BuildSettings {
                quality: Some("high".to_owned()),
                render_distance: Some(20),
                backend: Some("webgl2".to_owned()),
                resource_packs: vec![first_pack, second_pack],
            },
            ProcessControl::default(),
        );
        let output = task
            .result
            .recv_timeout(Duration::from_secs(10))
            .expect("fake patcher result")
            .expect("backend result");
        assert_eq!(output.exit_code, 0);
        let args: Vec<String> = serde_json::from_slice(
            &std::fs::read(root.join("26.2/received-args.json")).expect("captured args"),
        )
        .expect("captured arguments parse");
        for (name, value) in [
            ("--quality", "high"),
            ("--render-distance", "20"),
            ("--backend", "webgl2"),
        ] {
            let index = args
                .iter()
                .position(|arg| arg == name)
                .expect("setting flag passed");
            assert_eq!(args[index + 1], value);
        }
        let packs = args
            .windows(2)
            .filter(|pair| pair[0] == "--resource-pack")
            .map(|pair| pair[1].as_str())
            .collect::<Vec<_>>();
        assert_eq!(packs.len(), 2);
        assert_eq!(packs[0], root.join("first.zip").to_string_lossy());
        assert_eq!(packs[1], root.join("second pack.zip").to_string_lossy());
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
            None,
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
            None,
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
