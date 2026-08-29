#!/usr/bin/env python3
from pathlib import Path

ROOT = Path('.')

def patch(path: str, old: str, new: str, label: str):
    p = ROOT / path
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'missing {label} in {path}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Make road APIs native-friendly: expired PHP cookie can be restored from the
# device token already sent by the Android ApiClient instead of returning HTML.
for path, old in [
    ('api/road_pack.php', "require __DIR__ . '/bootstrap.php';\nrequire_login();"),
    ('api/road_corridor_pack.php', "require __DIR__.'/bootstrap.php';\nrequire_login();"),
    ('api/road_state_pack.php', "require __DIR__.'/bootstrap.php';\nrequire_login();"),
    ('api/offline_state.php', "require __DIR__.'/bootstrap.php';\nrequire_login();"),
    ('api/radars.php', "require __DIR__ . '/bootstrap.php';\n$user = require_login();"),
]:
    if 'radars.php' == path:
        new = "require __DIR__ . '/bootstrap.php';\nrequire_once __DIR__ . '/native_auth.php';\n$user = native_restore_user_from_request(null);\nif (!$user) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);"
    elif " . '/bootstrap.php'" in old:
        new = "require __DIR__ . '/bootstrap.php';\nrequire_once __DIR__ . '/native_auth.php';\nif (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);"
    else:
        new = "require __DIR__.'/bootstrap.php';\nrequire_once __DIR__.'/native_auth.php';\nif (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);"
    patch(path, old, new, 'native auth')

# Promote the existing 500 MB control center to v3 without changing its
# metadata-only storage model.
patch('api/server_500mb.php', '// SERVER_500MB_V2:', '// SERVER_500MB_V3:', 'server marker')
patch('api/server_500mb.php', "'server_version'=>'500MB-v2'", "'server_version'=>'500MB-v3'", 'server version')
patch('admin_server.php', 'ESTRADAPLAY · SERVIDOR 500 MB V2', 'ESTRADAPLAY · SERVIDOR 500 MB V3', 'admin version')
patch('admin_server.php', '<div class="row"><a class="button secondary" href="admin.php">Painel Admin</a>', '<div class="row"><a class="button secondary" href="admin_reports.php">Reportes da Estrada</a><a class="button secondary" href="admin.php">Painel Admin</a>', 'admin reports link')

print('Server Central 500 MB v3 integration applied')
