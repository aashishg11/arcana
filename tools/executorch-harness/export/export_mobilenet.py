"""Day 2, rung 1: MobileNetV2 -> XNNPACK -> .pte, validated against eager.

Produces:
  out/mobilenet_v2_xnnpack.pte   (delegated to XNNPACK CPU kernels)
  out/mobilenet_v2_portable.pte  (no delegate -- the control, for comparison)
  out/mobilenet_input.bin        (the exact 1x3x224x224 input, for the device)
  out/mobilenet_expected.bin     (eager logits, so the phone's answer is checkable)

The point of rung 1 is to prove the *whole loop* works with no tokenizer and no
LLM runner: does the toolchain produce a .pte that computes the right thing.
"""
import os
import warnings

warnings.filterwarnings("ignore")

import torch
from torch.export import export
from torchvision.models import mobilenet_v2, MobileNet_V2_Weights
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.runtime import Runtime

OUT = os.path.expanduser("~/arcana-ml/out")
os.makedirs(OUT, exist_ok=True)

# Deterministic input so the phone and the host see the exact same tensor.
torch.manual_seed(0)
sample = (torch.randn(1, 3, 224, 224),)

print("=== loading MobileNetV2 (pretrained) ===")
weights = MobileNet_V2_Weights.DEFAULT
model = mobilenet_v2(weights=weights).eval()
n_params = sum(p.numel() for p in model.parameters())
print(f"  parameters: {n_params:,}  ({n_params * 4 / 1e6:.1f} MB as fp32)")

with torch.no_grad():
    eager = model(*sample)
print(f"  eager logits shape: {tuple(eager.shape)}")
print(f"  eager top-1 class index: {eager.argmax(-1).item()}  ({weights.meta['categories'][eager.argmax(-1).item()]})")


def build(name, partitioners):
    ep = export(model, sample)
    edge = to_edge_transform_and_lower(ep, partitioner=partitioners)
    prog = edge.to_executorch()

    nodes = list(edge.exported_program().graph.nodes)
    delegates = [n for n in nodes if "delegate" in str(n.target)]
    compute = [
        n for n in nodes
        if n.op == "call_function"
        and "delegate" not in str(n.target)
        and "getitem" not in str(n.target)
    ]

    path = os.path.join(OUT, name)
    with open(path, "wb") as f:
        f.write(prog.buffer)
    size = os.path.getsize(path)

    # correctness on the host runtime
    out = Runtime.get().load_program(path).load_method("forward").execute(list(sample))[0]
    max_err = (out - eager).abs().max().item()
    same_top1 = out.argmax(-1).item() == eager.argmax(-1).item()

    print(f"\n=== {name} ===")
    print(f"  size            : {size/1e6:.2f} MB")
    print(f"  delegate calls  : {len(delegates)}")
    print(f"  ops left on CPU : {len(compute)}")
    print(f"  max abs err     : {max_err:.3e}")
    print(f"  same top-1      : {same_top1}")
    return path, size, len(delegates), len(compute)


build("mobilenet_v2_portable.pte", [])
build("mobilenet_v2_xnnpack.pte", [XnnpackPartitioner()])

# Save input + expected output as raw float32 so the Android side can read them
# and verify bit-for-bit rather than "looks plausible".
sample[0].numpy().astype("float32").tofile(os.path.join(OUT, "mobilenet_input.bin"))
eager.numpy().astype("float32").tofile(os.path.join(OUT, "mobilenet_expected.bin"))
print(f"\nwrote mobilenet_input.bin (1x3x224x224 f32) and mobilenet_expected.bin (1x1000 f32)")
print(f"expected top-1 index = {eager.argmax(-1).item()}")
