#!/usr/bin/env python3
from pathlib import Path

def rw(path,fn):
    p=Path(path);s=p.read_text(encoding='utf-8');p.write_text(fn(s),encoding='utf-8')

def server500(s):
    s=s.replace('SERVER_500MB_V3','SERVER_500MB_V4').replace('500MB-v3','500MB-v4')
    # Keep collective telemetry bounded on the 500 MB host.
    anchor="""        try {\n            db()->exec(\"DELETE FROM drive_sync_history WHERE finished_at < DATE_SUB(NOW(), INTERVAL 60 DAY)\");"""
    if anchor in s and 'road_collective_events WHERE created_at' not in s:
        s=s.replace(anchor,"""        try {\n            db()->exec(\"DELETE FROM road_collective_events WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY)\");\n        } catch (Throwable $ignored) {}\n\n"""+anchor,1)
    return s
rw('api/server_500mb.php',server500)
rw('api/server_intelligent.php',lambda s:s.replace("500MB-v3","500MB-v4"))
# Add a link to the new panel without making it mandatory for the admin shell.
p=Path('admin_server.php');s=p.read_text(encoding='utf-8')
if 'admin_collective.php' not in s:
    marker='admin_reports.php'
    pos=s.find(marker)
    if pos>=0:
        end=s.find('</a>',pos)
        if end>=0:
            end+=4
            s=s[:end]+' <a class="button secondary" href="admin_collective.php">Inteligência Coletiva</a>'+s[end:]
p.write_text(s.replace('500 MB V3','500 MB V4').replace('500MB V3','500MB V4'),encoding='utf-8')
print('server central v4 prepared')
