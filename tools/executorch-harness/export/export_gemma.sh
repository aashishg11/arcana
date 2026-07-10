#!/usr/bin/env bash
# Day 4, rung 3: Gemma 3 1B (gated) -> ExecuTorch, INT4.
# Day-3 lesson baked in: NEVER `4w` (weight-only is folded back to fp32 on xnnpack).
# Gemma 3 ties its embedding to the lm_head and the vocab is 262k, so --qembedding
# carries far more weight here than it did for SmolLM2.
set -uo pipefail
cd ~/arcana-ml && . .venv/bin/activate

MODEL="google/gemma-3-1b-it"
SEQ=512
BASE=~/arcana-ml/out

VARIANTS=(
  "gemma3_1b_8da4w_emb4w  8da4w  4w"   # primary: the real target
  "gemma3_1b_8da4w        8da4w  -"    # isolates the embedding contribution
)

peak_mem_watch() {   # $1 = logfile to append to
  local peak=0
  while :; do
    local used
    used=$(free -m | awk '/^Mem:/{print $3}')
    (( used > peak )) && peak=$used
    echo "$peak" > /tmp/peak_mem_mb
    sleep 5
  done
}

for row in "${VARIANTS[@]}"; do
  read -r name qlin qemb <<< "$row"
  outdir="$BASE/$name"
  [ -f "$outdir/model.pte" ] && { echo ">>> $name exists, skip"; continue; }

  args=(--model "$MODEL" --task text-generation --recipe xnnpack
        --output_dir "$outdir" --max_seq_len "$SEQ" --device cpu
        --use_custom_sdpa --use_custom_kv_cache --qlinear "$qlin")
  [ "$qemb" != "-" ] && args+=(--qembedding "$qemb")

  echo ">>> exporting $name (qlinear=$qlin qembedding=$qemb)"
  echo 0 > /tmp/peak_mem_mb
  peak_mem_watch & WATCH=$!

  start=$SECONDS
  if PYTHONWARNINGS=ignore optimum-cli export executorch "${args[@]}" > "$BASE/$name.log" 2>&1; then
    kill $WATCH 2>/dev/null
    sz=$(stat -c %s "$outdir/model.pte")
    printf "    OK in %ss -> %.1f MB   peak RSS-ish %s MB\n" \
      "$((SECONDS-start))" "$(echo "$sz/1048576" | bc -l)" "$(cat /tmp/peak_mem_mb)"
  else
    kill $WATCH 2>/dev/null
    echo "    FAILED after $((SECONDS-start))s   peak mem $(cat /tmp/peak_mem_mb) MB"
    echo "    --- last 25 lines ---"
    tail -25 "$BASE/$name.log"
  fi
done

echo
echo "=== tokenizer ==="
DEST=~/arcana-ml/tokenizers/gemma3
mkdir -p "$DEST"
[ -f "$DEST/tokenizer.json" ] || PYTHONWARNINGS=ignore hf download "$MODEL" tokenizer.json --local-dir "$DEST" >/dev/null 2>&1
ls -l "$DEST/tokenizer.json" 2>/dev/null || echo "TOKENIZER DOWNLOAD FAILED"
for d in "$BASE"/gemma3_1b_*/; do
  [ -f "$d/model.pte" ] && cp -n "$DEST/tokenizer.json" "$d/" 2>/dev/null
done

echo
echo "=== results ==="
for v in gemma3_1b_8da4w_emb4w gemma3_1b_8da4w; do
  f="$BASE/$v/model.pte"
  [ -f "$f" ] && printf "  %-24s %7.1f MB   %s\n" "$v" "$(echo "$(stat -c %s "$f")/1048576" | bc -l)" "$(ls "$BASE/$v" | tr '\n' ' ')"
done
