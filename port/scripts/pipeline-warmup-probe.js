// Page probe of pipeline-warmup-parity.mjs: a signature (hash of the shader texts and attribute bindings) of every linked WebGL program.
(() => {
  // Program signatures: hash of the shader texts handed to the driver, per linked program, in link order.
  const S = window.__sig = {programs: [], marks: []};
  const gp = WebGL2RenderingContext.prototype;
  const text = new WeakMap(), type = new WeakMap(), att = new WeakMap(), bind = new WeakMap();
  const fnv = (str) => { let h = 0x811c9dc5; for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; } return h.toString(16).padStart(8, '0'); };
  const w = (name, cb) => { const o = gp[name]; gp[name] = function (...a) { const r = o.apply(this, a); try { cb.call(this, a, r); } catch (e) {} return r; }; };
  w('createShader', (a, r) => { type.set(r, a[0]); });
  w('shaderSource', (a) => { text.set(a[0], a[1]); });
  w('attachShader', (a) => { const l = att.get(a[0]) || []; l.push(a[1]); att.set(a[0], l); });
  w('bindAttribLocation', (a) => { const l = bind.get(a[0]) || []; l.push(a[1] + ':' + a[2]); bind.set(a[0], l); });
  w('linkProgram', (a) => {
    const shaders = (att.get(a[0]) || []).map((s) => type.get(s) + '=' + fnv(text.get(s) || '') + '/' + (text.get(s) || '').length).sort();
    S.programs.push({sig: shaders.join('|') + '#' + fnv((bind.get(a[0]) || []).join(',')), t: performance.now()});
  });
  S.mark = (label) => { S.marks.push({label, at: S.programs.length, t: performance.now()}); return S.programs.length; };
  S.since = (n) => S.programs.slice(n).map((p) => p.sig);
})();
