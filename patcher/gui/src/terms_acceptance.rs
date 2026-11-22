use std::fs::{self, File};
use std::io::{self, Read};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

const EARLIEST_ACCEPTANCE: u64 = 1_577_836_800; // 2020-01-01 UTC

#[derive(Deserialize, Serialize)]
struct Acceptance {
    terms_sha256: String,
    accepted_at_unix: u64,
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_secs())
}

pub fn load(path: &Path, hash: &str, now: u64) -> Option<u64> {
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take(4097)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() > 4096 {
        return None;
    }
    let record: Acceptance = serde_json::from_slice(&bytes).ok()?;
    (record.terms_sha256 == hash && (EARLIEST_ACCEPTANCE..=now).contains(&record.accepted_at_unix))
        .then_some(record.accepted_at_unix)
}

pub fn save(path: &Path, hash: &str, timestamp: u64) -> io::Result<()> {
    if timestamp < EARLIEST_ACCEPTANCE {
        return Err(io::Error::other("invalid system clock"));
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let bytes = serde_json::to_vec_pretty(&Acceptance {
        terms_sha256: hash.to_owned(),
        accepted_at_unix: timestamp,
    })?;
    fs::write(path, bytes)
}

pub fn revoke(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}

pub fn display_time(timestamp: u64) -> String {
    i64::try_from(timestamp)
        .ok()
        .and_then(chrono::DateTime::from_timestamp_secs)
        .map(|time| time.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    const ACCEPTED: u64 = 1_795_000_000;
    const HASH: &str = "current-hash";

    fn fixture(test: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("terms-{test}-{}.json", std::process::id()))
    }

    #[test]
    fn matching_hash_and_time_restore_consent() {
        let path = fixture("match");
        save(&path, HASH, ACCEPTED).unwrap();
        assert_eq!(load(&path, HASH, ACCEPTED + 1), Some(ACCEPTED));
        assert_eq!(display_time(ACCEPTED), "2026-11-18 11:06 UTC");
        revoke(&path).unwrap();
    }

    #[test]
    fn changed_hash_requires_consent() {
        let path = fixture("mismatch");
        save(&path, HASH, ACCEPTED).unwrap();
        assert_eq!(load(&path, "updated-hash", ACCEPTED), None);
        revoke(&path).unwrap();
    }

    #[test]
    fn corrupt_or_implausible_records_are_ignored() {
        let path = fixture("invalid");
        for input in [
            "{invalid",
            "{}",
            r#"{"terms_sha256":"current-hash","accepted_at_unix":1}"#,
        ] {
            fs::write(&path, input).unwrap();
            assert_eq!(load(&path, HASH, ACCEPTED), None);
        }
        save(&path, HASH, ACCEPTED).unwrap();
        assert_eq!(load(&path, HASH, ACCEPTED - 1), None);
        revoke(&path).unwrap();
    }

    #[test]
    fn missing_record_requires_consent() {
        let path = fixture("missing");
        assert_eq!(load(&path, HASH, ACCEPTED), None);
    }

    #[test]
    fn revoke_removes_persistent_consent() {
        let path = fixture("revoke");
        save(&path, HASH, ACCEPTED).unwrap();
        revoke(&path).unwrap();
        assert!(!path.exists());
        assert_eq!(load(&path, HASH, ACCEPTED), None);
        revoke(&path).unwrap();
    }
}
