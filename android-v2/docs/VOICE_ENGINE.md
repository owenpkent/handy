# Voice engine

Every parameter in the audio pipeline, what it does, why this value, how to retune it. Audience: anyone changing the matcher, debugging poor matches, or porting to a different feature extractor.

## Pipeline

```
ShortArray (1.5 s × 16 kHz = 24 000 samples)
    │
    ▼  Mfcc.preemphasize
DoubleArray of the same length, with y[i] = x[i] − 0.97 · x[i−1]
    │
    ▼  framing: 25 ms windows, 10 ms hop
≈ 98 overlapping frames of 400 samples each
    │
    ▼  Hamming window (precomputed)
    ▼  zero-pad to 512
    │
    ▼  radix-2 in-place FFT
512 complex bins (we keep the first 257; the spectrum is symmetric)
    │
    ▼  magnitude² / N
power spectrum
    │
    ▼  26 triangular mel filters
26 mel-band energies
    │
    ▼  natural log (with floor at 1e−12)
log-mel energies
    │
    ▼  DCT-II (precomputed 13×26 matrix)
13 cepstral coefficients per frame
    │
    ▼
Array<DoubleArray>  ─── shape [≈ 98, 13]
```

Every step is in `voice/Mfcc.kt`. The whole extractor for a 1.5 s utterance runs in ~3 ms on a Pixel-class CPU.

## MFCC parameters

Defined as constructor defaults in `Mfcc.kt`. Change them in one place and the rest of the pipeline follows.

| Parameter | Default | What it controls | Why this value |
| --- | --- | --- | --- |
| `sampleRate` | 16 000 | PCM sample rate | Matches `vega/audio_io.py` and `AudioCapture.SAMPLE_RATE`. Voice band is comfortably ≤ 8 kHz, so 16 kHz with anti-alias is ample. |
| `frameSizeMs` | 25 | Window over which features are computed | Standard for speech. Short enough that one phoneme spans many frames, long enough to capture pitch periods of low voices. |
| `frameHopMs` | 10 | How far the window advances per frame | Standard for speech. Gives ~10:1 overlap with the 25 ms window. |
| `numMelFilters` | 26 | Triangular filters in the mel filterbank | Classic Davis & Mermelstein figure. Plenty of resolution for speaker-dependent matching. |
| `numCoeffs` | 13 | Cepstral coefficients kept after DCT | Including the energy coefficient (c₀). Standard for ASR features. Higher coeffs capture finer formant structure and are mostly noise for small-vocabulary matching. |
| `melLowHz` | 20 | Lowest filter centre | Below 20 Hz is rumble, irrelevant for speech. |
| `melHighHz` | 8 000 | Highest filter centre | Nyquist at 16 kHz sample rate. Captures sibilance and fricatives. |
| `preemphasis` | 0.97 | First-order high-pass coefficient | Boosts the spectral tilt: speech has a natural −6 dB/octave roll-off; pre-emphasis flattens it so high frequencies aren't drowned out. 0.97 is the standard value. |

Derived values:

- `frameSize = 400` samples (25 ms × 16 kHz).
- `frameHop = 160` samples (10 ms × 16 kHz).
- `fftSize = 512` (next power of two ≥ 400; the rest is zero-padded).

## FFT details

`Mfcc.fftInPlace` is an iterative radix-2 Cooley–Tukey on two `DoubleArray`s (real, imag). Two passes:

1. **Bit-reversal permutation** to put the input in butterfly order.
2. **Stage-by-stage butterflies** with precomputed twiddle factor recurrence (no `cos/sin` per butterfly inside the inner loop).

The implementation is allocation-free per call. Frame buffer, FFT buffer, and `powers` array are reused across frames.

## Mel filterbank construction

`Mfcc.buildMelFilters` constructs `numMelFilters` triangular filters whose peaks are evenly spaced on the mel scale between `melLowHz` and `melHighHz`. Each filter is a `DoubleArray` of length `fftSize/2 + 1` whose entries are the contribution of each FFT bin to that filter. Each frame's filterbank output is the dot product of the power spectrum with each filter.

