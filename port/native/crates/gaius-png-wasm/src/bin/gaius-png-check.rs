use std::fs;
use std::hint::black_box;
use std::time::Instant;

/// Rejection-set rows carry a fourth field naming what the kernel may do: "decline" (the Java
/// decoder rejects the input, or reads it in a way the kernel leaves to Java), "match" (the
/// kernel must return the Java bytes) or "match-or-decline".
fn check_verdicts(manifest: &str) -> Result<(), Box<dyn std::error::Error>> {
    let (mut matched, mut declined, mut optional_declines) = (0usize, 0usize, 0usize);
    for (line_number, line) in manifest.lines().enumerate() {
        if line.is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split('\t').collect();
        if fields.len() != 4 {
            return Err(format!("invalid verdict row {}", line_number + 1).into());
        }
        let mut payload = fields[1].parse::<u32>()?.to_le_bytes().to_vec();
        payload.extend_from_slice(&fs::read(fields[0])?);
        match (gaius_png_wasm::job::run_png_decode(&payload), fields[3]) {
            (Ok(actual), "match" | "match-or-decline") => {
                if actual != fs::read(fields[2])? {
                    return Err(format!("pixel mismatch: {} channels={}", fields[0], fields[1]).into());
                }
                matched += 1;
            }
            (Ok(_), _) => return Err(format!("kernel accepted what it must decline: {}", fields[0]).into()),
            (Err(error), "decline" | "match-or-decline") => {
                if error.status != gaius_kernel_abi::Status::BadPayload {
                    return Err(format!("{}: unexpected status {:?}", fields[0], error.status).into());
                }
                declined += 1;
                if fields[3] != "decline" {
                    optional_declines += 1;
                }
            }
            (Err(error), _) => {
                return Err(format!("kernel declined a required match: {}: {}", fields[0], error.message).into())
            }
        }
    }
    println!(
        "{{\"matched\":{matched},\"declined\":{declined},\"optionalDeclines\":{optional_declines},\"verdicts\":true}}"
    );
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments: Vec<String> = std::env::args().collect();
    if arguments.len() == 3 && arguments[2] == "verdicts" {
        return check_verdicts(&fs::read_to_string(&arguments[1])?);
    }
    if arguments.len() != 3 {
        return Err("usage: gaius-png-check <manifest.tsv> <rounds> | gaius-png-check <verdicts.tsv> verdicts".into());
    }
    let rounds: usize = arguments[2].parse()?;
    if rounds == 0 {
        return Err("rounds must be positive".into());
    }
    let manifest = fs::read_to_string(&arguments[1])?;
    let mut inputs = Vec::new();
    let mut input_bytes = 0usize;
    let mut output_bytes = 0usize;
    for (line_number, line) in manifest.lines().enumerate() {
        if line.is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split('\t').collect();
        if fields.len() != 3 {
            return Err(format!("invalid manifest row {}", line_number + 1).into());
        }
        let channels: u32 = fields[1].parse()?;
        let encoded = fs::read(fields[0])?;
        let expected = fs::read(fields[2])?;
        let mut payload = channels.to_le_bytes().to_vec();
        payload.extend_from_slice(&encoded);
        let actual = gaius_png_wasm::job::run_png_decode(&payload)
            .map_err(|error| format!("{}: {}", fields[0], error.message))?;
        if actual != expected {
            let mismatch = actual.iter().zip(&expected).position(|(left, right)| left != right);
            return Err(format!(
                "pixel mismatch: {} channels={} offset={mismatch:?}",
                fields[0], channels
            )
            .into());
        }
        input_bytes += encoded.len();
        output_bytes += actual.len() - 16;
        inputs.push(payload);
    }
    if inputs.is_empty() {
        return Err("empty PNG manifest".into());
    }
    for _ in 0..2 {
        for payload in &inputs {
            black_box(gaius_png_wasm::job::run_png_decode(black_box(payload)).map_err(|error| error.message)?);
        }
    }
    let started = Instant::now();
    for _ in 0..rounds {
        for payload in &inputs {
            let output = gaius_png_wasm::job::run_png_decode(black_box(payload)).map_err(|error| error.message)?;
            black_box(output);
        }
    }
    let elapsed_ms = started.elapsed().as_secs_f64() * 1000.0;
    println!(
        "{{\"count\":{},\"bytes\":{input_bytes},\"outputBytes\":{output_bytes},\"rounds\":{rounds},\"totalMs\":{elapsed_ms:.6},\"msPerRound\":{:.6},\"parity\":true}}",
        inputs.len(), elapsed_ms / rounds as f64
    );
    Ok(())
}
