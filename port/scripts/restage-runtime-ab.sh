#!/usr/bin/env bash
# Restage one JS-only diagnostic arm, preserving the accepted train's generated data.
# Usage: restage-runtime-ab.sh BASE_TRAIN RUNTIME_REF OUTPUT_ROOT LABEL [PACKAGING_REF]
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
WT=$(git rev-parse --show-toplevel)
TRAIN=$(realpath "${1:?base train directory}")
RUNTIME_REF=${2:?runtime git ref}
OUT=$(realpath -m "${3:?output directory}")
LABEL=${4:?arm label, e.g. A, B2, V0}
PACKAGING_REF=${5:-HEAD}
PROFILE=${PROFILE:-26.3}
[[ "$LABEL" =~ ^[A-Za-z0-9_-]+$ ]] || { echo 'Invalid arm label' >&2; exit 2; }
RUNTIME_COMMIT=$(git rev-parse "$RUNTIME_REF^{commit}")
PACKAGING_COMMIT=$(git rev-parse "$PACKAGING_REF^{commit}")
mkdir -p "$OUT"
WORK=$(mktemp -d "$OUT/.restage-$LABEL.XXXXXX")
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/runtime" "$WORK/packaging" "$WORK/train/dist"
# Only tracked sources (about 43 MB), no build outputs, worktree, hard links or compilation.
git archive "$RUNTIME_COMMIT" | tar -x -C "$WORK/runtime"
git archive "$PACKAGING_COMMIT" | tar -x -C "$WORK/packaging"
# The site builder reads loader/boot sources directly from its checkout. Keep
# packaging code fixed while supplying the selected runtime sources there too.
cp -a "$WORK/runtime/port/web/." "$WORK/packaging/port/web/"
cp "$TRAIN/READY.txt" "$WORK/train/READY.txt"
[[ ! -f "$TRAIN/SOURCE.json" ]] || cp "$TRAIN/SOURCE.json" "$WORK/train/SOURCE.json"
for role in client server; do
  cp -a --reflink=never "$TRAIN/dist/wasm-$PROFILE-$role" "$WORK/train/dist/"
done
# --js-role missing never reads js-internal: omit its multi-GB build/cache tree.
python3 - "$TRAIN" "$WORK" "$PROFILE" <<'PY'
import hashlib,json,shutil,subprocess,sys
from pathlib import Path
base,work,profile=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
source=work/'runtime'; web=source/'port/web'
# Explicit destinations for verbatim source runtime scripts. Everything else is frozen
# generated train data, notably kernel-mirror.js's injected vanilla/shader enable table.
mapping={'gaius-boot.js':web/'boot/gaius-boot.js','gaius-sw.js':web/'sw/gaius-sw.js'}
for p in (web/'runtime/wasmgc').glob('*.js'): mapping[p.name]=p
for prefix in ('kernels','runtime/quality','runtime/webgpu'):
 for p in (web/prefix).glob('*.js'): mapping[f'{prefix}/{p.name}']=p
mapping['runtime/webgpu/shaderxlat-job.js']=source/'port/native/golden/shaders/shaderxlat-job.js'
# This is generated, even though a source template exists at the same logical path.
mapping.pop('kernels/kernel-mirror.js',None)
records={}
for role in ('client','server'):
 original=base/f'dist/wasm-{profile}-{role}'; target=work/f'train/dist/wasm-{profile}-{role}'
 subprocess.run([sys.executable,str(source/'port/scripts/stage-web-runtime.py'),str(target),'--profile',profile],check=True)
 restored=[];changed=[]
 for p in sorted(target.rglob('*')):
  if not p.is_file(): continue
  name=p.relative_to(target).as_posix(); old=original/name
  if old.is_file() and old.read_bytes()==p.read_bytes(): continue
  src=mapping.get(name)
  if src is not None and src.is_file() and p.read_bytes()==src.read_bytes():
   changed.append(name)
  else:
   restored.append(name)
   if old.is_file(): shutil.copyfile(old,p)
   else: p.unlink()
 records[role]={'intendedRuntimeChanges':changed,'restoredGeneratedFiles':restored}
(work/'runtime-changes.json').write_text(json.dumps(records,indent=2)+'\n')
PY
python3 "$WORK/packaging/port/scripts/stage-train.py" "$WORK/train" \
  --profile "$PROFILE" --output "$OUT/staged-$LABEL" --scratch "$WORK/scratch" --js-role missing
cp "$OUT/staged-$LABEL/portable-$PROFILE/Gaius-$PROFILE.html" "$OUT/Gaius-$PROFILE-$LABEL.html"
python3 - "$OUT" "$WORK" "$LABEL" "$PROFILE" "$TRAIN" "$RUNTIME_COMMIT" "$PACKAGING_COMMIT" <<'PY'
import hashlib,json,sys
from pathlib import Path
out,work,label,profile,train,runtime,packaging=sys.argv[1:]
out,work=Path(out),Path(work)
p=out/f'Gaius-{profile}-{label}.html'
record={'label':label,'baseTrain':train,'runtimeCommit':runtime,'packagingCommit':packaging,
        'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,
        'runtimeChanges':json.loads((work/'runtime-changes.json').read_text()),
        'compiled':False,'jsRole':'missing'}
(out/f'restage-{label}.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record,indent=2))
PY
