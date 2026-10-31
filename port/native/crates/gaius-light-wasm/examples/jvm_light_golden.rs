use gaius_kernel_abi::{JobHeader, ResultHeader, RESULT_HEADER_LEN};
use gaius_light_wasm::job::{Kernel, LIGHT_COLUMN};
use std::path::PathBuf;

fn main() {
    let mut arguments = std::env::args().skip(1);
    let fixtures = PathBuf::from(arguments.next().expect("job directory"));
    let output = PathBuf::from(arguments.next().expect("output directory"));
    std::fs::create_dir_all(&output).expect("create output");
    let mut jobs: Vec<_> = std::fs::read_dir(&fixtures)
        .expect("list jobs")
        .map(|entry| entry.expect("job entry").path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "job"))
        .collect();
    jobs.sort();
    assert!(!jobs.is_empty(), "no light jobs");
    let mut kernel = Kernel::new();
    for job in &jobs {
        let bytes = std::fs::read(job).expect("read job");
        let (header, payload) = JobHeader::parse(&bytes).expect("job header");
        assert_eq!(header.kind, LIGHT_COLUMN);
        let payload = kernel.run(payload).expect("light job");
        let mut result = vec![0; RESULT_HEADER_LEN + payload.len()];
        ResultHeader {
            status: 0,
            job_id: header.job_id,
            payload_len: payload.len() as u32,
        }
        .write(&mut result);
        result[RESULT_HEADER_LEN..].copy_from_slice(&payload);
        std::fs::write(
            output.join(job.file_name().expect("job name")).with_extension("native"),
            result,
        )
        .expect("write result");
    }
    println!("{} native light column results", jobs.len());
}
