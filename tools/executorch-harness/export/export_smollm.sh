#!/usr/bin/env bash
# Day 3, rung 2: SmolLM2-135M-Instruct -> ExecuTorch, at four precisions.
# Ungated model, so nothing here depends on the Gemma license.
set -uo pipefail
cd ~/arcana-ml && . .venv/bin/activate

MODEL="HuggingFaceTB/SmolLM2-135M-Instruct"
SEQ=512
BASE=~/arcana-ml/out

# name              qlinear      qembedding
VARIANTS=(
  "smollm2_fp32      -            -"
  "smollm2_8w        8w           -"
  "smollm2_4w        4w           -"
  "smollm2_4w_emb4w  4w           4w"
)

echo "=== model: $MODEL   max_seq_len=$SEQ   device=cpu (no CUDA) ==="

for row in "${VARIANTS[@]}"; do
  read -r name qlin qemb <<< "$row"
  outdir="$BASE/$name"

  if [ -f "$outdir/model.pte" ]; then
    echo ">>> $name already exported, skipping"
    continue
  fi

  args=(--model "$MODEL" --task text-generation --recipe xnnpack
        --output_dir "$outdir" --max_seq_len "$SEQ" --device cpu
        --use_custom_sdpa --use_custom_kv_cache)
  [ "$qlin" != "-" ] && args+=(--qlinear "$qlin")
  [ "$qemb" != "-" ] && args+=(--qembedding "$qemb")

  echo
  echo ">>> exporting $name   (qlinear=$qlin qembedding=$qemb)"
  start=$SECONDS
  if PYTHONWARNINGS=ignore optimum-cli export executorch "${args[@]}" > "$BASE/$name.log" 2>&1; then
    echo "    OK in $((SECONDS-start))s"
  else
    echo "    FAILED in $((SECONDS-start))s -- tail of $BASE/$name.log:"
    tail -20 "$BASE/$name.log"
  fi
done

echo
echo "=============== RESULTS ==============="
printf "%-20s %12s  %s\n" "variant" "model.pte" "files"
for row in "${VARIANTS[@]}"; do
  read -r name _ _ <<< "$row"
  pte="$BASE/$name/model.pte"
  if [ -f "$pte" ]; then
    sz=$(stat -c %s "$pte")
    printf "%-20s %9.1f MB  %s\n" "$name" "$(echo "$sz/1048576" | bc -l)" "$(ls $BASE/$name | tr '\n' ' ')"
  else
    printf "%-20s %12s  -\n" "$name" "MISSING"
  fi
done
