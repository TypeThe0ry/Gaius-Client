use std::path::PathBuf;

use eframe::egui;

use gaius_builder::app::{BuilderApp, ScreenTheme, render_screenshot};
use gaius_builder::i18n::Language;
use gaius_builder::patcher_client::PatcherBackend;

const USAGE: &str = "Gaius Builder\n\
Usage:\n\
  gaius-builder [--gui]\n\
  gaius-builder doctor [--json]\n\
  gaius-builder version\n\
  gaius-builder --screenshot <out.png> [--page <1-6>] [--theme dark|light] [--lang zh|en]\n\
      [--size <width>x<height>] [--scale <factor>] [--state page-1..page-6|terms-accepted|mods-scrolled|toolchain-field|running|detect-fail|hash-error|build-success|build-fail|cancelled|unsupported-mod|badge-green|badge-yellow|badge-red|real-preflight-error]\n";

enum CommandLine {
    Gui,
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
        scale: f32,
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
        CommandLine::Gui => run_gui(),
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
            scale,
            state,
        } => render_screenshot(&path, page, theme, language, size, scale, &state)
            .map(|()| {
                println!("saved screenshot {}", path.display());
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
    if args.is_empty() || args == ["--gui"] {
        return Ok(CommandLine::Gui);
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
    if args.first().is_some_and(|arg| arg == "--screenshot") {
        return parse_screenshot_args(&args);
    }
    Err(format!("unknown command: {}", args[0]))
}

fn parse_screenshot_args(args: &[String]) -> Result<CommandLine, String> {
    let mut path = None;
    let mut page = 2;
    let mut theme = ScreenTheme::Dark;
    let mut language = Language::Chinese;
    let mut size = [960, 620];
    let mut scale = 1.0;
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
            "--scale" => {
                scale = value
                    .parse::<f32>()
                    .map_err(|_| "--scale must be a positive number".to_owned())?;
                if !scale.is_finite() || scale <= 0.0 || scale > 4.0 {
                    return Err("--scale must be greater than 0 and no more than 4".to_owned());
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
            other => return Err(format!("unknown screenshot option: {other}")),
        }
        if index < args.len() && !args[index].starts_with("--") {
            return Err(format!("unexpected value: {}", args[index]));
        }
    }
    let path = path.ok_or_else(|| "--screenshot needs an output path".to_owned())?;
    if state == "build-success" {
        page = 6;
    }
    Ok(CommandLine::Screenshot {
        path,
        page,
        theme,
        language,
        size,
        scale,
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

fn run_gui() -> Result<i32, String> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Gaius Builder")
            .with_inner_size([960.0, 620.0])
            .with_min_inner_size([800.0, 540.0]),
        ..Default::default()
    };
    eframe::run_native(
        "Gaius Builder",
        options,
        Box::new(|context| Ok(Box::new(BuilderApp::native(context)))),
    )
    .map_err(|error| format!("could not open Gaius Builder: {error}"))?;
    Ok(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_cli_modes_and_screenshot_options() {
        assert!(matches!(parse_args(Vec::new()), Ok(CommandLine::Gui)));
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
