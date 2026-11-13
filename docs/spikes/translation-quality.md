# Spike: translation and ASR quality (2026-11-09)

Why translations were wrong, and which on-device engines fix it. All runs were on the Mac (M1, CPU only, no GPU), replicating the app's code paths. Nothing here ran on the phone.

## Test material

- 10 English and 10 Chinese lines written for this spike (table below), covering everyday speech, idioms, business language and Chinese period-drama forms of address.
- 7 sentences from the YouTube Short `fqyOhpYWEZw` ("Woman Does Accents Perfectly", 60 s, English with accents), transcribed by SenseVoice. The audio and its transcript are third-party content and are **not stored in the repo**. Fetch them locally with `yt-dlp` when needed.

| id | line |
|---|---|
| en01 | It runs in under two hundred milliseconds. |
| en02 | I didn't mean to hurt you, but you left me no choice. |
| en03 | The board approved the merger yesterday despite the shareholders' objections. |
| en04 | So what we're gonna do is take this one and put it over there. |
| en05 | He was supposed to call me back an hour ago and I still haven't heard anything from him, which is really starting to worry me. |
| en06 | Don't beat around the bush, just tell me what happened. |
| en07 | You're telling me you drove all the way here just to return my umbrella? |
| en08 | If you don't subscribe, you'll miss next week's episode. |
| en09 | My social battery runs out really quickly. |
| en10 | Honestly, I'm not even mad, I'm just disappointed. |
| zh01 | 大人，属下有一事相求。 |
| zh02 | 你今天吃饭了吗？ |
| zh03 | 这个项目的进度比我们预期的要慢很多。 |
| zh04 | 我真的不知道该怎么办了。 |
| zh05 | 我觉得这件事情我们应该再考虑一下，不要急着做决定。 |
| zh06 | 陛下，草民不敢。 |
| zh07 | 来人，把他给我拿下！ |
| zh08 | 你别以为我不知道你在背后搞什么鬼。 |
| zh09 | 这家店的火锅特别好吃，下次带你来。 |
| zh10 | 师父，弟子知错了。 |

Scoring per line: 2 = correct meaning and natural Vietnamese, 1 = meaning mostly right but awkward or partly lost, 0 = wrong, invented, untranslated, or containing Chinese characters or labels. Scored by hand.

## ASR

| Model | Result on the Short |
|---|---|
| Streaming Zipformer bilingual zh-en 2023 int8 (current) | ALL CAPS, no punctuation, many invented words ("VIDEOSUCK", "GILLIODALE"). |
| SenseVoice int8 2024-07-17 (228 MB) | Cased, punctuated, mostly correct. About 0.28 s to decode a 12 s VAD segment with 2 threads. |
| SenseVoice int8 2025-09-09 | Worse: tags English and Mandarin as Cantonese (`yue`), errors on its own bundled `en.wav`. Do not use. |

## Translation: small LLMs (llama.cpp, Q4_K_M, greedy + repeat penalty 1.1)

| Model | Size | EN /20 | ZH /20 | Short /14 | Total /54 |
|---|---|---|---|---|---|
| Qwen2.5 0.5B Instruct (current) | 469 MB | 1 | 5 | 3 | 9 |
| Qwen2.5 1.5B Instruct | 1.1 GB | 6 | 6 | 6 | 18 |
| Sailor2 1B Chat | 739 MB | 9 | 10 | 7 | 26 |
| Gemma 3 1B it | 806 MB | 10 | 7 | 9 | 26 |
| Qwen3 1.7B (thinking off) | 1.1 GB | 9 | 6 | 11 | 26 |

With the app's own prompt and sampling (temp 0.2, top-p 0.8, `n_batch` 512), the current Qwen 0.5B also loops ("so so so…"), answers like a chatbot ("Tất nhiên, tôi sẽ giúp bạn…"), copies English through, leaks `VI:` labels, and **crashes the process** (`GGML_ASSERT(logits != nullptr)`) when a prompt exceeds 512 tokens, which happens when a bad earlier translation is fed back as context.

## Translation: dedicated NMT (Hugging Face, PyTorch fp32, beam 4)

| Model | Params | License | EN /20 | ZH /20 | Short /14 |
|---|---|---|---|---|---|
| Helsinki-NLP/opus-mt-en-vi | 72M | Apache-2.0 | 12 | – | 5 |
| vinai/vinai-translate-en2vi-v2 | 423M | AGPL-3.0 | 18 | – | 7 |
| Helsinki-NLP/opus-mt-zh-vi | 78M | Apache-2.0 | – | **16** | – |
| facebook/nllb-200-distilled-600M | 615M | CC-BY-NC-4.0 | 18 | 9 | **10** |
| facebook/nllb-200-distilled-1.3B | 1371M | CC-BY-NC-4.0 | 19 | 12 | 8 |

Best pair: NLLB-600M for English and OPUS-MT zh-vi for Chinese, 44/54. OPUS-MT zh-vi is the only model that renders the period-drama lines correctly ("Đại nhân, thuộc hạ có việc cần nhờ.", "Thần không dám, thưa bệ hạ.", "Lính đâu, bắt hắn cho ta!"). Every model fails zh09 (火锅 becomes "lò than", "bạch tuộc", "gà").

## ONNX Runtime variants (CPU, 4 intra-op threads)

Exported with `optimum-cli export onnx --task text2text-generation-with-past` (optimum-onnx, transformers 4.57.6), quantized with `onnxruntime.quantization.quantize_dynamic(weight_type=QInt8)`. The NLLB decoder merge step failed, so the separate `decoder_model.onnx` and `decoder_with_past_model.onnx` were used.

| Variant | Avg ms | Max ms | Same output as PyTorch fp32 | Notes |
|---|---|---|---|---|
| OPUS zh-vi fp32, beam 4 | 105 | 163 | 10/10 | no loss |
| OPUS zh-vi int8 per-channel, beam 4 | 74 | 113 | 7/10 | 草民 line loses register |
| OPUS zh-vi int8 per-tensor, greedy | 38 | 53 | 3/10 | clear loss ("Đại nhân, đại nhân có việc cần nhờ.") |
| NLLB-600M int8 per-channel, beam 4 | 402 | 1220 | 14/27 | EN about 17/20, Short 10/14 |
| NLLB-600M int8 per-channel, greedy | 204 | 597 | 7/27 | drops words ("Đừng đùa, chỉ nói chuyện gì.") |

On-disk size of the variants as exported (encoder + one decoder): OPUS zh-vi fp32 about 580 MB; NLLB-600M int8 per-channel about 1.15 GB, most of it the fp32 256k-token embedding and output layer that dynamic quantization leaves untouched. Tokenization of the exported tokenizers is identical to the originals on all 27 lines.
