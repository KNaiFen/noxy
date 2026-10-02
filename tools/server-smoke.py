"""Run the isolated dedicated integration harness using a prepared Gradle launch.

Requires build/smoke-launch.json with args and mod_classes from prepareServerRun.
The accepted EULA is copied from run/eula.txt; this script never changes consent.
"""
import json
import argparse
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
import tomllib

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--diagnostics', action='store_true', help='Enable production diagnostics and verify transfer/database log events')
parser.add_argument('--config-language', choices=['zh_cn', 'en_us'], default='zh_cn', help='Verify upgraded config comments in this language')
options = parser.parse_args()
run = root / 'build' / f'integration-{time.time_ns()}'
run.mkdir()
shutil.copyfile(root / 'run/eula.txt', run / 'eula.txt')
(run / 'server.properties').write_text(
    'server-ip=127.0.0.1\nserver-port=0\nview-distance=2\nsimulation-distance=2\n', encoding='utf-8')
if options.diagnostics:
    (run / 'config').mkdir()
    (run / 'config/voxy_distant.toml').write_text(f'configLanguage = "{options.config_language}"\n[debug]\nenabled = true\nverbose = true\nintervalSeconds = 1\n', encoding='utf-8')
launch = json.loads((root / 'build/smoke-launch.json').read_text())
env = os.environ.copy()
env['MOD_CLASSES'] = launch['mod_classes'] + ';distant_smoke%%' + str(root / 'build/classes/java/smoke') + ';distant_smoke%%' + str(root / 'build/resources/smoke')
args = [arg for arg in launch['args'] if not arg.startswith('-Dfml.modFolders=')]
args.insert(1, '-Dfml.modFolders=' + env['MOD_CLASSES'])
args.insert(1, '-Dfile.encoding=UTF-8')
process = subprocess.Popen(args, cwd=run, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
lines = queue.Queue()

def read():
    for line in process.stdout:
        lines.put(line)

threading.Thread(target=read, daemon=True).start()
passed = False
maintenance_stopped = False
deadline = time.monotonic() + 300
(root / 'logs').mkdir(exist_ok=True)
with (root / 'logs/integration-check.log').open('w', encoding='utf-8') as log:
    while process.poll() is None:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            line = ''
        if line:
            log.write(line)
            log.flush()
            if 'DISTANT_SMOKE_' in line or 'DISTANT_MAINTENANCE_' in line or 'Exception' in line or 'Error' in line:
                print(line, end='')
            passed |= 'DISTANT_SMOKE_PASS' in line
            maintenance_stopped |= 'DISTANT_MAINTENANCE_STOP_PASS' in line
        if time.monotonic() > deadline:
            process.stdin.write('voxydistant stats\nstop\n')
            process.stdin.flush()
            break
    try:
        result = process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.terminate()
        result = 124
    while not lines.empty():
        line = lines.get()
        log.write(line)
        maintenance_stopped |= 'DISTANT_MAINTENANCE_STOP_PASS' in line
print('INTEGRATION_PASS', passed and maintenance_stopped, 'EXIT', result)
if options.diagnostics:
    content = (root / 'logs/integration-check.log').read_text(encoding='utf-8')
    required = ['SERVER cache ', 'SERVER send_begin ', 'SERVER submitted ', 'SERVER receipt ',
                'SERVER player_interval ', 'SERVER encoded ', 'DATABASE interval ',
                'SERVER_BATCH_ENCODE ', 'SERVER throttle ', 'SERVER waits ', 'SERVER work_wait ']
    missing = [marker for marker in required if '[VD_DEBUG] ' + marker not in content]
    missing += [marker for marker in ('interval_avg_ms=', 'epoch_ms=') if marker not in content]
    if missing:
        raise AssertionError(f'Missing diagnostic events: {missing}')
    if 'DISTANT_CONFIG_UPGRADE_PASS' not in content:
        raise AssertionError('Config upgrade integration checks did not finish')
    config = (run / 'config/voxy_distant.toml').read_text(encoding='utf-8')
    values = tomllib.loads(config)
    assert values['configVersion'] == 7 and values['configLanguage'] == options.config_language
    assert values['client']['regionQueryWindow'] == 4
    assert ('同时等待服务端缓存核验' if options.config_language == 'zh_cn' else 'Outstanding region cache checks') in config
    assert values['server']['generation']['columnTimeoutSeconds'] == 60
    assert 'DISTANT_CACHE_ISOLATION_PASS' in content
    assert values['server']['generation']['pauseTriggerTicks'] == 20
    assert ('触发暂停需要连续' if options.config_language == 'zh_cn' else 'Consecutive ticks above pauseAboveMillis') in config
    assert len(list((run / 'config').glob('voxy_distant.toml.v0.*.bak'))) == 1
    print('CONFIG_MIGRATION_LANGUAGE_PASS', options.config_language)
    print('DIAGNOSTICS_PASS', run)
raise SystemExit(0 if passed and maintenance_stopped and result == 0 else 1)
