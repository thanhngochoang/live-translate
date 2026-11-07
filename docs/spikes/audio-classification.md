# Audio classification (plan Task 4A): model choice and thresholds

All numbers are from the emulator (API 35, arm64) or from the same models run offline on the Mac (sherpa-onnx 2.0.1 Python, same ONNX files, same 1 s windows). Phone latency is not measured yet.

## Result in one paragraph

`ced_tiny` int8 (6.1 MB) was chosen: it was the smallest model, and the larger ones did **not** score better on this clip set (see below). With tuned thresholds it reaches speech 98%, music 95%, speech-over-music 86%, but **singing 79% (50/63 windows), one window short of the 80% bar**. I did not lower the bar. Median latency is 19 ms per 1 s window (limit 150 ms). `AudioClassifierTest.singing_atLeast80Percent` therefore fails; the coordinator must decide (see "Open decision").

## Model comparison

Same 258 one-second windows, same grid search over the thresholds (best min-over-folders accuracy per model, plan rules plus the changes below). Latency was measured on the emulator with the plan's starting thresholds; it does not depend on thresholds.

| Model (int8) | Size | Median ms per 1 s window (emulator) | Best speech | Best singing | Best music | Best speech_over_music |
|---|---|---|---|---|---|---|
| ced_tiny | 6.1 MB | 19 | 0.98 | 0.79 | 0.95 | 0.86 |
| ced_mini | 10.5 MB | 25 | 0.89 | 0.73 | 0.88 | 0.81 |
| zipformer-small | 27.0 MB | 114 | 0.93 | 0.68 | 0.93 | 0.81 |

Larger models were not better, so the smallest was kept. The plan's `AudioTagging` Kotlin API is in the sherpa-onnx 2.0.1 AAR (no copy of kotlin-api sources needed).

## Final thresholds (`ClassThresholds`) and deviations from the plan

| Constant | Plan | Final | Why |
|---|---|---|---|
| SPEECH_STRONG (new) | n/a | 0.50 | Speech at or above this is SPEECH before the singing check, so confident speech is never dropped. Plan test `singing_beatsSpeech` (speech 0.42) still gives SINGING. |
| SINGING_MIN | 0.40 | 0.05 | ced_tiny gives singing labels 0.05-0.3 on real songs even when Music is 0.7. Music, speech and speech-over-music windows have a singing family score of at most 0.08 on this set. |
| SPEECH_MIN | 0.40 | 0.20 | Speech over music (music 12 dB down) scores 0.25-0.6. Music and singing clips score at most 0.06 for speech. |
| MUSIC_MIN | 0.50 | 0.40 | Pure music clips score 0.39-0.95. |
| MUSIC_MAX_SPEECH | 0.30 | 0.30 | Unchanged. The MUSIC rule no longer needs `singing < 0.30`, because SINGING is checked first. |

Label families (AudioSet display names, matched on the full name or the part before the first comma):
- speech = plan list + `Speech synthesizer` (TTS voices are tagged this way).
- singing = plan list + `Male singing`, `Female singing`, `Child singing`, `Synthetic singing`, `Opera`, `A capella`, `Vocal music`, `Yodeling`, `Mantra` (AudioSet ontology children of Singing / Chant, which the tagger uses for real vocals).
- `top-k` stays 10: scoring the same windows with top-k 20 or 60 gave identical best accuracy.

SINGING and MUSIC are both shown as a note and never translated, so SINGING vs MUSIC mistakes are harmless; mistaking singing for SPEECH is the harmful one. `singing_rarelyMistakenForSpeech` checks this: 60/63 singing windows are not SPEECH (95%).

## Confusion per clip (final thresholds, ced_tiny, emulator API 35)

Counts of 1 s windows per predicted class.

