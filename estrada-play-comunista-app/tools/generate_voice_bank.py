#!/usr/bin/env python3
# OFFLINE_VOICE_V204: generate deterministic pt-BR warning clips at build time.
import argparse
import hashlib
import json
import shutil
from pathlib import Path

import numpy as np
import soundfile as sf
import sherpa_onnx

ONES = {
    0: "zero", 1: "um", 2: "dois", 3: "três", 4: "quatro", 5: "cinco",
    6: "seis", 7: "sete", 8: "oito", 9: "nove", 10: "dez", 11: "onze",
    12: "doze", 13: "treze", 14: "quatorze", 15: "quinze", 16: "dezesseis",
    17: "dezessete", 18: "dezoito", 19: "dezenove",
}
TENS = {20: "vinte", 30: "trinta", 40: "quarenta", 50: "cinquenta",
        60: "sessenta", 70: "setenta", 80: "oitenta", 90: "noventa"}
HUNDREDS = {100: "cem", 200: "duzentos", 300: "trezentos", 400: "quatrocentos",
            500: "quinhentos", 600: "seiscentos", 700: "setecentos",
            800: "oitocentos", 900: "novecentos"}


def pt_number(n: int) -> str:
    if n < 20:
        return ONES[n]
    if n < 100:
        tens = (n // 10) * 10
        rest = n % 10
        return TENS[tens] if rest == 0 else f"{TENS[tens]} e {ONES[rest]}"
    if n == 100:
        return "cem"
    if n < 200:
        return f"cento e {pt_number(n - 100)}"
    hundreds = (n // 100) * 100
    rest = n % 100
    return HUNDREDS[hundreds] if rest == 0 else f"{HUNDREDS[hundreds]} e {pt_number(rest)}"


def distance_text(m: int) -> str:
    if m < 1000:
        return f"{pt_number(m)} metros."
    rest = m - 1000
    if rest == 0:
        return "Um quilômetro."
    return f"Um quilômetro e {pt_number(rest)} metros."


def speed_text(kmh: int) -> str:
    return f"{pt_number(kmh)} quilômetros por hora."


def find_one(root: Path, pattern: str) -> Path:
    found = [p for p in root.rglob(pattern) if p.is_file()]
    if not found:
        raise SystemExit(f"Arquivo do modelo não encontrado: {pattern}")
    found.sort(key=lambda p: p.stat().st_size, reverse=True)
    return found[0]


def find_dir(root: Path, name: str) -> Path:
    for p in root.rglob(name):
        if p.is_dir():
            return p
    raise SystemExit(f"Diretório do modelo não encontrado: {name}")


def build_tts(model_root: Path):
    model = find_one(model_root, "*.onnx")
    tokens = find_one(model_root, "tokens.txt")
    data_dir = find_dir(model_root, "espeak-ng-data")
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(model), lexicon="", tokens=str(tokens), data_dir=str(data_dir),
        noise_scale=0.667, noise_scale_w=0.8, length_scale=1.0,
    )
    model_cfg = sherpa_onnx.OfflineTtsModelConfig(
        vits=vits, num_threads=2, debug=False, provider="cpu",
    )
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=model_cfg, rule_fsts="", max_num_sentences=1,
    )
    return sherpa_onnx.OfflineTts(config=cfg), model, tokens, data_dir


def synth(tts, text: str, target: Path):
    audio = tts.generate(text=text, sid=0, speed=1.0)
    samples = np.asarray(audio.samples, dtype=np.float32)
    if samples.size < 100:
        raise RuntimeError(f"Áudio vazio para {target.name}: {text}")
    peak = float(np.max(np.abs(samples))) if samples.size else 0.0
    if peak > 0.0:
        samples = samples * min(1.0, 0.92 / peak)
    target.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(target), samples, int(audio.sample_rate), subtype="PCM_16")
    if target.stat().st_size < 1024:
        raise RuntimeError(f"Áudio inválido: {target}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-root", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--metadata-output", required=True)
    args = ap.parse_args()

    model_root = Path(args.model_root).resolve()
    out = Path(args.output).resolve()
    meta = Path(args.metadata_output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    meta.mkdir(parents=True, exist_ok=True)
    for old in out.glob("ep_*.wav"):
        old.unlink()

    tts, model, tokens, data_dir = build_tts(model_root)
    phrases = {
        "ep_atencao": "Atenção.",
        "ep_radar_frente": "Radar à frente.",
        "ep_limite_radar": "Limite do radar.",
        "ep_limite_via": "Limite da via.",
        "ep_acima_limite": "Acima do limite da via.",
        "ep_semaforo_frente": "Semáforo à frente.",
        "ep_quebra_molas_frente": "Quebra-molas à frente.",
        "ep_pedagio_frente": "Pedágio à frente.",
        "ep_passagem_nivel_frente": "Passagem de nível à frente.",
        "ep_camera_monitoramento": "Câmera de monitoramento de tráfego à frente.",
        "ep_reduza": "Reduza.",
    }
    distance_values = list(range(30, 101, 10)) + list(range(150, 1000, 50)) + list(range(1000, 1501, 100))
    speed_values = list(range(10, 181, 5))

    manifest = {}
    for name, text in phrases.items():
        synth(tts, text, out / f"{name}.wav")
        manifest[name] = text
    for value in distance_values:
        name = f"ep_dist_{value:04d}"
        text = distance_text(value)
        synth(tts, text, out / f"{name}.wav")
        manifest[name] = text
    for value in speed_values:
        name = f"ep_speed_{value:03d}"
        text = speed_text(value)
        synth(tts, text, out / f"{name}.wav")
        manifest[name] = text

    source = {
        "profile": "EstradaPlay offline voice test v2.0.4",
        "source_project": "k2-fsa/sherpa-onnx",
        "model_family": "Piper VITS pt-BR",
        "model_file": model.name,
        "tokens": tokens.name,
        "data_dir": data_dir.name,
        "clip_count": len(manifest),
        "runtime_network_required": False,
        "note": "Experimental/test voice. Review upstream model license before commercial distribution.",
    }
    (meta / "estradaplay_voice_source.json").write_text(
        json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (meta / "estradaplay_voice_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    copied = 0
    for pattern in ("MODEL_CARD*", "LICENSE*", "README*"):
        for p in model_root.rglob(pattern):
            if not p.is_file() or p.stat().st_size > 2_000_000:
                continue
            shutil.copy2(p, meta / f"upstream_{copied}_{p.name}")
            copied += 1
            if copied >= 8:
                break
        if copied >= 8:
            break

    h = hashlib.sha256()
    for wav in sorted(out.glob("ep_*.wav")):
        h.update(wav.name.encode("utf-8"))
        h.update(wav.read_bytes())
    print(f"OFFLINE_VOICE_V204 clips={len(manifest)} bank_sha256={h.hexdigest()}")


if __name__ == "__main__":
    main()
