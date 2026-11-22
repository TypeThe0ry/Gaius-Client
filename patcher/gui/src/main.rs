use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

use eframe::egui;

use gaius_builder::app::{
    BuilderApp, DisplayOptions, DpiPreview, ScreenTheme, render_screenshot_at_dpi,
};
use gaius_builder::i18n::Language;
use gaius_builder::patcher_client::PatcherBackend;

const USAGE: &str = "Gaius Builder\n\
Usage:\n\
  gaius-builder [--gui] [--work <dir>] [--scale <factor>]\n\
  gaius-builder --dpi-report <file.json> [--dpi-hold] [--work <dir>] [--scale <factor>] [--page <1-6>] [--state normal|config-mods|build-success] [--theme dark|light] [--lang zh|en]\n\
  gaius-builder doctor [--json]\n\
  gaius-builder version\n\
  gaius-builder --screenshot <out.png> [--page <1-6>] [--theme dark|light] [--lang zh|en]\n\
      [--work <dir>] [--size <width>x<height>] [--scale <factor>] [--native-ppp <factor>] [--state page-1..page-6|verify-ok|verify-running|verify-failed-index|terms-pre-accepted|terms-accepted|config-resources|config-mods|mods-scrolled|toolchain-field|running|detect-fail|hash-error|build-success|build-fail|cancelled|unsupported-mod|badge-green|badge-yellow|badge-red|real-preflight-error|tools-missing|tools-optional|tools-strict|tools-python-exe|tools-downloading|tools-failed|tools-ready|shader-verified|shader-missing|shader-missing-scrolled|shader-tampered|maven-partial|maven-ready|diagnostics-chinese]\n";

enum CommandLine {
    Gui(DisplayOptions),
    DpiReport(DpiPreview, DisplayOptions),
    Doctor {
        json: bool,
    },
    Version,
    Help,
    Screenshot {
        path: PathBuf,
        page: usize,
        theme: ScreenTheme,
        language: Language,
        size: [u32; 2],
        display: DisplayOptions,
        native_ppp: f32,
        state: String,
    },
}

fn main() {
    let command = match parse_args(std::env::args().skip(1).collect()) {
        Ok(command) => command,
        Err(error) => {
            eprintln!("{error}\n\n{USAGE}");
            std::process::exit(2);
        }
    };
    let result = match command {
        CommandLine::Gui(display) => run_gui(None, display),
        CommandLine::DpiReport(preview, display) => run_gui(Some(preview), display),
        CommandLine::Doctor { json } => run_doctor(json),
        CommandLine::Version => {
            println!("gaius-builder {}", env!("CARGO_PKG_VERSION"));
            Ok(0)
        }
        CommandLine::Help => {
            print!("{USAGE}");
            Ok(0)
        }
        CommandLine::Screenshot {
            path,
            page,
            theme,
            language,
            size,
            display,
            native_ppp,
            state,
        } => render_screenshot_at_dpi(
            &path,
            page,
            theme,
            language,
            size,
            (native_ppp, &display),
            &state,
        )
        .map(|report| {
            println!("saved screenshot {}", path.display());
            println!("{report}");
            0
        })
        .map_err(|error| error.to_string()),
    };
    match result {
        Ok(code) => std::process::exit(code),
        Err(error) => {
            eprintln!("error: {error}");
            std::process::exit(1);
        }
    }
}

fn parse_args(args: Vec<String>) -> Result<CommandLine, String> {
    if args
        .first()
        .is_none_or(|arg| matches!(arg.as_str(), "--gui" | "--work" | "--scale"))
    {
        let mut display = DisplayOptions::default();
        let mut args = args
            .iter()
            .skip(usize::from(args.first().is_some_and(|arg| arg == "--gui")));
        while let Some(flag) = args.next() {
            let value = args.next().ok_or_else(|| format!("{flag} needs a value"))?;
            if !parse_display_flag(&mut display, flag, value)? {
                return Err(format!("unknown window option: {flag}"));
            }
        }
        return Ok(CommandLine::Gui(display));
    }
    if args == ["--help"] || args == ["-h"] || args == ["help"] {
        return Ok(CommandLine::Help);
    }
    if args == ["version"] || args == ["--version"] {
        return Ok(CommandLine::Version);
    }
    if args.first().is_some_and(|arg| arg == "doctor") {
        if args.len() == 1 {
            return Ok(CommandLine::Doctor { json: false });
        }
        if args.len() == 2 && args[1] == "--json" {
            return Ok(CommandLine::Doctor { json: true });
        }
        return Err("doctor accepts only the optional --json flag".to_owned());
    }
    if args[0] == "--dpi-report" {
        return parse_dpi_report_args(&args);
    }
    if args.first().is_some_and(|arg| arg == "--screenshot") {
        return parse_screenshot_args(&args);
    }
    Err(format!("unknown command: {}", args[0]))
}

