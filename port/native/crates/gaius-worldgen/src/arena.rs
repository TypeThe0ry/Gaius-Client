//! Scratch buffer pool (`DensityBufferPool`): evaluation takes and returns
//! buffers instead of allocating, so a warmed-up job allocates nothing.
//! Buffers keep stale contents; every sampler writes its whole output before
//! reading it, exactly like vanilla's pooled `ScopedDensityBuffer`s.

#[derive(Default)]
pub struct Arena<T: Copy + Default> {
    free: Vec<Vec<T>>,
    held: usize,
}

impl<T: Copy + Default> Arena<T> {
    pub fn new() -> Self {
        Arena {
            free: Vec::new(),
            held: 0,
        }
    }

    /// A buffer of at least `len` elements (use `&mut buf[..len]`).
    pub fn take(&mut self, len: usize) -> Vec<T> {
        let mut best: Option<usize> = None;
        for (i, b) in self.free.iter().enumerate() {
            if b.len() >= len && best.is_none_or(|j| b.len() < self.free[j].len()) {
                best = Some(i);
                if b.len() == len {
                    break;
                }
            }
        }
        match best {
            Some(i) => {
                let b = self.free.swap_remove(i);
                self.held -= b.len();
                b
            }
            None => vec![T::default(); len.max(16)],
        }
    }

    pub fn give(&mut self, buffer: Vec<T>) {
        self.held += buffer.len();
        self.free.push(buffer);
    }

    /// Elements currently pooled.
    pub fn held(&self) -> usize {
        self.held
    }

    /// Drops pooled buffers, largest first, until at most `max_elements` remain.
    pub fn trim(&mut self, max_elements: usize) {
        if self.held <= max_elements {
            return;
        }
        self.free.sort_by_key(|b| core::cmp::Reverse(b.len()));
        while self.held > max_elements {
            match self.free.first() {
                Some(_) => {
                    let b = self.free.remove(0);
                    self.held -= b.len();
                }
                None => break,
            }
        }
    }
}
