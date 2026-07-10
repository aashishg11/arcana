"""Open each .pte and report what dtypes its constant tensors actually are.

If --qlinear worked, the decoder linear weights should NOT be float32.
"""
import os
import sys
import warnings

warnings.filterwarnings("ignore")

from collections import Counter

BASE = os.path.expanduser("~/arcana-ml/out")
VARIANTS = ["smollm2_fp32", "smollm2_8w", "smollm2_4w", "smollm2_4w_emb4w"]

try:
    from executorch.exir._serialize._program import deserialize_pte_binary
except Exception as e:
    print("no deserialize_pte_binary:", e)
    sys.exit(1)

from executorch.exir.schema import Tensor, ScalarType

for v in VARIANTS:
    path = os.path.join(BASE, v, "model.pte")
    if not os.path.exists(path):
        continue
    with open(path, "rb") as f:
        pte = deserialize_pte_binary(f.read())

    # PTEFile wraps the Program; find it rather than guess the attribute name.
    prog = getattr(pte, "program", pte)
    if not hasattr(prog, "execution_plan"):
        print(f"{v}: PTEFile attrs = {[a for a in dir(pte) if not a.startswith('_')]}")
        continue

    plan = prog.execution_plan[0]

    dtype_counts = Counter()
    dtype_elems = Counter()
    for val in plan.values:
        t = val.val
        if isinstance(t, Tensor):
            n = 1
            for d in t.sizes:
                n *= d
            name = ScalarType(t.scalar_type).name
            dtype_counts[name] += 1
            dtype_elems[name] += n

    delegates = len(plan.delegates)
    kernels = len(plan.operators)

    print(f"\n=== {v}  ({os.path.getsize(path)/1e6:.1f} MB) ===")
    print(f"  delegate blobs   : {delegates}")
    print(f"  distinct kernels : {kernels}")
    print("  tensors by dtype :")
    for name, cnt in dtype_counts.most_common():
        print(f"     {name:10s} count={cnt:5d}  elements={dtype_elems[name]:,}")