fn parse_dpi_report_args(args: &[String]) -> Result<CommandLine, String> {
    let path = args
        .get(1)
        .filter(|arg| !arg.starts_with("--"))
        .ok_or("--dpi-report requires an output JSON path")?;
    let mut common = vec![
        "--screenshot".to_owned(),
        path.clone(),
        "--page".to_owned(),
        "1".to_owned(),
    ];
    let mut hold = false;
    let mut index = 2;
    while let Some(arg) = args.get(index) {
        index += 1;
        if arg == "--dpi-hold" && !hold {
            hold = true;
            continue;
        }
        if !matches!(
            arg.as_str(),
            "--page" | "--theme" | "--lang" | "--state" | "--work" | "--scale"
        ) {
            return Err(format!("unknown DPI report option: {arg}"));
        }
        let value = args
            .get(index)
            .ok_or_else(|| format!("{arg} needs a value"))?;
        if arg == "--state" && !matches!(value.as_str(), "normal" | "config-mods" | "build-success")
        {
            return Err(
                "--dpi-report supports only normal, config-mods or build-success".to_owned(),
            );
        }
        common.extend([arg.clone(), value.clone()]);
        index += 1;
    }
    let CommandLine::Screenshot {
        path,
        mut page,
        theme,
        language,
        state,
        display,
        ..
    } = parse_screenshot_args(&common)?
    else {
        unreachable!()
    };
    if state == "config-mods" {
        page = 4;
    }
    Ok(CommandLine::DpiReport(
        DpiPreview {
            path,
            hold,
            completion: Arc::new(OnceLock::new()),
            page,
            theme,
            language,
            state,
        },
        display,
    ))
}

fn parse_display_flag(
    display: &mut DisplayOptions,
    flag: &str,
    value: &str,
) -> Result<bool, String> {
    match flag {
        "--work" => {
            if value.is_empty() || value.starts_with("--") {
                return Err("--work needs a directory path".to_owned());
            }
            display.work = Some(PathBuf::from(value));
        }
        "--scale" => {
            let scale = value
                .parse::<f32>()
                .map_err(|_| "--scale needs a factor".to_owned())?;
            if !(0.0 < scale && scale <= 4.0) {
                return Err("--scale must be greater than 0 and no more than 4".to_owned());
            }
            display.scale = Some(scale);
        }
        _ => return Ok(false),
    }
    Ok(true)
}

fn parse_screenshot_args(args: &[String]) -> Result<CommandLine, String> {
    let mut path = None;
    let mut page = 2;
    let mut theme = ScreenTheme::Dark;
    let mut language = Language::Chinese;
    let mut size = [960, 620];
    let mut display = DisplayOptions::default();
    let mut native_ppp = 1.0;
    let mut state = "normal".to_owned();
    let mut index = 0;
    while index < args.len() {
        let flag = args[index].as_str();
        index += 1;
        let value = args
            .get(index)
            .ok_or_else(|| format!("{flag} needs a value"))?;
        index += 1;
        match flag {
            "--screenshot" => path = Some(PathBuf::from(value)),
            "--page" => {
                page = value
                    .parse::<usize>()
                    .map_err(|_| "--page must be an integer from 1 to 6".to_owned())?;
                if !(1..=6).contains(&page) {
                    return Err("--page must be an integer from 1 to 6".to_owned());
                }
            }
            "--theme" => {
                theme = match value.as_str() {
                    "dark" => ScreenTheme::Dark,
                    "light" => ScreenTheme::Light,
                    _ => return Err("--theme must be dark or light".to_owned()),
                };
            }
            "--lang" => {
                language =
                    Language::parse(value).ok_or_else(|| "--lang must be zh or en".to_owned())?;
            }
            "--size" => size = parse_size(value)?,
            "--native-ppp" => {
                native_ppp = value
                    .parse::<f32>()
                    .map_err(|_| "--native-ppp needs a factor".to_owned())?;
                if !(0.75..=4.0).contains(&native_ppp) {
                    return Err("--native-ppp must be between 0.75 and 4.0".to_owned());
                }
            }
            "--state" => {
                state = value.clone();
                if let Some(page_text) = value.strip_prefix("page-") {
                    page = page_text
                        .parse::<usize>()
                        .map_err(|_| "--state page-N must use a page from 1 to 6".to_owned())?;
                    if !(1..=6).contains(&page) {
                        return Err("--state page-N must use a page from 1 to 6".to_owned());
                    }
                    state = "normal".to_owned();
                }
            }
            other => {
                if !parse_display_flag(&mut display, other, value)? {
                    return Err(format!("unknown screenshot option: {other}"));
                }
            }
        }
        if index < args.len() && !args[index].starts_with("--") {
            return Err(format!("unexpected value: {}", args[index]));
        }
    }
    let path = path.ok_or_else(|| "--screenshot needs an output path".to_owned())?;
    match state.as_str() {
        "build-success" => page = 6,
        "terms-pre-accepted" => page = 3,
        "verify-ok"
        | "verify-running"
        | "verify-failed-index"
        | "tools-optional"
        | "tools-strict"
        | "tools-python-exe" => page = 2,
        _ => {}
    }
    Ok(CommandLine::Screenshot {
        path,
        page,
        theme,
        language,
        size,
        display,
        native_ppp,
        state,
    })
}

