from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
INDEX = ROOT / 'index.php'
AURORA = ROOT / 'assets/css/aurora.css'
OVERRIDE = Path(__file__).with_name('v13.4.3-map-compact.css')

if not INDEX.exists():
    raise SystemExit(f'index.php não encontrado em {ROOT}')
if not AURORA.exists():
    raise SystemExit(f'assets/css/aurora.css não encontrado em {ROOT}')

css = OVERRIDE.read_text(encoding='utf-8').strip()
index = INDEX.read_text(encoding='utf-8')

# Cache bust / visible version.
index = index.replace('v=13.4.2', 'v=13.4.3')
index = index.replace('MusicRoad v13.4.2', 'MusicRoad v13.4.3')
index = index.replace("version:'13.4.1'", "version:'13.4.3'")
index = index.replace('cockpit-player.js?v=13.4.1', 'cockpit-player.js?v=13.4.3')
index = index.replace('cockpit.js?v=13.4.1', 'cockpit.js?v=13.4.3')

marker = '/* MusicRoad v13.4.3 compact navigation override */'
block = f'\n<style id="mr-v1343-map-compact">\n{marker}\n{css}\n</style>\n'
if marker not in index:
    index = index.replace('</head>', block + '</head>', 1)

INDEX.write_text(index, encoding='utf-8')

version = ROOT / 'VERSION'
version.write_text('13.4.3\n', encoding='utf-8')

readme = ROOT / 'LEIA-v13.4.3.txt'
readme.write_text(
    'MusicRoad v13.4.3 — navegação compacta\n\n'
    '- remove o segundo botão de menu do mapa;\n'
    '- reduz o card Navegando para uma faixa compacta;\n'
    '- libera espaço para velocidade, limite, fiscalização e geometria da rota;\n'
    '- mantém as correções de fiscalização da v13.4.2.\n\n'
    'Importante: preserve config/, storage/ e database/ ao atualizar.\n',
    encoding='utf-8'
)

print(f'MusicRoad v13.4.3 aplicado em {ROOT}')
