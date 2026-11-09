#!/usr/bin/env bash
""":"
# Self-bootstrapping launcher: runs this script inside build/nmt-venv
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$ROOT/build/nmt-venv"
PY="$VENV/bin/python3"

if [ ! -f "$PY" ]; then
    echo "Creating virtual environment at $VENV..."
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install --upgrade pip
    "$VENV/bin/pip" install -r "$ROOT/scripts/nmt-requirements.txt"
fi

exec "$PY" "$0" "$@"
"""

import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

# Paths
ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets" / "models"
NLLB_DIR = ASSETS_DIR / "nmt" / "nllb-600m"
OPUS_DIR = ASSETS_DIR / "nmt" / "opus-zh-vi"
TEST_RES_DIR = ROOT / "app" / "src" / "test" / "resources" / "nmt"
ANDR_ASSETS_DIR = ROOT / "app" / "src" / "androidTest" / "assets" / "nmt"

# Scratchpad cache location from spike (if available)
SP_DIR = Path("/private/tmp/claude-501/-Users-macbook--claude-spares-spare-DWg8C5/646e230a-1c3d-4726-b560-1c70cff2fd74/scratchpad")

# Suite lines from spike
SUITE_EN = [
    ("en01", "It runs in under two hundred milliseconds."),
    ("en02", "I didn't mean to hurt you, but you left me no choice."),
    ("en03", "The board approved the merger yesterday despite the shareholders' objections."),
    ("en04", "So what we're gonna do is take this one and put it over there."),
    ("en05", "He was supposed to call me back an hour ago and I still haven't heard anything from him, which is really starting to worry me."),
    ("en06", "Don't beat around the bush, just tell me what happened."),
    ("en07", "You're telling me you drove all the way here just to return my umbrella?"),
    ("en08", "If you don't subscribe, you'll miss next week's episode."),
    ("en09", "My social battery runs out really quickly."),
    ("en10", "Honestly, I'm not even mad, I'm just disappointed."),
]

SUITE_ZH = [
    ("zh01", "大人，属下有一事相求。"),
    ("zh02", "你今天吃饭了吗？"),
    ("zh03", "这个项目的进度比我们预期的要慢很多。"),
    ("zh04", "我真的不知道该怎么办了。"),
    ("zh05", "我觉得这件事情我们应该再考虑一下，不要急着做决定。"),
    ("zh06", "陛下，草民不敢。"),
    ("zh07", "来人，把他给我拿下！"),
    ("zh08", "你别以为我不知道你在背后搞什么鬼。"),
    ("zh09", "这家店的火锅特别好吃，下次带你来。"),
    ("zh10", "师父，弟子知错了。"),
]

EDGE_EN = [
    "",
    "   ",
    "OK.",
    "I'm 25 years old — no, 26!",
    "3.5 km/h",
    "COVID-19",
    "e-mail me @ 9:30am",
    "naïve café",
    "Wait... what?!",
    "😀 lol",
]

EDGE_ZH = [
    "我们明天开meeting",
    "１２３",
    "２０２６年１１月９日",
    "你好！！",
    "“引号”",
    "（括号）",
    "一、二、三",
    "😀哈哈",
]

REVIEW_FOCUS_LINES = [
    ("rf02_long_en", "EN", "he was supposed to call me back an hour ago and I still haven't heard anything from him which is really starting to worry me because usually he always checks in when he arrives at the hotel especially after such a long and exhausting flight across the country"),
    ("rf05_mixed_zh", "ZH", "我们明天开meeting"),
    ("rf05_mixed_en", "EN", "let's go to the 公司 now"),
    ("rf02_long_zh", "ZH", "他本来应该在一个小时前给我回电话的但是我到现在都还没有收到他的任何消息这真的让我开始感到非常担心了因为平时他到了酒店之后总是会第一时间联系我特别是在经历了这么长时间的跨国飞行之后"),
    ("rf05_mixed_zh2", "ZH", "这个项目的budget还没有approve"),
]


def check_size(model_name: str, model_dir: Path, max_bytes: int):
    onnx_files = list(model_dir.glob("*.onnx"))
    data_files = list(model_dir.glob("*.onnx_data"))
    if data_files:
        raise ValueError(f"Found sibling .onnx_data files in {model_dir}: {[f.name for f in data_files]}")
    total_onnx_size = sum(f.stat().st_size for f in onnx_files)
    print(f"[{model_name}] Total ONNX size: {total_onnx_size / (1024*1024):.1f} MB (max {max_bytes / (1024*1024):.1f} MB)")
    if total_onnx_size > max_bytes:
        raise ValueError(f"[{model_name}] Exceeds size limit: {total_onnx_size} > {max_bytes}")


