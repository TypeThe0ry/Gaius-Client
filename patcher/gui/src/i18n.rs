use std::collections::BTreeMap;
use std::sync::OnceLock;

use serde_json::Value;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Language {
    Chinese,
    English,
}

impl Language {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "zh" | "zh-CN" => Some(Self::Chinese),
            "en" => Some(Self::English),
            _ => None,
        }
    }

    pub fn code(self) -> &'static str {
        match self {
            Self::Chinese => "zh",
            Self::English => "en",
        }
    }

    pub fn text(self, key: &str) -> &str {
        match self {
            Self::Chinese => zh_resources().get(key),
            Self::English => en_resources().get(key),
        }
        .and_then(Value::as_str)
        .unwrap_or(key)
    }
}

fn parse_resource(source: &str) -> BTreeMap<String, Value> {
    serde_json::from_str(source).expect("i18n resource JSON must be valid")
}

fn zh_resources() -> &'static BTreeMap<String, Value> {
    static RESOURCES: OnceLock<BTreeMap<String, Value>> = OnceLock::new();
    RESOURCES.get_or_init(|| parse_resource(include_str!("i18n/zh-CN.json")))
}

fn en_resources() -> &'static BTreeMap<String, Value> {
    static RESOURCES: OnceLock<BTreeMap<String, Value>> = OnceLock::new();
    RESOURCES.get_or_init(|| parse_resource(include_str!("i18n/en.json")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locale_key_sets_match() {
        let zh_keys: Vec<_> = zh_resources().keys().collect();
        let en_keys: Vec<_> = en_resources().keys().collect();
        assert_eq!(zh_keys, en_keys);
        for key in [
            "status.check_failed",
            "status.hash_error",
            "status.build_failed",
            "status.cancelled",
        ] {
            assert_ne!(Language::Chinese.text(key), key);
            assert_ne!(Language::English.text(key), key);
        }
    }

    #[test]
    fn language_switch_uses_locale_resources() {
        assert_eq!(Language::Chinese.text("step.detect"), "检测");
        assert_eq!(Language::English.text("step.detect"), "Check");
    }
}
