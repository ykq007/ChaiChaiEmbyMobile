#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MEDIA="$ROOT/media/movies"
mkdir -p "$MEDIA/Direct Stream Fixture (2026)" "$MEDIA/Transcode Fixture (2026)"
DIRECT="$MEDIA/Direct Stream Fixture (2026)/Direct Stream Fixture (2026).mp4"
TRANSCODE="$MEDIA/Transcode Fixture (2026)/Transcode Fixture (2026).mkv"
if [ ! -s "$DIRECT" ]; then
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i testsrc2=size=1280x720:rate=24:duration=12 \
    -f lavfi -i sine=frequency=440:sample_rate=48000:duration=12 \
    -f lavfi -i sine=frequency=660:sample_rate=48000:duration=12 \
    -map 0:v -map 1:a -map 2:a -c:v libx264 -preset veryfast -pix_fmt yuv420p \
    -c:a aac -b:a 128k -metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn \
    -movflags +faststart "$DIRECT"
fi
if [ ! -s "$TRANSCODE" ]; then
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i smptebars=size=720x576:rate=25:duration=12 \
    -f lavfi -i sine=frequency=330:sample_rate=48000:duration=12 \
    -map 0:v -map 1:a -c:v mpeg2video -q:v 3 -c:a mp2 -b:a 192k "$TRANSCODE"
fi
cat > "$MEDIA/Direct Stream Fixture (2026)/Direct Stream Fixture (2026).en.srt" <<'SRT'
1
00:00:01,000 --> 00:00:03,000
Synthetic English subtitle

2
00:00:05,000 --> 00:00:07,000
Second deterministic cue
SRT
cat > "$MEDIA/Direct Stream Fixture (2026)/Direct Stream Fixture (2026).zh.vtt" <<'VTT'
WEBVTT

00:00:01.000 --> 00:00:03.000
Synthetic VTT subtitle
VTT
cat > "$MEDIA/Direct Stream Fixture (2026)/Direct Stream Fixture (2026).nfo" <<'NFO'
<movie><title>Direct Stream Fixture</title><year>2026</year><plot>Synthetic H.264 AAC media with two audio tracks and sidecar subtitles.</plot><uniqueid type="tmdb" default="true">990001</uniqueid></movie>
NFO
cat > "$MEDIA/Transcode Fixture (2026)/Transcode Fixture (2026).nfo" <<'NFO'
<movie><title>Transcode Fixture</title><year>2026</year><plot>Synthetic MPEG-2 and MP2 media intended to exercise negotiated transcoding.</plot><uniqueid type="tmdb" default="true">990002</uniqueid></movie>
NFO
find "$ROOT/media" -type f -exec chmod 644 {} \;
printf '%s\n' "synthetic media ready"
