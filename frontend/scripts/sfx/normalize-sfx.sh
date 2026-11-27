#!/usr/bin/env bash
# Turns downloaded sound effects into short, equally loud clips for click playback.
#
#   scripts/sfx/normalize-sfx.sh <input-dir> <output-dir>
#   e.g. scripts/sfx/normalize-sfx.sh soundfiles/sfx/bubblepop src/assets/sfx/bubblepop
#
# For each input audio file it writes <output-dir>/<same basename>.mp3:
#   1. mono, 44.1 kHz;
#   2. leading silence below SILENCE_DB removed, so the sound starts when the tap lands;
#   3. cut to MAX_SECONDS with a fade-out over the last FADE_SECONDS, so rapid taps do not pile up;
#   4. gain set so the RMS level is TARGET_MEAN_DB, but never pushing the peak above PEAK_CEILING_DB;
#   5. MP3 at BITRATE.
# The raw downloads stay out of git (frontend/.gitignore); only the output is committed.
set -euo pipefail

SILENCE_DB="${SILENCE_DB:--45}"
MAX_SECONDS="${MAX_SECONDS:-0.6}"
FADE_SECONDS="${FADE_SECONDS:-0.15}"
TARGET_MEAN_DB="${TARGET_MEAN_DB:--24}"
PEAK_CEILING_DB="${PEAK_CEILING_DB:--1}"
BITRATE="${BITRATE:-96k}"

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <input-dir> <output-dir>" >&2
  exit 2
fi
in_dir="$1"
out_dir="$2"
[[ -d "$in_dir" ]] || { echo "input directory not found: $in_dir" >&2; exit 1; }
mkdir -p "$out_dir"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# Prints "<mean_volume> <max_volume>" in dB for a file.
measure() {
  ffmpeg -hide_banner -nostats -i "$1" -af volumedetect -f null - 2>&1 \
    | awk '/mean_volume/ {mean=$5} /max_volume/ {max=$5} END {print mean, max}'
}

shopt -s nullglob
inputs=("$in_dir"/*.{mp3,wav,ogg,flac,m4a})
[[ ${#inputs[@]} -gt 0 ]] || { echo "no audio files in $in_dir" >&2; exit 1; }

printf '%-44s %8s %8s %8s\n' file seconds mean_dB peak_dB
for src in "${inputs[@]}"; do
  name="$(basename "${src%.*}")"
  trimmed="$tmp_dir/$name.wav"

  ffmpeg -hide_banner -loglevel error -y -i "$src" \
    -af "aformat=channel_layouts=mono,aresample=44100,silenceremove=start_periods=1:start_threshold=${SILENCE_DB}dB:detection=peak,atrim=0:${MAX_SECONDS}" \
    "$trimmed"

  seconds="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$trimmed")"
  fade_start="$(awk -v d="$seconds" -v f="$FADE_SECONDS" 'BEGIN {s = d - f; if (s < 0) s = 0; printf "%.4f", s}')"
  faded="$tmp_dir/$name.faded.wav"
  ffmpeg -hide_banner -loglevel error -y -i "$trimmed" -af "afade=t=out:st=${fade_start}:d=${FADE_SECONDS}" "$faded"

  read -r mean peak < <(measure "$faded")
  gain="$(awk -v m="$mean" -v p="$peak" -v t="$TARGET_MEAN_DB" -v c="$PEAK_CEILING_DB" \
    'BEGIN {g = t - m; if (p + g > c) g = c - p; printf "%.2f", g}')"

  out="$out_dir/$name.mp3"
  ffmpeg -hide_banner -loglevel error -y -i "$faded" -af "volume=${gain}dB" \
    -c:a libmp3lame -b:a "$BITRATE" -ar 44100 -ac 1 "$out"

  read -r out_mean out_peak < <(measure "$out")
  out_seconds="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out")"
  printf '%-44s %8.3f %8s %8s\n' "$name" "$out_seconds" "$out_mean" "$out_peak"
done