`hzToMel(f) = 2595 · log₁₀(1 + f/700)` is the O'Shaughnessy 1987 formula.

The filterbank is built once in the `Mfcc` constructor; the inner loop just dots it with the power spectrum.

## DCT

`Mfcc.buildDctMatrix` precomputes a `numCoeffs × numMelFilters` matrix of DCT-II coefficients. Normalisation matches the orthogonal variant (`sqrt(1/N)` for c₀, `sqrt(2/N)` for c_k>0) so the same MFCCs can be compared across implementations.

The DCT is performed as a single matrix-vector product per frame.

## Why MFCC at all

MFCC is not the only feature set. Alternatives we considered:

- **Mel filterbank energies (no DCT).** Equivalent representation, slightly more correlated coefficients. DTW with Euclidean distance treats coefficients as orthogonal; uncorrelated MFCCs are friendlier. Stick with MFCC.
- **Spectrogram patches** with image-style distance. Tried briefly during research; sensitive to timing differences that DTW would otherwise handle.
- **Speaker-agnostic embeddings** (Wav2Vec, Resnet50). Higher accuracy ceiling, much more complexity. This is the [Phase 3 TFLite path](#future-tflite-engine).
- **Raw PCM with DTW.** Distance computation is O(N²) per take with N ~ 24 000 sample-pairs. Far too slow on a phone.

The MFCC + DTW combination is the textbook approach for speaker-dependent small-vocabulary command recognition (cf. Sakoe & Chiba 1978, the original DTW-on-speech paper). The implementation here is the textbook with no clever tricks, optimised lightly for the JVM.

## DTW

`voice/Dtw.kt` implements path-length-normalised DTW between two MFCC sequences, with the Sakoe-Chiba band.

### Algorithm

Standard:

```
D[0,0]   = 0
D[i,j]   = ||a[i] − b[j]||  +  min(D[i−1, j], D[i, j−1], D[i−1, j−1])
result   = D[n, m] / (n + m)
```

We compute D row-by-row in two rolling buffers (`prev`, `curr`) rather than the full matrix. Space is `O(m)` per row; time is `O(n × m)` worst case, `O(n × bandWidth)` with the band.

### Sakoe-Chiba band

`bandRatio = 0.15` means the warping path can stray at most `0.15 × max(n, m)` cells off the diagonal. For a 98-frame query against a 105-frame template that's ±16 cells. The band:

1. Prunes obviously-bad alignments. Without it, a 1.5 s utterance can warp into matching a one-frame chunk of the template.
2. Cuts compute by a factor of `(1 / bandRatio)`. For typical 98-frame queries this is ~6× speedup.

The band width is clamped to at least `|n − m| + 1` so that valid diagonal paths are never excluded for templates of slightly different length.

### Distance per cell

`euclid(x, y) = sqrt(Σ (x[k] − y[k])²)` over the 13 MFCC coefficients.

Tried alternatives:

- **Cosine distance.** Insensitive to overall energy scale, but MFCC c₀ already captures energy and we want to be sensitive to volume differences (e.g. distinguishing whispered "faster" from emphatic "faster"). Euclidean retains this.
- **Manhattan (L1) distance.** Marginally faster, slightly worse separation in informal tests. Not worth the swap.

### Path-length normalisation

`result = D[n, m] / (n + m)` divides by the upper bound on warping path length. This makes long-vs-short comparisons fair: a longer template doesn't accumulate more raw distance just because it has more frames.

This is the *path-length-upper-bound* normalisation, not the *actual-path-length* one (which would require backtracking the path). The upper bound is cheap and "close enough" for ranking templates against the same query.

## Threshold and confidence

`TemplateEngine.Match` carries three fields:

- `label` — the best label.
- `score` — its normalised DTW distance. Lower is better.
- `runnerUp` — the second-lowest score across all other labels.

`margin = runnerUp − score` is the confidence indicator. A large margin means the best label is clearly best; a small margin means two labels are competing. The Phase 1 dispatch path only compares `score` to the threshold, but a future "use margin too" check would reject ambiguous matches (e.g. `slow` vs `stop` if those phonemes get confused).

### Choosing a threshold

`PreferencesManager.DEFAULT_THRESHOLD = 30f` is a starting guess. The actual right value depends on:

- The user's voice (consistency between takes; some speakers vary more than others).
- The user's mic (consumer Android mics have wildly different responses).
- The room (reverb adds variance to MFCC sequences).
- Whether always-on mode is on (false-fires on background sound require a stricter threshold than button mode).

**Empirical tuning loop:**

1. Record your templates normally. Open the app.
2. Issue every command twice; watch the debug log line `Heard: <label> score=NN.NN margin=MM.MM`.
3. Note the highest score you saw for a *correct* match. Call it `S_correct_max`.
4. Issue commands you did *not* train (or speak nonsense at the mic). Note the lowest score you saw. Call it `S_wrong_min`.
5. Set the threshold to `(S_correct_max + S_wrong_min) / 2` if there's a gap; if `S_correct_max > S_wrong_min` there's no clean threshold, and you should re-record the worst-performing label.

Typical good values are 10–35 with Euclidean distance over 13 MFCCs on the default framing. If you find you need 60+, something is wrong with the recordings.

## Storage format

`TemplateStore` writes one JSON file per take under `filesDir/templates/<label>/take_N.json`:

```json
{
  "format": 1,
  "label": "start",
  "mfcc": [
    [-0.123, 0.456, ...],   // 13 doubles
    [-0.118, 0.461, ...],
    ...                     // ~98 frames
  ]
}
```

Reading: `gson.fromJson(...)` into `SerializedTake`. Future formats can be additive without breaking forward-compat.

Re-extraction: never required. The MFCCs are the stored artefact; the original PCM is discarded after extraction (we never write WAV files to disk). If the MFCC parameters in the constructor change, stored takes from the old parameters silently become non-comparable with new captures. Bump `format` and run a migration if you ever re-tune the framing.

## Swappable engine contract

`TemplateEngine` is the abstraction:

```kotlin
interface TemplateEngine {
    fun labels(): List<String>
    fun addTake(label: String, audio: ShortArray)
    fun clearLabel(label: String)
    fun clearAll()
    fun takeCount(label: String): Int
    fun match(audio: ShortArray): Match?
    data class Match(val label: String, val score: Double, val runnerUp: Double)
}
```

The only consumer is `ControlActivity` (for matching) and `TrainingActivity` (for adding takes). Swapping engines requires changing the constructor call and possibly the threshold default; nothing else.

## Future TFLite engine

`voice/TfliteEmbeddingEngine.kt` is a stub. The plan:

1. **Model.** Extract EfficientWord-Net's `Resnet50_Arc_loss` TFLite model (about 25 MB) from `vega`'s install or fetch from upstream. Ship it under `assets/`.
2. **Preprocessing.** Reimplement EfficientWord-Net's `audio_processing.py` in Kotlin: load PCM, resample if needed (we're already at 16 kHz), STFT, log-mel, normalise. The model expects a fixed input tensor.
3. **Inference.** Use `org.tensorflow.lite:tensorflow-lite` (CPU is fine for one 1.5 s utterance) to produce a single embedding vector per utterance.
4. **Matching.** Store one average embedding per label (mean of 3 takes); compare query via cosine similarity. Threshold becomes "similarity > 0.7" instead of "DTW < 30".
5. **Storage.** Same `TemplateStore`, but `mfcc` becomes a single-row matrix of the embedding. Bump `format` to 2 so old MFCC takes are not silently treated as embeddings.

The training UX does not change. The user re-records once when switching engines (because the on-disk shape is different); the same nine command labels apply.

## References

- Davis, S. & Mermelstein, P. (1980). *Comparison of parametric representations for monosyllabic word recognition in continuously spoken sentences.* IEEE Trans. ASSP.
- Sakoe, H. & Chiba, S. (1978). *Dynamic programming algorithm optimization for spoken word recognition.* IEEE Trans. ASSP.
- O'Shaughnessy, D. (1987). *Speech Communication: Human and Machine.* Addison-Wesley. (The mel-scale formula.)
- EfficientWord-Net (Khan et al.). https://github.com/Ant-Brain/EfficientWord-Net — the inspiration for the Phase 3 path.
