//! Little-endian cursor with error positions, used by the IR decoder.

use super::IrError;

pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
    /// Section name for error messages.
    pub what: &'static str,
}

macro_rules! read_le {
    ($name:ident, $ty:ty) => {
        #[inline]
        pub fn $name(&mut self) -> Result<$ty, IrError> {
            let bytes = self.take(core::mem::size_of::<$ty>())?;
            let mut raw = [0u8; core::mem::size_of::<$ty>()];
            raw.copy_from_slice(bytes);
            Ok(<$ty>::from_le_bytes(raw))
        }
    };
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8], what: &'static str) -> Self {
        Reader { buf, pos: 0, what }
    }

    pub fn take(&mut self, len: usize) -> Result<&'a [u8], IrError> {
        let end = self
            .pos
            .checked_add(len)
            .filter(|&end| end <= self.buf.len())
            .ok_or_else(|| IrError::new(format!("{}: truncated at byte {}", self.what, self.pos)))?;
        let bytes = &self.buf[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    read_le!(u8, u8);
    read_le!(u16, u16);
    read_le!(u32, u32);
    read_le!(i32, i32);
    read_le!(i64, i64);
    read_le!(f32, f32);
    read_le!(f64, f64);

    pub fn bool(&mut self) -> Result<bool, IrError> {
        Ok(self.u8()? != 0)
    }

    /// `u16` byte length followed by UTF-8.
    pub fn string(&mut self) -> Result<String, IrError> {
        let len = self.u16()? as usize;
        let bytes = self.take(len)?;
        core::str::from_utf8(bytes)
            .map(String::from)
            .map_err(|_| self.error("string is not UTF-8"))
    }

    /// A count that must fit in the bytes left, given the minimum size of one element.
    pub fn count(&mut self, min_element: usize) -> Result<usize, IrError> {
        let count = self.u32()? as usize;
        if count.saturating_mul(min_element.max(1)) > self.remaining() {
            return Err(self.error("count exceeds the section"));
        }
        Ok(count)
    }

    pub fn f64s(&mut self, n: usize) -> Result<Vec<f64>, IrError> {
        (0..n).map(|_| self.f64()).collect()
    }

    pub fn f32s(&mut self, n: usize) -> Result<Vec<f32>, IrError> {
        (0..n).map(|_| self.f32()).collect()
    }

    pub fn u32s(&mut self, n: usize) -> Result<Vec<u32>, IrError> {
        (0..n).map(|_| self.u32()).collect()
    }

    pub fn error(&self, message: &str) -> IrError {
        IrError::new(format!("{}: {} (byte {})", self.what, message, self.pos))
    }
}
