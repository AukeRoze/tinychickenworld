# Video Assembly Service — Design

Standalone Spring Boot service. Input: a finished `Script` plus generated image and voice-over assets. Output: a 1920x1080 H.264 MP4 ready for upload.

FFmpeg is invoked via `ProcessBuilder`. We do not use a Java FFmpeg binding because (a) we want pinned binary control, and (b) the failure mode of a bad filter graph is easier to debug from the raw command line.

## Inputs

```
AssemblyRequest {
  jobId, scriptId
  scenes: [{ seq, imagePath, audioPath, durationSeconds, narration }]
  backgroundMusicPath?            // optional
  burnSubtitles: boolean          // default false (kids audience often subtitle-off)
  output: { width: 1920, height: 1080, fps: 30, bitrateKbps: 6000 }
}
```

All paths are local files (downloaded from blob storage into a per-job work directory).

## Pipeline — step by step

### Step 1. Prepare work directory
```
workdir/<jobId>/
  in/scene_01.png, scene_01.wav, ...
  tmp/scene_01.mp4, ...
  out/final.mp4, out/subs.srt
```
Created once. Removed on success.

### Step 2. Build per-scene MP4 clips (parallel)
For each scene we produce a self-contained MP4 of exactly `durationSeconds`. Image is scaled/padded to 1920x1080 and zoomed slowly (Ken Burns). Audio is the narration WAV padded to clip duration.

```
ffmpeg -y \
  -loop 1 -t {duration} -i scene_01.png \
  -i scene_01.wav \
  -filter_complex "
    [0:v]scale=1920:1080:force_original_aspect_ratio=decrease,
         pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black,
         zoompan=z='min(zoom+0.0015,1.15)':d={duration*fps}:s=1920x1080:fps={fps},
         format=yuv420p[v];
    [1:a]apad,atrim=duration={duration}[a]
  " \
  -map "[v]" -map "[a]" \
  -c:v libx264 -preset veryfast -crf 20 -r {fps} \
  -c:a aac -b:a 192k -ar 48000 \
  -shortest tmp/scene_01.mp4
```
Parallelism is bounded by an executor (CPU-1 threads). Each invocation streams stdout/stderr to a log file.

### Step 3. Concatenate clips
Build a concat list and use the demuxer (no re-encode — fast, lossless):
```
tmp/concat.txt:
  file 'tmp/scene_01.mp4'
  file 'tmp/scene_02.mp4'
  ...

ffmpeg -y -f concat -safe 0 -i tmp/concat.txt -c copy tmp/joined.mp4
```
If clip params don't match exactly, fall back to a re-encoding concat (filter_complex `concat=n=N:v=1:a=1`). Step 2 makes the demuxer path the default.

### Step 4. Mix background music (optional)
Music ducks under narration via `sidechaincompress`:
```
ffmpeg -y -i tmp/joined.mp4 -i bgm.mp3 \
  -filter_complex "
    [1:a]aloop=loop=-1:size=2e9,volume=0.25[bgm];
    [0:a][bgm]sidechaincompress=threshold=0.04:ratio=8:attack=20:release=250[mixed]
  " \
  -map 0:v -map "[mixed]" \
  -c:v copy -c:a aac -b:a 192k -shortest tmp/withmusic.mp4
```

### Step 5. Burn subtitles (optional)
Generate SRT from scene narration + durations (cumulative timestamps). Burn with the `subtitles` filter:
```
ffmpeg -y -i tmp/withmusic.mp4 \
  -vf "subtitles=out/subs.srt:force_style='Fontname=Arial,Fontsize=42,PrimaryColour=&HFFFFFF&,OutlineColour=&H000000&,Outline=3,BorderStyle=1'" \
  -c:v libx264 -preset veryfast -crf 20 -c:a copy tmp/withsubs.mp4
```
If burning subtitles we lose the `-c:v copy` fast-path; that's fine — it runs once.

### Step 6. Final encode
Single re-encode pass to lock target params for YouTube:
```
ffmpeg -y -i tmp/withsubs.mp4 \
  -c:v libx264 -preset medium -crf 19 -pix_fmt yuv420p \
  -movflags +faststart -profile:v high -level 4.1 \
  -c:a aac -b:a 192k -ar 48000 \
  out/final.mp4
```
`+faststart` moves the moov atom to the head — YouTube starts processing earlier.

### Step 7. Probe + return
`ffprobe -v error -show_format -show_streams -of json out/final.mp4` → record duration, bitrate, video/audio codecs, file size on the JobResult. Upload `out/final.mp4` to blob storage, return its URL.

## Failure handling
- Every `ffmpeg` invocation has a hard timeout (e.g., 10× expected duration).
- Non-zero exit → capture last 200 lines of stderr → throw `FfmpegException` with the command line.
- Idempotency: if `tmp/scene_NN.mp4` exists and probe matches duration, skip Step 2 for that scene. Lets us resume after a crash.

## Concurrency model
- One job at a time per VM by default (FFmpeg saturates CPU). Tune via `app.ffmpeg.maxConcurrentJobs`.
- Within a job, scene clips (Step 2) run in parallel up to `Runtime.availableProcessors()-1`.

## Why this shape
- Each step is a single ffmpeg invocation — cheap to log, easy to reproduce manually.
- Step 2 + Step 3 (demuxer concat) is the fast path. Steps 4–5 add re-encodes but only when those features are enabled.
- The final pass guarantees consistent output regardless of upstream choices.
