use gaius_kernel_abi::{kind, JobHeader, ResultHeader, Status};
use gaius_worldgen_wasm::ore_job;
use std::fs::File;
use std::io::{BufReader, Read};

#[test]
fn matches_independent_jvm_ordered_ore_frames() {
    let Some(path) = std::env::var_os("GAIUS_ORE_GOLDEN") else {
        eprintln!("Set GAIUS_ORE_GOLDEN to a JVM ore fixture; no external parity ran");
        return;
    };
    let mut input = BufReader::new(File::open(path).unwrap());
    let mut phases = [0usize; 2];
    loop {
        let mut header = [0u8; 8];
        if input.read(&mut header[..1]).unwrap() == 0 {
            break;
        }
        input.read_exact(&mut header[1..]).unwrap();
        let input_length = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
        let output_length = u32::from_le_bytes(header[4..].try_into().unwrap()) as usize;
        assert!((16..=16 * 1024 * 1024).contains(&input_length));
        assert!((20..=16 * 1024 * 1024).contains(&output_length));
        let mut job = vec![0; input_length];
        let mut expected = vec![0; output_length];
        input.read_exact(&mut job).unwrap();
        input.read_exact(&mut expected).unwrap();
        let (request, payload) = JobHeader::parse(&job).unwrap();
        assert_eq!(request.kind, kind::ORE);
        let response = ResultHeader::parse(&expected).unwrap();
        assert_eq!(response.status, Status::Ok as u16);
        assert_eq!(response.job_id, request.job_id);
        assert_eq!(response.payload_len as usize, expected.len() - 16);
        let phase = u32::from_le_bytes(payload[..4].try_into().unwrap()) as usize;
        assert!(phase < phases.len());
        assert_eq!(ore_job::run(payload).unwrap(), expected[16..]);
        phases[phase] += 1;
    }
    assert!(phases[0] >= 300);
    assert_eq!(phases[0], phases[1]);
    println!("{} independent JVM ore geometry and delta cases exact", phases[0]);
}
