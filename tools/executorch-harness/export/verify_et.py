"""Day-1 smoke test: prove the ExecuTorch pipeline runs end to end on CPU.

Walks the four stages the Week-5 plan asks to be able to articulate:
  torch.export -> to_edge_transform_and_lower -> to_executorch -> runtime
"""
from importlib.metadata import version

import torch
from torch.export import export
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

print("executorch:", version("executorch"))
print("torch     :", torch.__version__)
print("cuda      :", torch.cuda.is_available())


class Add(torch.nn.Module):
    def forward(self, x, y):
        return x + y


sample = (torch.ones(2, 2), torch.ones(2, 2))

# 1. capture the graph
ep = export(Add(), sample)
print("\n[1] torch.export -> ExportedProgram")
print("    graph nodes:", len(list(ep.graph.nodes)))

# 2. lower: partition subgraphs to the XNNPACK (CPU) delegate
edge = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()])
print("[2] to_edge_transform_and_lower -> EdgeProgramManager")

# 3. serialize to a .pte
prog = edge.to_executorch()
pte = prog.buffer
print("[3] to_executorch -> .pte buffer:", len(pte), "bytes")

# 4. run it through the Python runtime bindings
from executorch.runtime import Runtime

rt = Runtime.get()
with open("/tmp/add.pte", "wb") as f:
    f.write(pte)
method = rt.load_program("/tmp/add.pte").load_method("forward")
out = method.execute(list(sample))
print("[4] runtime execute -> ", out[0].tolist())

expected = torch.full((2, 2), 2.0)
assert torch.allclose(out[0], expected), f"WRONG: {out[0]}"
print("\nOK: 1 + 1 == 2 through a real .pte")