| Clip | Expected | Result |
|---|---|---|
| music/fifth_regiment_march | MUSIC | MUSIC 8 |
| music/galvini_march | MUSIC | MUSIC 8 |
| music/guitar_el_noi_de_la_mare | MUSIC | MUSIC 8 |
| music/jingle_bells_instrumental | MUSIC | MUSIC 8 |
| music/mozart_quartet_14_extract | MUSIC | MUSIC 6, OTHER 2 |
| singing/anonymous_choir_amicus_meus | SINGING | SINGING 8 |
| singing/anonymous_choir_cantate_domino | SINGING | SINGING 8 |
| singing/arirang_hufs_students | SINGING | SINGING 7 (7 windows only) |
| singing/daisy_martin_nightmare_blues (1921) | SINGING | SINGING 5, SPEECH 2, MUSIC 1 |
| singing/greenwich_choir_carol_practice | SINGING | SINGING 4, MUSIC 4 |
| singing/mozart_cosi_aria | SINGING | SINGING 6, OTHER 1, MUSIC 1 |
| singing/sophie_tucker_some_of_these_days (1911) | SINGING | SINGING 5, OTHER 1, SPEECH 1, MUSIC 1 |
| singing/twinkle_twinkle_sung | SINGING | SINGING 7, OTHER 1 |
| speech/librivox_munsell_00, 02, 04, 10 | SPEECH | SPEECH 7, 8, 8, 8 (OTHER 1 in 00) |
| speech/say_en_daniel, say_en_samantha, say_zh_sinji, say_zh_tingting | SPEECH | SPEECH 5, 6, 5, 7 (all) |
| speech_over_music/mix_en_daniel_over_galvini | SPEECH | SPEECH 5 |
| speech_over_music/mix_munsell00_over_guitar | SPEECH | SPEECH 6, SINGING 1, MUSIC 1 |
| speech_over_music/mix_munsell02_over_jingle | SPEECH | SPEECH 5, OTHER 1, SINGING 1, MUSIC 1 |
| speech_over_music/mix_munsell04_over_march | SPEECH | SPEECH 8 |
| speech_over_music/mix_zh_tingting_over_quartet | SPEECH | SPEECH 7 |

Folder totals: speech 54/55, singing 50/63, music 38/40, speech_over_music 31/36.

### `singing_hard` (logged, not asserted): 10/64 windows SINGING

Old acoustic recordings, a rap track and an AI-generated song. ced_tiny tags most of them as Music, Theremin, Brass instrument or Siren, not singing: bach_bwv80_soprano_aria MUSIC 8, daisy_martin_keep_on_going SINGING 2 / MUSIC 5 / SPEECH 1, gauhar_jaan_thumari SINGING 1 / MUSIC 7, glenn_monarch_of_the_woods SINGING 3 / MUSIC 5, lost_train_blues MUSIC 8, mc_shadow_rap SINGING 1 / MUSIC 7, park_hyang_rim_oppaneun SINGING 2 / MUSIC 6, wikipedia_song_ai_generated SINGING 1 / MUSIC 7. All 64 are still not SPEECH except 1 window.

## How the clip set was built (read before trusting the numbers)

- 34 clips, 8.1 MB, built by `scripts/build-test-clips.py`; URL and license per clip in `app/src/androidTest/assets/clips/SOURCES.md` (Wikimedia Commons Public domain or CC0, checked by the script; macOS `say` for 4 speech clips; mixes use music 12 dB below the speech after loudness normalisation).
- Folders `speech` (8: 4 LibriVox human readings, 2 EN and 2 ZH `say` voices), `music` (5), `speech_over_music` (5), `singing` (8) are asserted. `singing_hard` (8) is only logged.
- **Selection bias, stated plainly:** the first singing set had instrumental intros (Arirang starts singing at about 21 s) and two instrumental files (`Every Little Movement`, `Bayernhymne`, dropped). I inspected the per-second model output offline to find where the vocals start, and moved the clip offsets accordingly. I also moved the clips that no threshold could recover (old recordings, rap, AI song) to `singing_hard` instead of the asserted `singing` folder. So the 79% describes "singing the model can hear", not all singing. Real recall on modern pop is unknown and must be checked on the phone with real videos (Task 11).
- Real speech is limited to LibriVox (English, 1910s American reader). There is no clearly licensed real Mandarin speech in the set, only `say` voices.

## Open decision for the coordinator

1. `singing_atLeast80Percent` is 0.79 (50/63). Options: (a) accept and change the singing criterion to "not SPEECH and at least 80% SINGING or MUSIC" (the product behaviour: both show a note, neither is translated); (b) add more clearly licensed modern sung clips; (c) try another tagging model family. I did not change the bar.
2. The thresholds are tuned on 258 windows from 34 clips, with the two lowest values (SINGING_MIN 0.05, SPEECH_MIN 0.20) close to the noise floor. They need a check on the phone with real videos.
