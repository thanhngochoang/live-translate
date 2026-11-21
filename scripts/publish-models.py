#!/usr/bin/env python3
"""Publishes build/models/ as a GitHub release and rewrites the app's model catalog to point at it.

    scripts/build-models.sh                      # build the model set from its sources
    scripts/publish-models.py voicetrans-models-YYYY.MM.DD [--upload]

Without --upload it only writes app/src/main/assets/models/models.json and the release notes
(build/model-release-notes.md). With --upload it also creates the release in MODEL_REPO with `gh` and uploads
the files. Release assets cannot contain "/", so "asr/tokens.txt" is uploaded as "asr.tokens.txt".
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "build" / "models"
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "models" / "models.json"
NOTES = ROOT / "build" / "model-release-notes.md"
MODEL_REPO = "thanhngochoang/image-generator-model"

# Speech models, read by the Android app and by the desktop apps ("files" in models.json).
SPEECH = [
    "asr/encoder-epoch-99-avg-1.int8.onnx",
    "asr/decoder-epoch-99-avg-1.onnx",
    "asr/joiner-epoch-99-avg-1.int8.onnx",
    "asr/tokens.txt",
    "vad/silero_vad.onnx",
    "tagging/model.int8.onnx",
    "tagging/class_labels_indices.csv",
]
# Translation models, Android only ("nmt" in models.json). Only the files the app opens.
NMT = [
    "nmt/nllb-600m/meta.json",
    "nmt/nllb-600m/encoder_model.onnx",
    "nmt/nllb-600m/decoder_model_merged.onnx",
    "nmt/nllb-600m/sentencepiece.bpe.model",
    "nmt/opus-zh-vi/meta.json",
    "nmt/opus-zh-vi/encoder_model.onnx",
    "nmt/opus-zh-vi/decoder_model_merged.onnx",
    "nmt/opus-zh-vi/source.spm",
    "nmt/opus-zh-vi/target.spm",
    "nmt/opus-zh-vi/vocab.json",
]

SOURCES = """\
| Files | Source | Licence |
|---|---|---|
| `asr.*` | sherpa-onnx streaming Zipformer bilingual zh-en 2023-02-20 (k2-fsa/sherpa-onnx `asr-models`), int8 encoder and joiner | see the upstream model card |
| `vad.silero_vad.onnx` | Silero VAD (k2-fsa/sherpa-onnx `asr-models`) | MIT |
| `tagging.*` | CED tiny audio tagging 2024-04-19 (k2-fsa/sherpa-onnx `audio-tagging-models`), int8 | Apache-2.0 |
| `nmt.nllb-600m.*` | facebook/nllb-200-distilled-600M exported to ONNX, int8 dynamic quantization | CC-BY-NC-4.0 (non-commercial use only) |
| `nmt.opus-zh-vi.*` | Helsinki-NLP/opus-mt-zh-vi exported to ONNX, fp32 | Apache-2.0 |
"""


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    upload = "--upload" in sys.argv
    if len(args) != 1:
        sys.exit(__doc__)
    tag = args[0]
    base = f"https://github.com/{MODEL_REPO}/releases/download/{tag}/"

    staged = []

    def entries(dests):
        out = []
        for dest in dests:
            path = SRC / dest
            if not path.is_file():
                sys.exit(f"Missing {path} (run scripts/build-models.sh)")
            asset = dest.replace("/", ".")
            staged.append((path, asset))
            out.append({
                "id": asset,
                "url": base + asset,
                "dest": dest,
                "sha256": sha256(path),
                "size": path.stat().st_size,
            })
        return out

    catalog = {
        "version": 2,
        "release": tag,
        "note": "Written by scripts/publish-models.py. The Android app downloads \"files\" and \"nmt\" on first run; "
                "the desktop apps download \"files\". Original sources: scripts/models-upstream.json and scripts/export-nmt.py.",
        "archives": [],
        "files": entries(SPEECH),
        "nmt": entries(NMT),
    }
    assert len({asset for _, asset in staged}) == len(staged), "asset names collide"
    CATALOG.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {CATALOG.relative_to(ROOT)}")

    rows = "\n".join(
        f"| `{e['id']}` | {e['size'] / (1 << 20):.1f} MB | `{e['sha256']}` |" for e in catalog["files"] + catalog["nmt"]
    )
    total = sum(e["size"] for e in catalog["files"] + catalog["nmt"]) / (1 << 20)
    NOTES.write_text(
        "Models for VoiceTrans VI (thanhngochoang/live-translate): speech recognition, voice activity detection, "
        "audio tagging and English/Chinese to Vietnamese translation. The apps download these files on first run and "
        "verify the SHA-256 below. An asset named `a.b.c` is stored by the app as `a/b/c`.\n\n"
        f"Total: {total:.0f} MB.\n\n"
        "| Asset | Size | SHA-256 |\n|---|---|---|\n" + rows + "\n\n" + SOURCES,
        encoding="utf-8",
    )
    print(f"Wrote {NOTES.relative_to(ROOT)}")

    if not upload:
        print("Dry run: nothing uploaded (pass --upload to create the release).")
        return

    # Upload under the flattened names by staging hard links (gh names an asset after its file).
    stage_dir = ROOT / "build" / "model-release-assets"
    stage_dir.mkdir(parents=True, exist_ok=True)
    for old in stage_dir.iterdir():
        old.unlink()
    paths = []
    for path, asset in staged:
        link = stage_dir / asset
        try:
            link.hardlink_to(path)
        except OSError:
            link.write_bytes(path.read_bytes())
        paths.append(str(link))
    subprocess.run(
        ["gh", "release", "create", tag, "--repo", MODEL_REPO, "--title", f"VoiceTrans models {tag.rsplit('-', 1)[-1]}",
         "--notes-file", str(NOTES), "--latest=false", *paths],
        check=True,
    )
    print(f"Published https://github.com/{MODEL_REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
