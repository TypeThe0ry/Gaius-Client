//! Decoding and encoding of the shared golden fixture format: doubles (and
//! widened floats) are 16-char lowercase hex of the raw bits, longs are
//! decimal strings, ints are JSON numbers.

use gaius_noise::{RandomKind, RandomSource};
use serde_json::{Map, Value};

pub type Outcome = Result<Vec<Value>, String>;

/// One fixture line.
pub struct Case {
    pub kind: String,
    pub params: Map<String, Value>,
    pub inputs: Vec<Vec<Value>>,
    pub outputs: Vec<Value>,
}

impl Case {
    pub fn parse(line: &str) -> Result<Case, String> {
        let value: Value = serde_json::from_str(line).map_err(|e| format!("bad JSON: {e}"))?;
        let field = |name: &str| value.get(name).ok_or_else(|| format!("missing \"{name}\""));
        let kind = field("kind")?.as_str().ok_or("\"kind\" is not a string")?.to_string();
        let params = field("params")?
            .as_object()
            .ok_or("\"params\" is not an object")?
            .clone();
        let inputs = field("inputs")?
            .as_array()
            .ok_or("\"inputs\" is not an array")?
            .iter()
            .map(|row| {
                row.as_array()
                    .cloned()
                    .ok_or_else(|| "input row is not an array".to_string())
            })
            .collect::<Result<_, _>>()?;
        let outputs = field("outputs")?
            .as_array()
            .ok_or("\"outputs\" is not an array")?
            .clone();
        Ok(Case {
            kind,
            params,
            inputs,
            outputs,
        })
    }

    pub fn param(&self, name: &str) -> Result<&Value, String> {
        self.params.get(name).ok_or_else(|| format!("missing param \"{name}\""))
    }

    pub fn str(&self, name: &str) -> Result<&str, String> {
        self.param(name)?
            .as_str()
            .ok_or_else(|| format!("param \"{name}\" is not a string"))
    }

    pub fn f64(&self, name: &str) -> Result<f64, String> {
        f64_of(self.param(name)?).map_err(|e| format!("param \"{name}\": {e}"))
    }

    pub fn i64(&self, name: &str) -> Result<i64, String> {
        i64_of(self.param(name)?).map_err(|e| format!("param \"{name}\": {e}"))
    }

    pub fn i32(&self, name: &str) -> Result<i32, String> {
        i32_of(self.param(name)?).map_err(|e| format!("param \"{name}\": {e}"))
    }

    pub fn bool(&self, name: &str) -> Result<bool, String> {
        self.param(name)?
            .as_bool()
            .ok_or_else(|| format!("param \"{name}\" is not a boolean"))
    }

    pub fn f64_list(&self, name: &str) -> Result<Vec<f64>, String> {
        list(self.param(name)?, f64_of).map_err(|e| format!("param \"{name}\": {e}"))
    }

    pub fn i32_list(&self, name: &str) -> Result<Vec<i32>, String> {
        list(self.param(name)?, i32_of).map_err(|e| format!("param \"{name}\": {e}"))
    }

    /// The random source of `Rngs.forked`: `random` + `seed`, optionally
    /// re-seeded through `forkPositional().fromHashOf(fork)`.
    pub fn random(&self) -> Result<RandomSource, String> {
        let kind = match self.str("random")? {
            "xoroshiro" => RandomKind::Xoroshiro,
            "legacy" => RandomKind::Legacy,
            other => return Err(format!("unknown random \"{other}\"")),
        };
        let mut random = RandomSource::new(kind, self.i64("seed")?);
        if let Some(fork) = self.params.get("fork") {
            let name = fork.as_str().ok_or("param \"fork\" is not a string")?;
            random = random.fork_positional().from_hash_of(name);
        }
        Ok(random)
    }

    /// Input rows decoded as doubles, each of exactly `arity` values.
    pub fn f64_rows(&self, arity: usize) -> Result<Vec<Vec<f64>>, String> {
        self.inputs
            .iter()
            .map(|row| {
                if row.len() != arity {
                    return Err(format!("expected {arity} inputs per row, got {}", row.len()));
                }
                row.iter().map(f64_of).collect()
            })
            .collect()
    }

    /// Input rows decoded as ints, each of exactly `arity` values.
    pub fn i32_rows(&self, arity: usize) -> Result<Vec<Vec<i32>>, String> {
        self.inputs
            .iter()
            .map(|row| {
                if row.len() != arity {
                    return Err(format!("expected {arity} inputs per row, got {}", row.len()));
                }
                row.iter().map(i32_of).collect()
            })
            .collect()
    }
}

fn list<T>(value: &Value, item: fn(&Value) -> Result<T, String>) -> Result<Vec<T>, String> {
    value
        .as_array()
        .ok_or_else(|| "not an array".to_string())?
        .iter()
        .map(item)
        .collect()
}

pub fn f64_of(value: &Value) -> Result<f64, String> {
    let text = value.as_str().ok_or_else(|| format!("{value} is not a hex double"))?;
    if text.len() != 16 {
        return Err(format!("\"{text}\" is not 16 hex digits"));
    }
    u64::from_str_radix(text, 16)
        .map(f64::from_bits)
        .map_err(|e| format!("\"{text}\": {e}"))
}

/// A float that the fixture widened to double; narrowing it back is exact.
pub fn f32_of(value: &Value) -> Result<f32, String> {
    let wide = f64_of(value)?;
    let narrow = wide as f32;
    if (narrow as f64).to_bits() != wide.to_bits() && !wide.is_nan() {
        return Err(format!("{wide} is not a widened float"));
    }
    Ok(narrow)
}

pub fn i64_of(value: &Value) -> Result<i64, String> {
    match value {
        Value::String(text) => text.parse().map_err(|e| format!("\"{text}\": {e}")),
        Value::Number(n) => n.as_i64().ok_or_else(|| format!("{n} is not an integer")),
        other => Err(format!("{other} is not a long")),
    }
}

pub fn i32_of(value: &Value) -> Result<i32, String> {
    let n = value.as_i64().ok_or_else(|| format!("{value} is not an int"))?;
    i32::try_from(n).map_err(|_| format!("{n} does not fit an int"))
}

pub fn enc_f64(value: f64) -> Value {
    Value::String(format!("{:016x}", value.to_bits()))
}

pub fn enc_f32(value: f32) -> Value {
    enc_f64(value as f64)
}

pub fn enc_i64(value: i64) -> Value {
    Value::String(value.to_string())
}

pub fn enc_i32(value: i32) -> Value {
    Value::from(value)
}

/// Fails unless the recorded double param equals `actual` bit for bit.
pub fn expect_param_bits(case: &Case, name: &str, actual: f64) -> Result<(), String> {
    let expected = case.f64(name)?;
    if expected.to_bits() == actual.to_bits() {
        Ok(())
    } else {
        Err(format!(
            "param \"{name}\": expected {expected:e} ({:016x}), computed {actual:e} ({:016x})",
            expected.to_bits(),
            actual.to_bits()
        ))
    }
}