fn parse_size(value: &str) -> Result<[u32; 2], String> {
    let (width, height) = value
        .split_once(['x', 'X'])
        .ok_or_else(|| "--size must use WIDTHxHEIGHT".to_owned())?;
    let size = [
        width
            .parse::<u32>()
            .map_err(|_| "--size width and height must be integers".to_owned())?,
        height
            .parse::<u32>()
            .map_err(|_| "--size width and height must be integers".to_owned())?,
    ];
    if size[0] < 800 || size[1] < 540 {
        return Err("--size must be at least 800x540".to_owned());
    }
    Ok(size)
}

fn run_doctor(json: bool) -> Result<i32, String> {
    let report = gaius_builder::patcher_client::PatcherClient
        .doctor()
        .map_err(|error| error.to_string())?;
    if json {
        let output = serde_json::to_string_pretty(&report)
            .map_err(|error| format!("could not serialize doctor result: {error}"))?;
        println!("{output}");
    } else {
        print!("{}", report.raw_output);
        if !report.stderr.is_empty() {
            eprint!("{}", report.stderr);
        }
    }
    Ok(report.exit_code)
}

fn run_gui(preview: Option<DpiPreview>, display: DisplayOptions) -> Result<i32, String> {
    let completion = preview.as_ref().map(|preview| preview.completion.clone());
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Gaius Builder")
            .with_inner_size(gaius_builder::dpi::DEFAULT_CLIENT_LOGICAL.map(|point| point as f32)),
        ..Default::default()
    };
    eframe::run_native(
        "Gaius Builder",
        options,
        Box::new(move |context| {
            Ok(Box::new(match preview {
                Some(options) => BuilderApp::native_preview(context, options, &display),
                None => BuilderApp::native(context, &display),
            }))
        }),
    )
    .map_err(|error| format!("could not open Gaius Builder: {error}"))?;
    if let Some(completion) = completion {
        completion
            .get()
            .cloned()
            .ok_or("DPI preview closed before a frame was captured")??;
    }
    Ok(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_options_are_shared_across_window_and_preview_modes() {
        let parse = |args: &[&str]| parse_args(args.iter().map(|arg| (*arg).into()).collect());
        for prefix in [
            &["--gui"][..],
            &["--dpi-report", "dpi.json"],
            &["--screenshot", "out.png"],
        ] {
            let args = [prefix, &["--work", "custom work", "--scale", "1.75"]].concat();
            let display = match parse(&args).unwrap() {
                CommandLine::Gui(display)
                | CommandLine::DpiReport(_, display)
                | CommandLine::Screenshot { display, .. } => display,
                _ => panic!("expected a display command"),
            };
            assert_eq!(display.work, Some(PathBuf::from("custom work")));
            assert_eq!(display.scale, Some(1.75));
        }
    }

    #[test]
    fn parses_safe_native_dpi_previews() {
        let parse = |args: &[&str]| parse_args(args.iter().map(|arg| (*arg).into()).collect());
        let Ok(CommandLine::DpiReport(preview, display)) = parse(&["--dpi-report", "dpi.json"])
        else {
            panic!("preview expected")
        };
        assert_eq!(preview.page, 1);
        assert!(!preview.hold);
        assert_eq!(display.scale, None);
        let Ok(CommandLine::DpiReport(preview, _)) = parse(&[
            "--dpi-report",
            "DPI report.json",
            "--dpi-hold",
            "--state",
            "config-mods",
            "--lang",
            "en",
        ]) else {
            panic!("preview expected")
        };
        assert_eq!(preview.page, 4);
        assert_eq!(preview.language, Language::English);
        assert!(preview.hold);
        assert!(
            parse(&[
                "--dpi-report",
                "test.json",
                "--state",
                "real-preflight-error"
            ])
            .is_err()
        );
        assert!(parse(&["--dpi-report", "--dpi-hold"]).is_err());
    }

    #[test]
    fn parses_cli_modes_and_screenshot_options() {
        assert!(matches!(parse_args(Vec::new()), Ok(CommandLine::Gui(_))));
        assert!(matches!(
            parse_args(vec!["doctor".to_owned(), "--json".to_owned()]),
            Ok(CommandLine::Doctor { json: true })
        ));
        assert!(matches!(
            parse_args(vec![
                "--screenshot".to_owned(),
                "out.png".to_owned(),
                "--page".to_owned(),
                "2".to_owned(),
                "--theme".to_owned(),
                "light".to_owned(),
                "--lang".to_owned(),
                "en".to_owned(),
                "--size".to_owned(),
                "800x540".to_owned(),
                "--scale".to_owned(),
                "1.25".to_owned(),
            ]),
            Ok(CommandLine::Screenshot { .. })
        ));
        match parse_args(vec![
            "--screenshot".to_owned(),
            "success.png".to_owned(),
            "--state".to_owned(),
            "build-success".to_owned(),
        ]) {
            Ok(CommandLine::Screenshot { page, state, .. }) => {
                assert_eq!(page, 6);
                assert_eq!(state, "build-success");
            }
            _ => panic!("unexpected command"),
        }
        assert!(parse_args(vec!["doctor".to_owned(), "--offline".to_owned()]).is_err());
    }
}
