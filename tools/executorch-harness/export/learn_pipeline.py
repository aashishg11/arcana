"""A guided, printable walk through the ExecuTorch pipeline.

Run it and read the output top to bottom: you see the model as a Python class,
then as a captured graph, then as a lowered graph with a delegate node, then as
bytes on disk, then as a running program.

    cd ~/arcana-ml && . .venv/bin/activate && python learn_pipeline.py
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
PTE = os.path.join(OUT, "tiny.pte")


def rule(title):
    print("\n" + "=" * 70)
    print(title)
    print("=" * 70)


# ---------------------------------------------------------------- the model
class Tiny(torch.nn.Module):
    """Small enough to read, big enough that XNNPACK wants the linear."""

    def __init__(self):
        super().__init__()
        self.fc = torch.nn.Linear(4, 3)

    def forward(self, x):
        return torch.relu(self.fc(x))


model = Tiny().eval()
sample = (torch.randn(1, 4),)

rule("STAGE 0 - eager PyTorch (needs a Python interpreter to run)")
print(model)
with torch.no_grad():
    eager_out = model(*sample)
print("eager output:", eager_out)

# ---------------------------------------------------------------- 1. capture
rule("STAGE 1 - torch.export  ->  ExportedProgram (ATen graph, no Python left)")
ep = export(model, sample)
print(ep.graph_module.code.strip())
print("\n-- the weights got captured too, as named state:")
for k, v in ep.state_dict.items():
    print(f"   {k:12s} {tuple(v.shape)}")

# ---------------------------------------------------------------- 2. lower
rule("STAGE 2 - to_edge_transform_and_lower  ->  XNNPACK claimed a subgraph")
edge = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()])
lowered_code = edge.exported_program().graph_module.code.strip()
print(lowered_code)

print("\n-- read that carefully:")
if "executorch_call_delegate" in lowered_code:
    print("   The addmm/relu ops are GONE. In their place: executorch_call_delegate.")
    print("   XNNPACK swallowed them into one precompiled blob, right here on the host.")
else:
    print("   No delegate node -- XNNPACK claimed nothing; everything stays on portable CPU kernels.")

# how much did the delegate actually take?
delegated = sum(
    1 for n in edge.exported_program().graph.nodes if "delegate" in str(n.target)
)
total = len(list(edge.exported_program().graph.nodes))
print(f"\n   nodes in lowered graph: {total}   delegate calls: {delegated}")
print("   (Anything NOT delegated silently falls back to portable CPU kernels.")
print("    A model can be 5% delegated and still 'work' -- just slowly. Always check.)")

# ---------------------------------------------------------------- 3. serialize
rule("STAGE 3 - .to_executorch()  ->  a .pte file on disk")
prog = edge.to_executorch()
with open(PTE, "wb") as f:
    f.write(prog.buffer)
size = os.path.getsize(PTE)
print(f"wrote {PTE}  ({size} bytes)")

with open(PTE, "rb") as f:
    head = f.read(16)
print("first 16 bytes:", " ".join(f"{b:02x}" for b in head))
print("       as ascii:", "".join(chr(b) if 32 <= b < 127 else "." for b in head))
print("\n   Bytes 4-7 are the flatbuffer file identifier. This is a self-contained,")
print("   mmap-able container: graph + weights + the XNNPACK blob. No libtorch needed.")

# ---------------------------------------------------------------- 4. run
rule("STAGE 4 - the runtime executes the .pte (no Python model, no libtorch)")
from executorch.runtime import Runtime

rt = Runtime.get()
method = rt.load_program(PTE).load_method("forward")
et_out = method.execute(list(sample))[0]
print("executorch output:", et_out)
print("eager output     :", eager_out)

close = torch.allclose(et_out, eager_out, atol=1e-5)
print(f"\nmatches eager PyTorch within 1e-5: {close}")
print("\nThat equality is the whole point: same math, no Python, portable to a phone.")
