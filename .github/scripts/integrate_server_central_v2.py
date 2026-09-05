from pathlib import Path


def replace_once(path: str, old: str, new: str, marker: str) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    if marker in s:
        return
    if old not in s:
        raise SystemExit(f"anchor not found: {path} / {marker}")
    p.write_text(s.replace(old, new, 1), encoding="utf-8")


replace_once(
    "admin.php",
    '<div class="row"><a class="button secondary" href="admin_radares.php">',
    '<div class="row"><a class="button secondary" href="admin_server.php">Central do Servidor</a><a class="button secondary" href="admin_radares.php">',
    'href="admin_server.php"',
)

replace_once(
    "api/native_app.php",
    "return ['tracks'=>$tracks,'active_roots'=>$roots,'last_sync'=>app_setting('native_library_last_sync',''),'db_driver'=>(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)];",
    "return ['tracks'=>$tracks,'active_roots'=>$roots,'last_sync'=>app_setting('native_library_last_sync',''),'catalog_version'=>server_catalog_version(),'catalog_changed_at'=>app_setting('catalog_changed_at',''),'db_driver'=>(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)];",
    "'catalog_version'=>server_catalog_version()",
)

replace_once(
    "admin_server.php",
    "if (($_GET['format'] ?? '') === 'json') {\n    json_response(['ok'=>true,'diagnostic'=>$diag,'folders'=>$folders,'history'=>$history]);\n}",
    """if (($_GET['format'] ?? '') === 'json') {
    $safeDiag = $diag;
    if (isset($safeDiag['health']) && is_array($safeDiag['health'])) unset($safeDiag['health']['cron_url']);
    $safeFolders = array_map(static function(array $row): array {
        unset($row['folder_id']);
        return $row;
    }, $folders);
    json_response(['ok'=>true,'diagnostic'=>$safeDiag,'folders'=>$safeFolders,'history'=>$history]);
}""",
    "$safeFolders",
)

print("Server Central v2 integration ready")
