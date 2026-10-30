// Gaius kernel scheduling policies: the pure, allocation-light decisions behind the kernel
// runtime (kernel-runtime.js). Nothing here touches Workers, WebAssembly or the DOM, so the
// node smokes drive every policy directly with synthetic clocks.
//
// This file is a plain script (it is inlined into the single-file page and loaded before
// kernel-runtime.js). It installs globalThis.GaiusKernelPolicy:
//
//   PRIORITY             the five priority classes, lowest number runs first:
//                          P0 VISIBLE_MESH   sections the camera can see
//                          P1 VISIBLE_LIGHT  light for visible sections
//                          P2 NEAR_GEN       generation near the player, ahead of its motion
//                          P3 FAR_GEN        far generation, off-screen mesh and light
//                          P4 BACKGROUND     saves, prefetch, anything nobody waits for
//   MotionPredictor      player position/velocity/yaw -> predicted position and a heading; scores
//                        chunks by distance to where the player will be, discounted ahead of the
//                        motion and penalised behind it
//   classify / score     job descriptor -> class and a sortable score (class first, then the
//                        motion-aware distance, then age so nothing starves inside its class)
//   initialPlan          worker count, growth ceiling and memory budget from hardwareConcurrency,
//                        deviceMemory and the device class (phones and 4 GB Chromebooks)
//   PoolSizer            adaptive pool size: trial growth while high-priority work backs up,
//                        rollback (and a remembered ceiling) when the extra worker brought no
//                        throughput, shrink under heap or budget pressure, idle shrink
//   MemoryBudget         one global byte budget: wasm instance memories + payloads queued and in
//                        flight + expected results; per-class admission limits give backpressure
(function (global) {
  "use strict";

  const PRIORITY = Object.freeze({
    VISIBLE_MESH: 0,
    VISIBLE_LIGHT: 1,
    NEAR_GEN: 2,
    FAR_GEN: 3,
    BACKGROUND: 4,
  });
  const CLASS_COUNT = 5;
  // Score units per class. A sub-score is clamped below this, so classes never interleave
  // (except for starved jobs, see STARVATION_MS).
  const CLASS_SPAN = 1 << 24;
  // Share of the memory budget a class may fill before it is held back. P0 may use it all.
  const CLASS_BUDGET_SHARE = Object.freeze([1.0, 0.95, 0.85, 0.7, 0.5]);
  // Sub-score units: 1024 per chunk of (motion-weighted) distance.
  const CHUNK_UNIT = 1024;
  // Aging: a queued job gains one chunk of distance every AGING_MS_PER_CHUNK.
  const AGING_MS_PER_CHUNK = 160;
  // A P1 or P2 job queued longer than this competes one class up (with its aged sub-score), so
  // a flood of P0 mesh work cannot hold server light and near worldgen past their timeouts. The
  // runtime rescores queued jobs about once a second.
  const STARVATION_MS = 2000;

  const clamp = (value, low, high) => (value < low ? low : (value > high ? high : value));
  const finite = (value) => typeof value === "number" && value === value && value !== Infinity && value !== -Infinity;

  // --- motion prediction ----------------------------------------------------------------------

  // Minecraft yaw: 0 faces +Z (south), 90 faces -X (west).
  function yawToDirection(yawDegrees) {
    const radians = yawDegrees * Math.PI / 180;
    return [-Math.sin(radians), Math.cos(radians)];
  }

  class MotionPredictor {
    constructor(options) {
      const opts = options || {};
      this.horizonSec = finite(opts.horizonSec) ? opts.horizonSec : 2.0;
      this.maxLeadBlocks = finite(opts.maxLeadBlocks) ? opts.maxLeadBlocks : 96;
      this.smoothing = finite(opts.smoothing) ? opts.smoothing : 0.35;
      this.forwardBias = finite(opts.forwardBias) ? opts.forwardBias : 0.6;
      this.backPenalty = finite(opts.backPenalty) ? opts.backPenalty : 0.5;
      this.baseNearChunks = finite(opts.nearChunks) ? opts.nearChunks : 4;
      this.known = false;
      this.x = 0;
      this.y = 0;
      this.z = 0;
      this.vx = 0;
      this.vz = 0;
      this.yaw = NaN;
      this.lastT = NaN;
      this.viewChunks = 8;
      // Derived state, refreshed by update().
      this.px = 0;
      this.pz = 0;
      this.dirX = 0;
      this.dirZ = 0;
      this.speed = 0;
      this.heading = 0;    // 0 (no preference) .. 1 (fast, committed motion)
      this.epoch = 0;      // bumps when queued scores should be recomputed
      this.epochKey = "";
    }

    // sample: {x, y, z, vx?, vz?, yaw?, t?, viewDistance?}. Velocities are blocks per second;
    // without them the velocity is derived from consecutive positions.
    update(sample) {
      if (!sample || !finite(sample.x) || !finite(sample.z)) return false;
      const t = finite(sample.t) ? sample.t : Date.now();
      let vx = sample.vx;
      let vz = sample.vz;
      if (!finite(vx) || !finite(vz)) {
        vx = 0;
        vz = 0;
        if (this.known && finite(this.lastT)) {
          const dt = (t - this.lastT) / 1000;
          if (dt > 0.02 && dt < 5) {
            vx = (sample.x - this.x) / dt;
            vz = (sample.z - this.z) / dt;
            // A teleport (or a respawn) is not motion.
            if (vx * vx + vz * vz > 120 * 120) {
              vx = 0;
              vz = 0;
            }
          } else if (dt <= 0.02) {
            vx = this.vx;
            vz = this.vz;
          }
        }
      }
      if (this.known) {
        const a = this.smoothing;
        this.vx = this.vx + a * (vx - this.vx);
        this.vz = this.vz + a * (vz - this.vz);
      } else {
        this.vx = vx;
        this.vz = vz;
      }
      this.x = sample.x;
      this.y = finite(sample.y) ? sample.y : this.y;
      this.z = sample.z;
      if (finite(sample.yaw)) this.yaw = sample.yaw;
      if (finite(sample.viewDistance) && sample.viewDistance > 0) this.viewChunks = sample.viewDistance;
      this.lastT = t;
      this.known = true;
      this.derive();
      return true;
    }

    derive() {
      const speed = Math.sqrt(this.vx * this.vx + this.vz * this.vz);
      this.speed = speed;
      if (speed > 0.5) {
        this.dirX = this.vx / speed;
        this.dirZ = this.vz / speed;
        // Walking (4.3 b/s) gives a moderate heading, sprint-flying (>20 b/s) a full one.
        this.heading = clamp(speed / 20, 0.3, 1);
      } else if (finite(this.yaw)) {
        const dir = yawToDirection(this.yaw);
        this.dirX = dir[0];
        this.dirZ = dir[1];
        this.heading = 0.15;  // looking somewhere is a weak hint of where the player goes next
      } else {
        this.dirX = 0;
        this.dirZ = 0;
        this.heading = 0;
      }
      let lead = speed * this.horizonSec;
      if (lead > this.maxLeadBlocks) lead = this.maxLeadBlocks;
      this.px = this.x + this.dirX * (speed > 0.5 ? lead : 0);
      this.pz = this.z + this.dirZ * (speed > 0.5 ? lead : 0);
      // Recompute queued scores only when the predicted chunk or the heading sector changes.
      const sector = this.heading > 0 ? Math.round(Math.atan2(this.dirZ, this.dirX) / (Math.PI / 8)) : 99;
      const key = Math.floor(this.px / 16) + ":" + Math.floor(this.pz / 16) + ":" + sector + ":"
        + Math.round(this.heading * 4);
      if (key !== this.epochKey) {
        this.epochKey = key;
        this.epoch++;
      }
    }

    // Generation within this many chunks of the predicted position counts as near (P2).
    nearChunks() {
      return this.baseNearChunks + Math.ceil(Math.min(this.maxLeadBlocks, this.speed * this.horizonSec) / 16);
    }

    // Motion-weighted distance in chunks from the player to chunk (cx, cz). Chunks ahead of the
    // motion look closer than they are, chunks behind it further away.
    chunkDistance(cx, cz) {
      if (!this.known) return 0;
      const centerX = cx * 16 + 8;
      const centerZ = cz * 16 + 8;
      const dx = centerX - this.px;
      const dz = centerZ - this.pz;
      const distance = Math.sqrt(dx * dx + dz * dz) / 16;
      if (this.heading <= 0) return distance;
      const ox = centerX - this.x;
      const oz = centerZ - this.z;
      const length = Math.sqrt(ox * ox + oz * oz);
      if (length < 1e-6) return distance;
      const cosine = (ox * this.dirX + oz * this.dirZ) / length;
      const weight = cosine >= 0
        ? 1 - this.forwardBias * cosine * this.heading
        : 1 - this.backPenalty * cosine * this.heading;  // cosine < 0: the weight grows
      return distance * weight;
    }

    isNear(cx, cz) {
      if (!this.known) return true;
      const ox = (cx * 16 + 8 - this.x) / 16;
      const oz = (cz * 16 + 8 - this.z) / 16;
      if (ox * ox + oz * oz <= 2.5 * 2.5) return true;  // the player's own neighbourhood always is
      return this.chunkDistance(cx, cz) <= this.nearChunks();
    }

    snapshot() {
      return {
        known: this.known, x: this.x, y: this.y, z: this.z, vx: this.vx, vz: this.vz,
        speed: this.speed, heading: this.heading, predictedX: this.px, predictedZ: this.pz,
        nearChunks: this.nearChunks(), viewChunks: this.viewChunks, epoch: this.epoch,
      };
    }
  }

  // --- classification and scoring ---------------------------------------------------------------

  // Kernel names come from the crate names (gaius-mesher-wasm -> "mesher", gaius-light-wasm ->
  // "light"); anything else is generation work.
  function kernelRole(kernel) {
    const name = String(kernel || "");
    if (name.indexOf("mesh") === 0) return "mesh";
    if (name.indexOf("light") === 0) return "light";
    return "gen";
  }

  // job: {kernel, priorityClass?, visible?, background?, cx?, cz?}. Explicit classes win.
  function classify(job, predictor) {
    if (Number.isInteger(job.priorityClass)) return clamp(job.priorityClass, 0, CLASS_COUNT - 1);
    if (job.background === true) return PRIORITY.BACKGROUND;
    const role = kernelRole(job.kernel);
    if (role === "mesh") return job.visible === false ? PRIORITY.FAR_GEN : PRIORITY.VISIBLE_MESH;
    if (role === "light") return job.visible === false ? PRIORITY.FAR_GEN : PRIORITY.VISIBLE_LIGHT;
    if (finite(job.cx) && finite(job.cz) && predictor) {
      return predictor.isNear(job.cx, job.cz) ? PRIORITY.NEAR_GEN : PRIORITY.FAR_GEN;
    }
    return job.near === false ? PRIORITY.FAR_GEN : PRIORITY.NEAR_GEN;
  }

  // Lower runs first. Within a class: motion-weighted chunk distance when the job names a
  // chunk, otherwise its own distance hint, then its numeric priority; queue time lowers it.
  // job.cls itself never changes (pump accounting and budget shares stay per class).
  function score(job, predictor, now) {
    let cls = job.cls;
    // Only P1 and P2 are lifted: far and background jobs stay behind the runtime's worker caps,
    // and one of them at the head of the queue would hold back the near work behind it.
    if (cls > 0 && cls <= PRIORITY.NEAR_GEN && finite(job.submittedAt) && finite(now)
        && now - job.submittedAt > STARVATION_MS) {
      cls -= 1;
    }
    let sub;
    if (finite(job.cx) && finite(job.cz) && predictor && predictor.known) {
      sub = predictor.chunkDistance(job.cx, job.cz) * CHUNK_UNIT;
    } else if (finite(job.distance)) {
      sub = job.distance * CHUNK_UNIT;
    } else {
      sub = 0;
    }
    if (finite(job.priority)) sub += job.priority * CHUNK_UNIT;
    if (finite(job.submittedAt) && finite(now) && now > job.submittedAt) {
      sub -= ((now - job.submittedAt) / AGING_MS_PER_CHUNK) * CHUNK_UNIT;
    }
    return cls * CLASS_SPAN + clamp(sub, 0, CLASS_SPAN - 1);
  }

  // --- initial sizing and memory budget ---------------------------------------------------------

  const MB = 1024 * 1024;

  function isMobileAgent(nav) {
    if (!nav) return false;
    if (nav.userAgentData && typeof nav.userAgentData.mobile === "boolean") return nav.userAgentData.mobile;
    const ua = String(nav.userAgent || "");
    return /Android|iPhone|iPad|iPod|Mobile/i.test(ua) || (/Macintosh/.test(ua) && nav.maxTouchPoints > 1);
  }

  // env: {hardwareConcurrency, deviceMemory, mobile, reserve, maxSize, budgetBytes}.
  // Worker counts leave a core each for the page and the integrated server Worker.
  function initialPlan(env) {
    const e = env || {};
    const cores = finite(e.hardwareConcurrency) && e.hardwareConcurrency > 0 ? Math.floor(e.hardwareConcurrency) : 4;
    const memoryGb = finite(e.deviceMemory) && e.deviceMemory > 0 ? e.deviceMemory : 0;
    const mobile = e.mobile === true;
    const reserve = Math.max(0, Math.floor(e.reserve || 0));
    // deviceMemory is rounded down to a power of two and capped at 8 by browsers.
    let memoryCap;
    if (memoryGb === 0) memoryCap = mobile ? 2 : 4;
    else if (memoryGb <= 2) memoryCap = 1;
    else if (memoryGb <= 4) memoryCap = 2;
    else if (memoryGb < 8) memoryCap = 3;
    else memoryCap = 6;
    let initial = cores - 2 - reserve;
    if (mobile) initial = Math.min(initial, cores >= 8 ? 2 : 1);
    initial = clamp(initial, 1, memoryCap);
    let ceiling = clamp(cores - 1 - reserve, initial, memoryCap + (memoryGb >= 8 && !mobile ? 2 : 0));
    if (finite(e.maxSize) && e.maxSize >= 1) {
      ceiling = Math.min(ceiling, Math.floor(e.maxSize));
      initial = Math.min(initial, ceiling);
    }
    let budgetBytes;
    if (finite(e.budgetBytes) && e.budgetBytes > 0) budgetBytes = Math.floor(e.budgetBytes);
    else if (memoryGb === 0) budgetBytes = (mobile ? 128 : 256) * MB;
    else if (memoryGb <= 2) budgetBytes = 96 * MB;
    else if (memoryGb <= 4) budgetBytes = (mobile ? 128 : 160) * MB;
    else if (memoryGb < 8) budgetBytes = 256 * MB;
    else budgetBytes = (mobile ? 256 : 512) * MB;
    return {
      initial, ceiling, min: 1, budgetBytes, mobile, cores, deviceMemory: memoryGb || null,
      lowMemory: memoryGb > 0 && memoryGb <= 4,
    };
  }

  class MemoryBudget {
    constructor(limitBytes) {
      this.limit = Math.max(8 * MB, Math.floor(limitBytes || 256 * MB));
      this.instanceBytes = 0;
      this.queuedBytes = 0;
      this.inflightBytes = 0;
      this.inflightCount = 0;
      this.instances = new Map();   // "<worker>:<kernel>" -> bytes
      this.rejected = 0;
      this.held = 0;
    }

    setLimit(limitBytes) {
      this.limit = Math.max(8 * MB, Math.floor(limitBytes));
    }

    used() {
      return this.instanceBytes + this.queuedBytes + this.inflightBytes;
    }

    pressure() {
      return this.used() / this.limit;
    }

    // Memory the workers actually hold: live instances and the jobs running in them. Queued
    // payloads sit on the page and shrinking the pool frees none of them, so pool sizing and
    // idle unloading look at this rather than at pressure().
    resident() {
      return this.instanceBytes + this.inflightBytes;
    }

    residentPressure() {
      return this.resident() / this.limit;
    }

    classLimit(cls) {
      return this.limit * CLASS_BUDGET_SHARE[clamp(cls | 0, 0, CLASS_COUNT - 1)];
    }

    setInstance(key, bytes) {
      const previous = this.instances.get(key) || 0;
      const next = Math.max(0, bytes | 0) >>> 0;
      if (next === 0) this.instances.delete(key);
      else this.instances.set(key, next);
      this.instanceBytes += next - previous;
    }

    dropInstancesWithPrefix(prefix) {
      for (const [key, bytes] of this.instances) {
        if (key.indexOf(prefix) === 0) {
          this.instanceBytes -= bytes;
          this.instances.delete(key);
        }
      }
    }

    // Admission at submit: P0 and P1 always queue (they are what the player sees); lower
    // classes are refused once their share of the budget is used, and the caller falls back
    // to the vanilla Java path or retries later.
    canQueue(bytes, cls) {
      if (cls <= PRIORITY.VISIBLE_LIGHT) return true;
      if (this.used() + bytes <= this.classLimit(cls)) return true;
      this.rejected++;
      return false;
    }

    // Dispatch: payload + expected result must fit next to the live instances and the other
    // jobs in flight. With nothing in flight one job of any class may run: wasm memories never
    // shrink, so instances alone can exceed a class share, and the queue must still drain (the
    // runtime evicts idle instances under pressure).
    canDispatch(bytes, cls) {
      if (this.inflightCount === 0) return true;
      if (this.instanceBytes + this.inflightBytes + bytes <= this.classLimit(cls)) return true;
      this.held++;
      return false;
    }

    queue(bytes) {
      this.queuedBytes += bytes;
    }

    unqueue(bytes) {
      this.queuedBytes = Math.max(0, this.queuedBytes - bytes);
    }

    begin(bytes) {
      this.inflightBytes += bytes;
      this.inflightCount++;
    }

    end(bytes) {
      this.inflightBytes = Math.max(0, this.inflightBytes - bytes);
      this.inflightCount = Math.max(0, this.inflightCount - 1);
    }

    snapshot() {
      return {
        limit: this.limit, used: this.used(), pressure: this.pressure(), residentPressure: this.residentPressure(),
        instanceBytes: this.instanceBytes, queuedBytes: this.queuedBytes,
        inflightBytes: this.inflightBytes, inflightCount: this.inflightCount,
        rejected: this.rejected, held: this.held,
      };
    }
  }

  // --- adaptive pool sizing ---------------------------------------------------------------------

  // States: steady -> trial (one extra worker on probation) -> steady (kept) | cooldown (rolled
  // back, ceiling remembered); any state -> pressure (shrink) -> cooldown -> steady.
  class PoolSizer {
    constructor(plan, options) {
      const opts = options || {};
      this.min = Math.max(1, plan.min || 1);
      this.max = Math.max(this.min, plan.ceiling || plan.initial || 1);
      this.initial = clamp(plan.initial || 1, this.min, this.max);
      this.size = this.initial;
      this.state = "steady";
      this.trialWindowMs = opts.trialWindowMs || 4000;
      this.minGain = finite(opts.minGain) ? opts.minGain : 0.12;
      this.cooldownMs = opts.cooldownMs || 15000;
      this.ceilingTtlMs = opts.ceilingTtlMs || 120000;
      this.idleShrinkMs = opts.idleShrinkMs || 30000;
      this.heapPressureHigh = finite(opts.heapPressureHigh) ? opts.heapPressureHigh : 0.85;
      this.busyThreshold = finite(opts.busyThreshold) ? opts.busyThreshold : 0.8;
      this.backlogSamples = opts.backlogSamples || 2;
      this.ceiling = this.max;
      this.ceilingUntil = 0;
      this.stateSince = 0;
      this.backlogStreak = 0;
      this.idleSince = NaN;
      this.last = null;
      this.trialBaseline = 0;
      this.trialSamples = [];
      this.trialSize = 0;
      this.history = [];
    }

    effectiveCeiling(now) {
      if (this.ceilingUntil > 0 && now >= this.ceilingUntil) {
        this.ceiling = this.max;
        this.ceilingUntil = 0;
      }
      return this.ceiling;
    }

    record(now, action, reason) {
      this.history.push({at: now, action, reason, size: this.size, state: this.state});
      if (this.history.length > 32) this.history.shift();
      return {action, reason, size: this.size, state: this.state};
    }

    // s: {now, completed, busyMs, queuedHigh, queued, heapPressure, budgetPressure}
    // completed and busyMs are cumulative counters; queuedHigh counts queued P0-P2 jobs.
    sample(s) {
      const now = s.now;
      const prev = this.last;
      this.last = {now, completed: s.completed, busyMs: s.busyMs};
      if (!prev || now <= prev.now) return this.record(now, "none", "first-sample");
      const dt = now - prev.now;
      const throughput = ((s.completed - prev.completed) * 1000) / dt;
      const utilization = (s.busyMs - prev.busyMs) / (dt * Math.max(1, this.size));
      const heap = finite(s.heapPressure) ? s.heapPressure : 0;
      const budget = finite(s.budgetPressure) ? s.budgetPressure : 0;

      if (heap >= this.heapPressureHigh || budget >= 1) {
        const target = Math.max(this.min, this.size - 1);
        if (this.state !== "pressure") this.stateSince = now;
        this.state = "pressure";
        this.ceiling = target;
        this.ceilingUntil = now + this.ceilingTtlMs;
        if (target < this.size) {
          this.size = target;
          return this.record(now, "shrink", heap >= this.heapPressureHigh ? "heap-pressure" : "budget-pressure");
        }
        return this.record(now, "none", "pressure-at-minimum");
      }

      if (this.state === "pressure") {
        if (now - this.stateSince < this.cooldownMs) return this.record(now, "none", "pressure-cooldown");
        this.state = "steady";
        this.stateSince = now;
      }

      if (this.state === "trial") {
        if (now - this.stateSince < this.trialWindowMs) {
          this.trialSamples.push(throughput);
          return this.record(now, "none", "trial-running");
        }
        this.trialSamples.push(throughput);
        let total = 0;
        for (let i = 0; i < this.trialSamples.length; i++) total += this.trialSamples[i];
        const trialThroughput = total / this.trialSamples.length;
        const baseline = this.trialBaseline;
        // Only a measurable gain keeps the worker; a backlog that drained during the trial
        // (nothing left to measure) keeps it too, since the extra worker did the draining.
        const drained = s.queuedHigh === 0 && trialThroughput > 0;
        if (baseline > 0 && trialThroughput < baseline * (1 + this.minGain) && !drained) {
          this.size = Math.max(this.min, this.trialSize - 1);
          this.ceiling = this.size;
          this.ceilingUntil = now + this.ceilingTtlMs;
          this.state = "cooldown";
          this.stateSince = now;
          return this.record(now, "rollback", "no-throughput-gain");
        }
        this.state = "steady";
        this.stateSince = now;
        return this.record(now, "keep", "throughput-gain");
      }

      if (this.state === "cooldown") {
        if (now - this.stateSince < this.cooldownMs) return this.record(now, "none", "cooldown");
        this.state = "steady";
        this.stateSince = now;
      }

      // steady
      const backlog = s.queuedHigh > 0 && utilization >= this.busyThreshold;
      this.backlogStreak = backlog ? this.backlogStreak + 1 : 0;
      if (this.backlogStreak >= this.backlogSamples && this.size < this.effectiveCeiling(now)
          && budget < 0.85) {
        this.trialBaseline = throughput;
        this.trialSamples = [];
        this.size++;
        this.trialSize = this.size;
        this.state = "trial";
        this.stateSince = now;
        this.backlogStreak = 0;
        return this.record(now, "grow", "backlog");
      }
      if (s.queued === 0 && utilization < 0.15) {
        if (!finite(this.idleSince)) this.idleSince = now;
        if (now - this.idleSince >= this.idleShrinkMs && this.size > this.initial) {
          this.size--;
          this.idleSince = now;
          return this.record(now, "shrink", "idle");
        }
      } else {
        this.idleSince = NaN;
      }
      return this.record(now, "none", "steady");
    }

    snapshot() {
      return {
        size: this.size, state: this.state, min: this.min, max: this.max, initial: this.initial,
        ceiling: this.ceiling, history: this.history.slice(-8),
      };
    }
  }

  const api = Object.freeze({
    PRIORITY, CLASS_COUNT, CLASS_SPAN, CLASS_BUDGET_SHARE, CHUNK_UNIT, AGING_MS_PER_CHUNK, STARVATION_MS,
    MotionPredictor, yawToDirection, kernelRole, classify, score, initialPlan, isMobileAgent,
    MemoryBudget, PoolSizer,
  });
  global.GaiusKernelPolicy = api;
})(typeof globalThis !== "undefined" ? globalThis : self);
