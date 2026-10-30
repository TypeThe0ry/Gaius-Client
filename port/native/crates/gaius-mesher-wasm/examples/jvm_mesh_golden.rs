use gaius_mesher::Kernel;
use std::path::PathBuf;

fn main() {
    let mut arguments = std::env::args().skip(1);
    let fixtures = PathBuf::from(arguments.next().expect("fixture directory"));
    let output = PathBuf::from(arguments.next().expect("output directory"));
    std::fs::create_dir_all(&output).expect("create output");
    let mut kernel = Kernel::new();
    kernel
        .load_model_table(&std::fs::read(fixtures.join("table.bin")).expect("read table"))
        .expect("load table");
    let mut jobs: Vec<_> = std::fs::read_dir(&fixtures)
        .expect("list fixtures")
        .map(|entry| entry.expect("fixture entry").path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "job"))
        .collect();
    jobs.sort();
    assert!(jobs.len() >= 300, "at least 300 JVM sections required");
    for job in &jobs {
        let result = kernel
            .mesh_section(&std::fs::read(job).expect("read job"))
            .expect("mesh job");
        std::fs::write(
            output.join(job.file_name().expect("job name")).with_extension("native"),
            result,
        )
        .expect("write result");
    }
    println!("{} native section results", jobs.len());
}
