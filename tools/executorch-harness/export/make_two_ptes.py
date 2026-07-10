"""Build the SAME model two ways so the delegate is visible side by side.

  out/tiny_portable.pte  - no partitioner: every aten op survives, runs on
                           portable CPU reference kernels.
  out/tiny_xnnpack.pte   - XnnpackPartitioner: the ops are swallowed into one
                           precompiled delegate blob.

Open both in Netron and compare. Same math, same output, very different graph.
"""
import os
import warnings

warnings.filterwarnings("ignore")

import torch
from torch.export import export
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

OUT = os.path.expanduser("~/arcana-ml/out")
os.makedirs(OUT, exist_ok=True)


class Tiny(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.fc1 = torch.nn.Linear(8, 16)
        self.fc2 = torch.nn.Linear(16, 4)

    def forward(self, x):
        return self.fc2(torch.relu(self.fc1(x)))


model = Tiny().eval()
sample = (torch.randn(1, 8),)
with torch.no_grad():
    eager = model(*sample)

variants = {
    "tiny_portable.pte": [],                     # nothing delegated
    "tiny_xnnpack.pte": [XnnpackPartitioner()],  # CPU backend claims what it can
}

for fname, partitioners in variants.items():
    ep = export(model, sample)
    edge = to_edge_transform_and_lower(ep, partitioner=partitioners)
    gm = edge.exported_program().graph_module

    def opname(node):
        t = str(node.target)
        # aten targets look like 'aten.addmm.default'; getitem looks like
        # '<built-in function getitem>' and has no dots to split on.
        parts = t.split(".")
        return parts[-2] if len(parts) >= 2 else t

    nodes = list(edge.exported_program().graph.nodes)
    delegates = [n for n in nodes if "delegate" in str(n.target)]
    aten_ops = [
        n
        for n in nodes
        if n.op == "call_function"
        and "delegate" not in str(n.target)
        and "getitem" not in str(n.target)
    ]

    path = os.path.join(OUT, fname)
    prog = edge.to_executorch()
    with open(path, "wb") as f:
        f.write(prog.buffer)

    # correctness: both must still match eager
    from executorch.runtime import Runtime
    out = Runtime.get().load_program(path).load_method("forward").execute(list(sample))[0]
    ok = torch.allclose(out, eager, atol=1e-5)

    print(f"\n=== {fname} ({os.path.getsize(path)} bytes) ===")
    print(f"  delegate calls : {len(delegates)}")
    print(f"  aten ops left  : {len(aten_ops)}  {[opname(n) for n in aten_ops][:6]}")
    print(f"  matches eager  : {ok}")

print("\nBoth produce identical results. Only one is accelerated.")
print("That is the whole 'silent fallback' point, made visual.")
