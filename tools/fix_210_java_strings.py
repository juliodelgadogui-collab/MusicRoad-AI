from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')

s = s.replace(
    'alert("Espaço insuficiente", "Selecionado: " + bytes(known) + "\nLivre: " + bytes(free));',
    'alert("Espaço insuficiente", "Selecionado: " + bytes(known) + "\\nLivre: " + bytes(free));'
)
s = s.replace(
    '.setMessage(shortFolder(stat.name) + "\n\nAs músicas continuam no Google Drive e podem ser baixadas novamente.")',
    '.setMessage(shortFolder(stat.name) + "\\n\\nAs músicas continuam no Google Drive e podem ser baixadas novamente.")'
)

p.write_text(s, encoding='utf-8')
print('Java string escapes normalized')