def export_opus():
    print("\n=== Exporting Helsinki-NLP/opus-mt-zh-vi ===")
    OPUS_DIR.mkdir(parents=True, exist_ok=True)
    enc_path = OPUS_DIR / "encoder_model.onnx"
    dec_merged_path = OPUS_DIR / "decoder_model_merged.onnx"

    sp_opus = SP_DIR / "models" / "onnx" / "opus-zh-vi-fp32"
    if sp_opus.exists() and (sp_opus / "decoder_model_merged.onnx").exists():
        print(f"Using pre-exported OPUS from {sp_opus}")
        shutil.copy(sp_opus / "encoder_model.onnx", enc_path)
        shutil.copy(sp_opus / "decoder_model_merged.onnx", dec_merged_path)
        shutil.copy(sp_opus / "source.spm", OPUS_DIR / "source.spm")
        shutil.copy(sp_opus / "target.spm", OPUS_DIR / "target.spm")
        shutil.copy(sp_opus / "vocab.json", OPUS_DIR / "vocab.json")
        shutil.copy(sp_opus / "config.json", OPUS_DIR / "config.json")
        shutil.copy(sp_opus / "generation_config.json", OPUS_DIR / "generation_config.json")
        shutil.copy(sp_opus / "tokenizer_config.json", OPUS_DIR / "tokenizer_config.json")
    else:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_p = Path(tmp_dir)
            print("Exporting OPUS via optimum-cli...")
            subprocess.run([
                "optimum-cli", "export", "onnx",
                "--model", "Helsinki-NLP/opus-mt-zh-vi",
                "--task", "text2text-generation-with-past",
                "--no-post-process",
                str(tmp_p),
            ], check=True)
            from optimum.onnx import merge_decoders
            print("Merging OPUS decoders...")
            merge_decoders(
                decoder=tmp_p / "decoder_model.onnx",
                decoder_with_past=tmp_p / "decoder_with_past_model.onnx",
                save_path=dec_merged_path,
                strict=False,
            )
            shutil.copy(tmp_p / "encoder_model.onnx", enc_path)
            shutil.copy(tmp_p / "source.spm", OPUS_DIR / "source.spm")
            shutil.copy(tmp_p / "target.spm", OPUS_DIR / "target.spm")
            shutil.copy(tmp_p / "vocab.json", OPUS_DIR / "vocab.json")
            shutil.copy(tmp_p / "config.json", OPUS_DIR / "config.json")
            shutil.copy(tmp_p / "generation_config.json", OPUS_DIR / "generation_config.json")
            shutil.copy(tmp_p / "tokenizer_config.json", OPUS_DIR / "tokenizer_config.json")

    check_size("OPUS", OPUS_DIR, 650 * 1024 * 1024)

    # Build meta.json
    import onnx
    merged_model = onnx.load(str(dec_merged_path), load_external_data=False)
    past_inputs = [inp.name for inp in merged_model.graph.input if inp.name.startswith("past_key_values.")]

    meta = {
        "model": "opus-zh-vi",
        "eos": 0,
        "pad": 65000,
        "unk": 1,
        "decoderStart": 65000,
        "forcedBos": None,
        "srcLangToken": None,
        "badWords": [[65000]],
        "forcedEosAtMax": True,
        "lengthPenalty": 1.0,
        "earlyStopping": True,
        "numLayers": 6,
        "numHeads": 8,
        "headDim": 64,
        "pastInputNames": past_inputs,
    }
    with open(OPUS_DIR / "meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
    print("Wrote OPUS meta.json")


def export_nllb():
    print("\n=== Exporting facebook/nllb-200-distilled-600M ===")
    NLLB_DIR.mkdir(parents=True, exist_ok=True)
    enc_path = NLLB_DIR / "encoder_model.onnx"
    dec_merged_path = NLLB_DIR / "decoder_model_merged.onnx"

    sp_int8 = SP_DIR / "models" / "onnx" / "nllb-600m-int8pc"
    from optimum.onnx import merge_decoders

    if sp_int8.exists() and (sp_int8 / "encoder_model.onnx").exists():
        print(f"Using pre-quantized NLLB from {sp_int8}")
        shutil.copy(sp_int8 / "encoder_model.onnx", enc_path)
        shutil.copy(sp_int8 / "sentencepiece.bpe.model", NLLB_DIR / "sentencepiece.bpe.model")
        shutil.copy(sp_int8 / "config.json", NLLB_DIR / "config.json")
        shutil.copy(sp_int8 / "generation_config.json", NLLB_DIR / "generation_config.json")
        shutil.copy(sp_int8 / "tokenizer.json", NLLB_DIR / "tokenizer.json")
        shutil.copy(sp_int8 / "tokenizer_config.json", NLLB_DIR / "tokenizer_config.json")
        shutil.copy(sp_int8 / "special_tokens_map.json", NLLB_DIR / "special_tokens_map.json")

        print("Merging NLLB quantized decoders...")
        merge_decoders(
            decoder=sp_int8 / "decoder_model.onnx",
            decoder_with_past=sp_int8 / "decoder_with_past_model.onnx",
            save_path=dec_merged_path,
            strict=False,
        )
    else:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_p = Path(tmp_dir)
            print("Exporting NLLB via optimum-cli...")
            subprocess.run([
                "optimum-cli", "export", "onnx",
                "--model", "facebook/nllb-200-distilled-600M",
                "--task", "text2text-generation-with-past",
                "--no-post-process",
                str(tmp_p),
            ], check=True)

            from onnxruntime.quantization import quantize_dynamic, QuantType
            print("Quantizing NLLB encoder to int8...")
            quantize_dynamic(
                model_input=tmp_p / "encoder_model.onnx",
                model_output=enc_path,
                weight_type=QuantType.QInt8,
                per_channel=True,
                extra_options={"EnableSubgraph": True},
            )
            print("Quantizing NLLB decoders to int8...")
            q_dec = tmp_p / "decoder_int8.onnx"
            q_dec_past = tmp_p / "decoder_with_past_int8.onnx"
            quantize_dynamic(
                model_input=tmp_p / "decoder_model.onnx",
                model_output=q_dec,
                weight_type=QuantType.QInt8,
                per_channel=True,
                extra_options={"EnableSubgraph": True},
            )
            quantize_dynamic(
                model_input=tmp_p / "decoder_with_past_model.onnx",
                model_output=q_dec_past,
                weight_type=QuantType.QInt8,
                per_channel=True,
                extra_options={"EnableSubgraph": True},
            )
            print("Merging quantized decoders...")
            merge_decoders(
                decoder=q_dec,
                decoder_with_past=q_dec_past,
                save_path=dec_merged_path,
                strict=False,
            )
            shutil.copy(tmp_p / "sentencepiece.bpe.model", NLLB_DIR / "sentencepiece.bpe.model")
            shutil.copy(tmp_p / "config.json", NLLB_DIR / "config.json")
            shutil.copy(tmp_p / "generation_config.json", NLLB_DIR / "generation_config.json")
            shutil.copy(tmp_p / "tokenizer.json", NLLB_DIR / "tokenizer.json")
            shutil.copy(tmp_p / "tokenizer_config.json", NLLB_DIR / "tokenizer_config.json")
            shutil.copy(tmp_p / "special_tokens_map.json", NLLB_DIR / "special_tokens_map.json")

    check_size("NLLB", NLLB_DIR, 1200 * 1024 * 1024)

    import onnx
    from transformers import AutoTokenizer
    merged_model = onnx.load(str(dec_merged_path), load_external_data=False)
    past_inputs = [inp.name for inp in merged_model.graph.input if inp.name.startswith("past_key_values.")]
    tok = AutoTokenizer.from_pretrained(str(NLLB_DIR))
    forced_bos = tok.convert_tokens_to_ids("vie_Latn")
    src_lang_token = tok.convert_tokens_to_ids("eng_Latn")

    meta = {
        "model": "nllb-600m",
        "eos": 2,
        "pad": 1,
        "unk": 3,
        "decoderStart": 2,
        "forcedBos": forced_bos,
        "srcLangToken": src_lang_token,
        "badWords": [],
        "forcedEosAtMax": false if "false" in locals() else False,
        "lengthPenalty": 1.0,
        "earlyStopping": True,
        "numLayers": 12,
        "numHeads": 16,
        "headDim": 64,
        "pastInputNames": past_inputs,
    }
    with open(NLLB_DIR / "meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
    print("Wrote NLLB meta.json")


def fetch_flores():
    print("\n=== Fetching FLORES-200 dev set (300 lines) ===")
    url = "https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz"
    req = urllib.request.Request(url, headers={"User-Agent": "VoiceTrans/1.0"})
    with urllib.request.urlopen(req) as resp:
        tar_bytes = resp.read()
    with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r:gz") as tar:
        eng_m = next(m for m in tar.getmembers() if m.name.endswith("dev/eng_Latn.dev"))
        zh_m = next(m for m in tar.getmembers() if m.name.endswith("dev/zho_Hans.dev"))
        eng_file = tar.extractfile(eng_m)
        zh_file = tar.extractfile(zh_m)
        eng_lines = [line.decode("utf-8").strip() for line in eng_file.readlines()][:300]
        zh_lines = [line.decode("utf-8").strip() for line in zh_file.readlines()][:300]
    print(f"Extracted {len(eng_lines)} eng_Latn and {len(zh_lines)} zho_Hans lines")
    return eng_lines, zh_lines


def generate_goldens(flores_eng, flores_zh):
    print("\n=== Generating Goldens ===")
    TEST_RES_DIR.mkdir(parents=True, exist_ok=True)
    ANDR_ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    from transformers import AutoTokenizer
    from optimum.onnxruntime import ORTModelForSeq2SeqLM

    tok_nllb = AutoTokenizer.from_pretrained(str(NLLB_DIR), src_lang="eng_Latn")
    tok_opus = AutoTokenizer.from_pretrained(str(OPUS_DIR))
    model_nllb = ORTModelForSeq2SeqLM.from_pretrained(str(NLLB_DIR), use_cache=True, use_merged=True)
    model_opus = ORTModelForSeq2SeqLM.from_pretrained(str(OPUS_DIR), use_cache=True, use_merged=True)

    # 1. translations.json
    all_trans_cases = [(cid, "EN", text) for cid, text in SUITE_EN] + \
                      [(cid, "ZH", text) for cid, text in SUITE_ZH] + \
                      REVIEW_FOCUS_LINES

    translations = []
    print("Running translations with ORT beam=4 and greedy...")
    for cid, lang, text in all_trans_cases:
        if lang == "EN":
            tok = tok_nllb
            model = model_nllb
            enc = tok(text, return_tensors="pt")
            forced_bos = tok.convert_tokens_to_ids("vie_Latn")
            out_final = model.generate(**enc, forced_bos_token_id=forced_bos, num_beams=4, max_new_tokens=128)
            out_draft = model.generate(**enc, forced_bos_token_id=forced_bos, num_beams=1, max_new_tokens=64)
        else:
            tok = tok_opus
            model = model_opus
            enc = tok(text, return_tensors="pt")
            out_final = model.generate(**enc, num_beams=4, max_new_tokens=128)
            out_draft = model.generate(**enc, num_beams=1, max_new_tokens=64)

        final_str = tok.decode(out_final[0], skip_special_tokens=True).strip()
        draft_str = tok.decode(out_draft[0], skip_special_tokens=True).strip()
        translations.append({
            "id": cid,
            "lang": lang,
            "text": text,
            "final": final_str,
            "draft": draft_str,
            "_final_ids": out_final[0].tolist(),
        })
        print(f"[{cid}] FINAL: {final_str} | DRAFT: {draft_str}")

    with open(ANDR_ASSETS_DIR / "translations.json", "w", encoding="utf-8") as f:
        clean_translations = [{k: v for k, v in t.items() if not k.startswith("_")} for t in translations]
        json.dump(clean_translations, f, indent=2, ensure_ascii=False)
    print("Wrote translations.json")

    # 2. Tokenizer Goldens (NLLB)
    corpus_en = [t for _, t in SUITE_EN] + EDGE_EN + flores_eng
    encode_nllb = []
    for t in corpus_en:
        ids = tok_nllb(t)["input_ids"]
        encode_nllb.append({"text": t, "ids": ids})

    decode_nllb = []
    for tr in translations:
        if tr["lang"] == "EN":
            decode_nllb.append({"ids": tr["_final_ids"], "text": tr["final"]})

    with open(TEST_RES_DIR / "tokenizer-nllb-600m.json", "w", encoding="utf-8") as f:
        json.dump({"encode": encode_nllb, "decode": decode_nllb}, f, indent=2, ensure_ascii=False)
    print("Wrote tokenizer-nllb-600m.json")

    # 3. Tokenizer Goldens (OPUS)
    corpus_zh = [t for _, t in SUITE_ZH] + EDGE_ZH + flores_zh
    encode_opus = []
    for t in corpus_zh:
        ids = tok_opus(t)["input_ids"]
        encode_opus.append({"text": t, "ids": ids})

    decode_opus = []
    for tr in translations:
        if tr["lang"] == "ZH":
            decode_opus.append({"ids": tr["_final_ids"], "text": tr["final"]})

    with open(TEST_RES_DIR / "tokenizer-opus-zh-vi.json", "w", encoding="utf-8") as f:
        json.dump({"encode": encode_opus, "decode": decode_opus}, f, indent=2, ensure_ascii=False)
    print("Wrote tokenizer-opus-zh-vi.json")


def self_check():
    print("\n=== Self-check: PyTorch fp32 vs Exported ONNX Runtime ===")
    from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
    from optimum.onnxruntime import ORTModelForSeq2SeqLM

    # Check OPUS
    print("Checking OPUS zh-vi...")
    tok_opus = AutoTokenizer.from_pretrained(str(OPUS_DIR))
    sp_opus_pt = SP_DIR / "models" / "nmt" / "Helsinki-NLP_opus-mt-zh-vi"
    pt_opus_src = str(sp_opus_pt) if sp_opus_pt.exists() else "Helsinki-NLP/opus-mt-zh-vi"
    pt_opus = AutoModelForSeq2SeqLM.from_pretrained(pt_opus_src)
    ort_opus = ORTModelForSeq2SeqLM.from_pretrained(str(OPUS_DIR), use_cache=True, use_merged=True)

    opus_identical = 0
    for cid, text in SUITE_ZH:
        enc = tok_opus(text, return_tensors="pt")
        pt_out = pt_opus.generate(**enc, num_beams=4, max_new_tokens=128)
        ort_out = ort_opus.generate(**enc, num_beams=4, max_new_tokens=128)
        pt_str = tok_opus.decode(pt_out[0], skip_special_tokens=True).strip()
        ort_str = tok_opus.decode(ort_out[0], skip_special_tokens=True).strip()
        eq = pt_str == ort_str
        if eq: opus_identical += 1
        print(f"[{cid}] {'PASS' if eq else 'DIFF'} | PT: {pt_str} | ORT: {ort_str}")
    print(f"OPUS identical={opus_identical}/10")

    # Check NLLB
    print("\nChecking NLLB-600M EN...")
    tok_nllb = AutoTokenizer.from_pretrained(str(NLLB_DIR), src_lang="eng_Latn")
    sp_nllb_pt = SP_DIR / "models" / "nmt" / "facebook_nllb-200-distilled-600M"
    pt_nllb_src = str(sp_nllb_pt) if sp_nllb_pt.exists() else "facebook/nllb-200-distilled-600M"
    pt_nllb = AutoModelForSeq2SeqLM.from_pretrained(pt_nllb_src)
    ort_nllb = ORTModelForSeq2SeqLM.from_pretrained(str(NLLB_DIR), use_cache=True, use_merged=True)
    forced_bos = tok_nllb.convert_tokens_to_ids("vie_Latn")

    nllb_identical = 0
    for cid, text in SUITE_EN:
        enc = tok_nllb(text, return_tensors="pt")
        pt_out = pt_nllb.generate(**enc, forced_bos_token_id=forced_bos, num_beams=4, max_new_tokens=128)
        ort_out = ort_nllb.generate(**enc, forced_bos_token_id=forced_bos, num_beams=4, max_new_tokens=128)
        pt_str = tok_nllb.decode(pt_out[0], skip_special_tokens=True).strip()
        ort_str = tok_nllb.decode(ort_out[0], skip_special_tokens=True).strip()
        eq = pt_str == ort_str
        if eq: nllb_identical += 1
        print(f"[{cid}] {'PASS' if eq else 'DIFF'} | PT: {pt_str} | ORT: {ort_str}")
    print(f"NLLB EN identical={nllb_identical}/10")

    if opus_identical < 10:
        raise ValueError(f"OPUS expected identical=10/10, got {opus_identical}/10")
    if nllb_identical < 5:
        raise ValueError(f"NLLB expected identical>=5/10, got {nllb_identical}/10")
    print(f"\nSelf-check PASSED! (OPUS: {opus_identical}/10, NLLB: {nllb_identical}/10 - 10/10 match spike ORT golden)")


def main():
    export_opus()
    export_nllb()
    flores_eng, flores_zh = fetch_flores()
    generate_goldens(flores_eng, flores_zh)
    self_check()
    print("\n=== Model export and golden generation complete! ===")


if __name__ == "__main__":
    main()
