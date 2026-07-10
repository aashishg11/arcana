#!/usr/bin/env bash
# Day 5 discriminator for the Day-4 anomaly.
#
# Day 3 (SmolLM2): int4 weights decoded FASTER than int8 -> "decode is bandwidth-bound".
# Day 4 (Gemma):   2.5x smaller file, decode UNCHANGED -> contradicts that.
#
# 8da8w vs 8da4w on Gemma isolates weight BYTES while holding activation width (int8)
# and the whole graph constant. If decode tracks weight bytes -> bandwidth-bound.
# If decode is flat -> something else dominates per-token cost at 1B on this SoC.
set -uo pipefail
cd ~/arcana-ml && . .venv/bin/activate

MODEL="google/gemma-3-1b-it"
BASE=~/arcana-ml/out
name="gemma3_1b_8da8w_emb4w"
outdir="$BASE/$name"

if [ -f "$outdir/model.pte" ]; then
  echo ">>> $name exists, skip"
else
  echo ">>> exporting $name (qlinear=8da8w qembedding=4w)"
  start=$SECONDS
  if PYTHONWARNINGS=ignore optimum-cli export executorch \
      --model "$MODEL" --task text-generation --recipe xnnpack \
      --output_dir "$outdir" --max_seq_len 512 --device cpu \
      --use_custom_sdpa --use_custom_kv_cache \
      --qlinear 8da8w --qembedding 4w > "$BASE/$name.log" 2>&1; then
    sz=$(stat -c %s "$outdir/model.pte")
    printf "    OK in %ss -> %.1f MB\n" "$((SECONDS-start))" "$(echo "$sz/1048576" | bc -l)"
    cp -n ~/arcana-ml/tokenizers/gemma3/tokenizer.json "$outdir/" 2>/dev/null
  else
    echo "    FAILED"; tail -20 "$BASE/$name.log"
  fi
fi

echo
echo "=== all gemma variants ==="
for v in gemma3_1b_8da4w gemma3_1b_8da4w_emb4w gemma3_1b_8da8w_emb4w; do
  f="$BASE/$v/model.pte"
  [ -f "$f" ] && printf "  %-24s %8.1f MB\n" "$v" "$(echo "$(stat -c %s "$f")/1048576" | bc -l)"
done
