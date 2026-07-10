#!/usr/bin/env bash
# Hypothesis: weight-only (4w/8w) is a no-op under the xnnpack recipe because the
# delegate has no weight-only linear kernel, so the dq is constant-folded back to
# fp32. Dynamic-activation configs (8da8w / 8da4w) SHOULD actually shrink the .pte.
set -uo pipefail
cd ~/arcana-ml && . .venv/bin/activate

MODEL="HuggingFaceTB/SmolLM2-135M-Instruct"
SEQ=512
BASE=~/arcana-ml/out

VARIANTS=(
  "smollm2_8da8w        8da8w  -"
  "smollm2_8da4w        8da4w  -"
  "smollm2_8da4w_emb4w  8da4w  4w"
)

for row in "${VARIANTS[@]}"; do
  read -r name qlin qemb <<< "$row"
  outdir="$BASE/$name"
  [ -f "$outdir/model.pte" ] && { echo ">>> $name exists, skip"; continue; }

  args=(--model "$MODEL" --task text-generation --recipe xnnpack
        --output_dir "$outdir" --max_seq_len "$SEQ" --device cpu
        --use_custom_sdpa --use_custom_kv_cache --qlinear "$qlin")
  [ "$qemb" != "-" ] && args+=(--qembedding "$qemb")

  echo ">>> exporting $name (qlinear=$qlin qembedding=$qemb)"
  start=$SECONDS
  if PYTHONWARNINGS=ignore optimum-cli export executorch "${args[@]}" > "$BASE/$name.log" 2>&1; then
    sz=$(stat -c %s "$outdir/model.pte")
    printf "    OK in %ss -> %.1f MB\n" "$((SECONDS-start))" "$(echo "$sz/1048576" | bc -l)"
  else
    echo "    FAILED in $((SECONDS-start))s"; tail -15 "$BASE/$name.log"
  fi
done

echo
echo "=============== ALL VARIANTS ==============="
printf "%-22s %10s\n" "variant" "model.pte"
for v in smollm2_fp32 smollm2_8w smollm2_4w smollm2_4w_emb4w smollm2_8da8w smollm2_8da4w smollm2_8da4w_emb4w; do
  f="$BASE/$v/model.pte"
  if [ -f "$f" ]; then
    printf "%-22s %7.1f MB\n" "$v" "$(echo "$(stat -c %s "$f")/1048576" | bc -l)"
  fi
done
