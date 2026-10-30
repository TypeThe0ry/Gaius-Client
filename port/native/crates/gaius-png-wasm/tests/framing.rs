use gaius_kernel_abi::{kind, Status};
use gaius_png_wasm::job::run_png_decode;

#[test]
fn declines_bad_payload_without_panicking() {
    for length in 0..4 {
        assert_eq!(run_png_decode(&vec![0; length]).unwrap_err().status, Status::Truncated);
    }
    assert_eq!(run_png_decode(&[4, 0, 0, 0]).unwrap_err().status, Status::BadPayload);
    assert_eq!(run_png_decode(&[5, 0, 0, 0]).unwrap_err().status, Status::BadPayload);
    assert_eq!(kind::PNG_DECODE, 0x0701);
}
