#!/usr/bin/env python3
from pathlib import Path

root = Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista')
files = [
    'CommunistCopilot.java',
    'DestinationActivity.java',
    'DestinationResolver.java',
    'DestinationStore.java',
    'RouteEngine.java',
]

for name in files:
    path = root / name
    text = path.read_text(encoding='utf-8')
    if text.startswith('\\\n'):
        text = text[2:]
    elif text.startswith('\\'):
        text = text[1:]
    path.write_text(text, encoding='utf-8')
    if path.read_text(encoding='utf-8').startswith('\\'):
        raise SystemExit(f'prefix fix failed: {name}')
    print(f'fixed {name}')
