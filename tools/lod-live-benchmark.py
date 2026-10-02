"""Real Forge socket/client benchmark. Prepare launches and corpus as documented.

Only operates below build/benchmarks/lod-square256-cache128. Every run has an isolated
client cache. Server console is controlled through the child process stdin.
"""
import argparse
import csv
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import time
import socket
import uuid
import struct
import re
import psutil
from PIL import Image

root = Path(__file__).resolve().parents[1]
base = root / 'build/benchmarks/lod-square256-cache128'
parser = argparse.ArgumentParser()
parser.add_argument('--profiles', default='default')
parser.add_argument('--rounds', type=int, default=2)
parser.add_argument('--rate', choices=['default', 'full', 'custom'], default='custom', help='custom uses the five-player configuration; full retains the historical 8-thread/32-player-concurrency setup')
parser.add_argument('--submissions-per-second', type=int, help='CUSTOM submission limit, 1..10000; custom defaults to 8192, historical full to 1000')
parser.add_argument('--bandwidth-mbps', type=float, default=30.0, help='Server upload budget in Mbps, 0.01..1000; defaults to 30')
parser.add_argument('--radius', type=int, default=72)
parser.add_argument('--server-radius', type=int, help='Server permission radius; defaults to --radius')
parser.add_argument('--hold', action='store_true', help='Leave the final measured scene open until hold.txt is changed to stop')
parser.add_argument('--tag', default='', help='Suffix for a fresh run directory')
parser.add_argument('--acceptance', action='store_true', help='Functional diagnosis only; never writes benchmark complete.json')
parser.add_argument('--debug', action='store_true', help='Enable mod diagnostics on both sides')
parser.add_argument('--debug-overlay', action='store_true', help='Show the F3 overlay in acceptance screenshots')
parser.add_argument('--verbose', action='store_true', help='Include individual transfers and mesh decisions (requires --debug)')
parser.add_argument('--capture-check', action='store_true', help='With --acceptance, check title/world screenshots and first connection, without receiving LODs')
parser.add_argument('--reconnect-check', action='store_true', help='After first login, explicitly disconnect and reconnect before receiving LODs')
parser.add_argument('--client-cache', type=Path, help='Override the client source .voxy cache in the isolated copy; acceptance only')
parser.add_argument('--client-directory', type=Path, help='Isolated client source directory; defaults to the full-cache baseline')
parser.add_argument('--retain-run-games', action='store_true', help='Keep this run\'s client and server game directories to prepare a cache-reopen input')
parser.add_argument('--receive-memory', type=int, default=128, choices=[32, 64, 128, 256], help='Client receive budget in MiB')
parser.add_argument('--player-send-memory', type=int, default=32, help='Server per-player send queue in MiB; also caps advertised credit at four times this value')
parser.add_argument('--request-window', type=int, default=256, help='Client outstanding column window, 1..1024')
parser.add_argument('--max-batch-columns',type=int,default=16,help='Server maximum columns per compressed batch, 1..128')
parser.add_argument('--diagnostic-columns', type=int, help='Stop after this many naturally ordered columns; incomplete diagnosis, never an acceptance pass')
parser.add_argument('--visibility-diagnostic', action='store_true', help='Read GPU visibility once per second; acceptance only, affects timings')
parser.add_argument('--capture-interval', type=float, default=10, help='Seconds between loading screenshots')
parser.add_argument('--turn-check', action='store_true', help='After retention, capture four quarter turns')
parser.add_argument('--record-process', action='store_true', help='Record framebuffer at up to 5 FPS with timestamps; acceptance only')
parser.add_argument('--settings-check', action='store_true', help='Exercise OP settings UI before and during normal CUSTOM loading; acceptance only')
parser.add_argument('--import-first', action='store_true', help='Import the existing server world before fresh-client acceptance, measure load and exercise reload; disables missing-chunk generation')
parser.add_argument('--voxy-import', action='store_true', help='Copy the server world into an isolated singleplayer client and measure Voxy import current; acceptance only')
parser.add_argument('--voxy-threads', type=int, default=10, help='Voxy service threads for --voxy-import')
parser.add_argument('--voxy-dedicated', action='store_true', help='Disable Embeddium worker borrowing for a controlled Voxy thread count')
parser.add_argument('--server-directory', type=Path, help='Isolated server working directory for acceptance')
parser.add_argument('--exclusive-import-check', action='store_true', help='Pause an active client with import, reconnect, reload, then verify automatic recovery')
parser.add_argument('--cached-import-check', action='store_true', help='After full coverage, import once and check uninterrupted cached-client recovery')
parser.add_argument('--request-check', action='store_true', help='Run real client request-completion integration checks in a disposable scene, then exit')
parser.add_argument('--prediction-check', action='store_true', help='Measure a 22 block/second route with turn and stop, recording JFR')
parser.add_argument('--prediction-local', action='store_true', help='Use singleplayer generation for --voxy-import instead of native import')
parser.add_argument('--boundary-check', action='store_true', help='Move across a chunk boundary during reception and remain still for at least two minutes')
parser.add_argument('--travel-check', action='store_true', help='After settled load, teleport 56 chunks out and back, capturing each boundary and two minutes stationary')
parser.add_argument('--cruise-check', action='store_true', help='Continuous multi-boundary local client/server cruise')
parser.add_argument('--cruise-only', action='store_true', help='Start cruise after real LOD reception and first rendered geometry, without waiting for full-radius coverage')
parser.add_argument('--cruise-bounds', type=int, default=56, help='Axis cruise limit in chunks; keep this plus receive radius within the client cache radius')
parser.add_argument('--mask-check', action='store_true', help='Capture one fixed viewpoint with Voxy vanilla-depth masking on and off')
parser.add_argument('--mask-x', type=int, default=896)
parser.add_argument('--mask-z', type=int, default=0)
parser.add_argument('--mask-yaw', type=float, default=260.53766)
parser.add_argument('--settle-timeline', action='store_true', help='Capture a fixed viewpoint at 1, 5, 15, 30, 60, and 120 seconds after a cold client arrives')
options = parser.parse_args()
server_source = options.server_directory.resolve() if options.server_directory else base / 'server'
client_source = options.client_directory.resolve() if options.client_directory else base / 'client'
if not 1 <= options.max_batch_columns <= 128:
    parser.error('--max-batch-columns must be from 1 to 128')
if options.capture_interval <= 0:
    parser.error('--capture-interval must be positive')
if not 0.01 <= options.bandwidth_mbps <= 1000:
    parser.error('--bandwidth-mbps must be from 0.01 to 1000')
if not 1 <= options.request_window <= 1024:
    parser.error('--request-window must be from 1 to 1024')
if options.submissions_per_second is not None and (options.rate == 'default' or not 1 <= options.submissions_per_second <= 10000):
    parser.error('--submissions-per-second requires --rate custom/full and a value from 1 to 10000')
submission_rate = 2048 if options.rate == 'default' else (options.submissions_per_second or (8192 if options.rate == 'custom' else 1000))
if options.verbose and not options.debug:
    parser.error('--verbose requires --debug')
if (options.debug or options.debug_overlay or options.capture_check or options.reconnect_check or options.diagnostic_columns or options.visibility_diagnostic or options.turn_check or options.record_process or options.settings_check or options.import_first or options.voxy_import or options.exclusive_import_check or options.cached_import_check) and not options.acceptance:
    parser.error('--debug and --capture-check require --acceptance; diagnostic runs are excluded from benchmarks')
if options.acceptance and (options.rounds != 1 or ',' in options.profiles):
    parser.error('Acceptance requires one explicit --profiles value and --rounds 1')
if (options.request_check or options.boundary_check or options.travel_check or options.cruise_check or options.cruise_only) and not options.acceptance:
    parser.error('--request-check and --boundary-check require --acceptance')
if options.travel_check and options.diagnostic_columns:
    parser.error('--travel-check requires a complete scene; omit --diagnostic-columns')
if options.cruise_only and not options.cruise_check:
    parser.error('--cruise-only requires --cruise-check')
if (options.mask_check or options.settle_timeline) and not options.acceptance:
    parser.error('--mask-check and --settle-timeline require --acceptance')
if not 1 <= options.cruise_bounds <= 96:
    parser.error('--cruise-bounds must be from 1 to 96')
if options.client_cache and (not options.acceptance or not options.client_cache.is_dir()):
    parser.error('--client-cache requires --acceptance and an existing .voxy directory')
if not server_source.is_dir() or not client_source.is_dir():
    parser.error('Server and client source directories must exist')
if options.voxy_import and (options.import_first or not 1 <= options.voxy_threads <= 256):
    parser.error('--voxy-import requires 1..256 service threads and cannot use --import-first')
bands = {'default': ['32:0', '64:1', '96:2'], 'cruise': ['24:0','48:1','72:2','96:3','128:4'],
         '32L1-64L2-96L3-128L4': ['32:1', '64:2', '96:3', '128:4'], 'near32': ['32:0', '256:1']}
bands.update({f'l{i}': [f'256:{i}'] for i in range(5)})

def launch(kind, directory, output):
    spec = json.loads((base / f'{kind}-launch.json').read_text())
    folders = spec['mod_classes'].split(';distant_smoke')[0]
    folders += f';distant_smoke%%{root / "build/classes/java/smoke"};distant_smoke%%{root / "build/resources/smoke"}'
    args = [a for a in spec['args'] if not a.startswith(('-Dfml.modFolders=', '-Xmx', '-Xms'))]
    args[1:1] = ['-Xmx6G', '-Xms1G', '-Dfile.encoding=UTF-8', '-Dfml.modFolders=' + folders,
                 '-DvoxyDistant.lodBenchmark=true', '-DvoxyDistant.benchmarkOutput=' + str(output)]
    args += ['--mixin.config', 'distant_benchmark.mixins.json']
    args.insert(1, '-Dstdout.encoding=UTF-8')
    args.insert(1, '-Dstderr.encoding=UTF-8')
    if kind == 'client':
        args += ['--username', 'LodBench', '--width', '1280', '--height', '720']
        args.insert(1, '-DvoxyDistant.fixedCamera=true')
        if options.visibility_diagnostic:
            args.insert(1, '-DvoxyDistant.visibilityDiagnostic=true')
        if options.record_process:
            args.insert(1, '-DvoxyDistant.recordProcess=true')
    env = os.environ.copy()
    env['MOD_CLASSES'] = folders
    output.mkdir(parents=True)
    args.insert(1, '-Xlog:gc*:file=' + Path(os.path.relpath(output / 'gc.log', directory)).as_posix() + ':time,uptime,level,tags')
    log = (output / 'console.log').open('w', encoding='utf-8')
    process = subprocess.Popen(args, cwd=directory, env=env, stdin=subprocess.PIPE,
                               stdout=log, stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    (output / 'pid.txt').write_text(str(process.pid))
    return process, log


def command(process, text):
    process.stdin.write(text + '\n')
    process.stdin.flush()


def wait_log(process, output, marker, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        content = (output / 'console.log').read_text(encoding='utf-8', errors='replace')
        if marker in content:
            return
        if process.poll() is not None:
            raise RuntimeError(f'{output}: process exited before {marker}')
        time.sleep(1)
    raise TimeoutError(f'{output}: missing {marker}')


def client_command(process, output, command, seconds=30, **fields):
    identity = command + '-' + uuid.uuid4().hex
    temporary = output / 'client-command.tmp'
    temporary.write_text(json.dumps({'id': identity, 'command': command, **fields}), encoding='utf-8')
    temporary.replace(output / 'client-command.json')
    result = output / 'acks' / (identity + '.json')
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if result.exists():
            ack = json.loads(result.read_text(encoding='utf-8'))
            if ack['result'] != 'ok':
                raise RuntimeError(f'Client {command} failed: {ack}')
            if command == 'screenshot':
                with Image.open(ack['path']) as picture:
                    if picture.size != (ack['width'], ack['height']) or min(picture.size) <= 0:
                        raise AssertionError(f'Invalid screenshot dimensions: {ack}')
                    picture.verify()
            return ack
        if process.poll() is not None:
            raise RuntimeError(f'Client exited before acknowledging {command}')
        time.sleep(0.1)
    raise TimeoutError(f'No client acknowledgement for {identity}')


def latest_csv(path):
    with path.open('rb') as stream:
        stream.seek(0, 2)
        end = stream.tell()
        stream.seek(max(0, end - 8192))
        tail = stream.read().decode('utf-8').splitlines()
    if end > 8192:
        tail = tail[1:]
    if not tail:
        return None
    with path.open(encoding='utf-8') as stream:
        headers = next(csv.reader(stream))
    return dict(zip(headers, next(csv.reader([tail[-1]]))))


def latest_state(path):
    with path.open('rb') as stream:
        stream.seek(0, 2)
        end = stream.tell()
        stream.seek(max(0, end - 8192))
        tail = stream.read().splitlines()
    if end > 8192:
        tail = tail[1:]
    return json.loads(tail[-1]) if tail else None


def cruise_check(client,server,run,captures):
    edge=options.cruise_bounds
    waypoints=[[edge,0],[0,0],[0,edge],[0,0],[-edge,0],[0,0],[0,-edge],[0,0]]
    client_command(client,run/'client','cruise',waypoints=waypoints)
    route=[];positions=[];deadline=time.monotonic()+2700;last_segment=-1;last_check=0
    while time.monotonic()<deadline:
        if client.poll() is not None:raise RuntimeError(f'Client exited during cruise at segment {last_segment}')
        if server.poll() is not None:raise RuntimeError(f'Server exited during cruise at segment {last_segment}')
        state=latest_state(run/'client/states.jsonl')
        if state is None:time.sleep(1);continue
        if not state['in_world']:raise RuntimeError(f'Client left world during cruise: {state["screen"]}')
        segment=state['cruise_segment']
        if segment!=last_segment:
            route.append({'segment':segment,'epoch_ms':state['epoch_ms'],'x':state.get('x'),'z':state.get('z')})
            last_segment=segment
        if time.monotonic()-last_check>=5:
            server_position=latest_csv(run/'server/player-positions.csv')
            if server_position is None:raise AssertionError('Missing server position sample')
            age=state['epoch_ms']-int(server_position['epoch_ms'])
            distance=max(abs(state['x']-float(server_position['x'])),abs(state['z']-float(server_position['z'])))
            positions.append({'client_epoch_ms':state['epoch_ms'],'server_epoch_ms':int(server_position['epoch_ms']),
                'segment':segment,'client_x':state['x'],'client_z':state['z'],
                'server_x':float(server_position['x']),'server_z':float(server_position['z']),
                'lag_ms':age,'distance_blocks':distance})
            if abs(age)>5000 or distance>32:raise AssertionError(f'Client/server cruise position desynchronized: {positions[-1]}')
            last_check=time.monotonic()
        if state['cruise_done']:break
        time.sleep(1)
    else:raise TimeoutError('Cruise did not finish in 45 minutes')
    command(server,f'tp LodBench {edge*16} 180 0 0 15')
    client_command(client,run/'client','move-camera',x=edge*16,z=0)
    time.sleep(5)
    captures.append(client_command(client,run/'client','screenshot'))
    command(server,'tp LodBench 0 180 0 0 15')
    client_command(client,run/'client','move-camera',x=0,z=0)
    time.sleep(120)
    settled_shot=client_command(client,run/'client','screenshot');captures.append(settled_shot)
    turns=[]
    for _ in range(4):
        client_command(client,run/'client','turn');time.sleep(2)
        turns.append(client_command(client,run/'client','screenshot'))
    captures.extend(turns)
    (run/'cruise-check.json').write_text(json.dumps({'waypoints':waypoints,'route':route,
        'position_checks':positions,'stationary_seconds':120,'stationary_capture':settled_shot,
        'turn_captures':turns,'visual_verified':False},ensure_ascii=False,indent=2),encoding='utf-8')


def wait_state(process, output, key, seconds=120):
    deadline = time.monotonic() + seconds
    snapshots = iter([5, 15, 25])
    snapshot_at = next(snapshots)
    state = {}
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'Client exited while waiting for {key}')
        path = output / 'states.jsonl'
        if path.exists():
            records = path.read_bytes().split(b'\n')
            if len(records) > 1:
                state = json.loads(records[-2])
                if time.time()*1000 - state['epoch_ms'] < 5000 and state[key]:
                    return state
                if state['screen'] == 'DisconnectedScreen':
                    raise RuntimeError(f'First connection failed: {state}')
                if state['screen'] == 'LoadingErrorScreen':
                    raise RuntimeError(f'Mod loading stopped on a warning/error screen: {state}')
        elapsed = seconds - (deadline - time.monotonic())
        if options.acceptance and key == 'in_world' and elapsed >= snapshot_at:
            for kind in (['client'] if options.voxy_import else ['client', 'server']):
                spec = json.loads((base / f'{kind}-launch.json').read_text())
                diagnostic = Path(spec['args'][0]).with_name('jcmd.exe' if os.name == 'nt' else 'jcmd')
                pid = (output.parent / kind / 'pid.txt').read_text()
                result = subprocess.run([str(diagnostic), pid, 'Thread.print', '-l'], capture_output=True, text=True, timeout=10)
                (output.parent / f'{kind}-login-{snapshot_at}s-threads.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
                if result.returncode:
                    raise RuntimeError(f'{kind} login thread dump failed: {result.returncode}')
            snapshot_at = next(snapshots, float('inf'))
        time.sleep(0.2)
    raise TimeoutError(f'Client state {key} not ready: {state}')


def config_server(profile):
    preset = 'BALANCED' if options.rate == 'default' else 'CUSTOM'
    text = f'''[server]
generateMissingChunks = {str(not (options.import_first or options.exclusive_import_check or options.cached_import_check)).lower()}
radius = {options.server_radius or options.radius}
distanceBands = {json.dumps(bands[profile])}
compressionLevel = 1
maxBatchColumns = {options.max_batch_columns}
playerSendMemoryMiB = {options.player_send_memory}
totalSendMemoryMiB = 64
cacheMemoryMiB = 128
totalBandwidthMbps = {options.bandwidth_mbps}
[server.generation]
preset = "{preset}"
playerConcurrency = {32 if options.rate == 'full' else 8}
playerRequestQueueColumns = 256
queueColumns = 2048
snapshotMemoryMiB = 128
[server.generation.custom]
conversionThreads = {8 if options.rate == 'full' else 4}
generationConcurrency = 32
submissionsPerSecond = {submission_rate}
snapshotMillisPerTick = 2.0
conversionDutyCycle = 1.0
[debug]
enabled = {str(options.debug).lower()}
verbose = {str(options.verbose).lower()}
meshDetails = {str(options.verbose).lower()}
'''
    (server_home / 'config/voxy_distant.toml').write_text(text, encoding='utf-8')


def import_world(server, run):
    world = server_home / 'world'
    def slots():
        inventory = {}
        for path in sorted(world.rglob('region/r.*.*.mca')):
            with path.open('rb') as source:
                header = struct.unpack('>1024I', source.read(4096))
            inventory[str(path.relative_to(world))] = [i for i, offset in enumerate(header) if offset]
        return inventory
    before = slots()
    (run / 'region-slots-before.json').write_text(json.dumps(before))
    command(server, 'save-all flush\nvoxydistant stats')
    process = psutil.Process(server.pid)
    process.cpu_percent()
    start = time.monotonic()
    started = finished = reloaded = False
    report = {'baseline_epoch_ms': int(time.time()*1000), 'regions': len(before), 'occupied_slots': sum(map(len, before.values()))}
    with (run / 'import-process.csv').open('w', newline='', encoding='utf-8') as output:
        writer = csv.writer(output)
        writer.writerow(['epoch_ms', 'phase', 'cpu_percent', 'rss', 'private', 'read_bytes', 'write_bytes'])
        while time.monotonic() - start < 3600:
            if server.poll() is not None:
                raise RuntimeError('Server exited during import')
            elapsed = time.monotonic() - start
            memory = process.memory_info()
            io = process.io_counters()
            writer.writerow([int(time.time()*1000), 'import' if started and not finished else 'idle', process.cpu_percent(), memory.rss, memory.private, io.read_bytes, io.write_bytes])
            output.flush()
            if not started and elapsed >= 20:
                started = True
                report['import_start_epoch_ms'] = int(time.time()*1000)
                command(server, 'voxydistant import')
                print('IMPORT_STARTED', report, flush=True)
            if started and not finished:
                content = (run / 'server/console.log').read_text(encoding='utf-8', errors='replace')
                match = re.search(r'import 维护任务已结束：完成 (\d+)，跳过 (\d+)，失败 (\d+)', content)
                if match:
                    finished = True
                    report.update(completed=int(match[1]), skipped=int(match[2]), failed=int(match[3]), import_end_epoch_ms=int(time.time()*1000))
                    command(server, 'save-all flush\nvoxydistant stats')
                elif int(elapsed) % 5 == 0:
                    command(server, 'voxydistant stats')
                    progress = re.findall(r'维护任务 import[^\r\n]+', content)
                    if progress and int(elapsed) % 30 == 0:
                        print('IMPORT_PROGRESS', progress[-1], flush=True)
                if not reloaded and elapsed >= 50 and not finished:
                    config = server_home / 'config/voxy_distant.toml'
                    config.write_text(re.sub(r'cacheMemoryMiB = 128', 'cacheMemoryMiB = 96', config.read_text(encoding='utf-8')), encoding='utf-8')
                    command(server, 'voxydistant reload')
                    reloaded = True
                    report['reload_epoch_ms'] = int(time.time()*1000)
            if finished and time.time()*1000 - report['import_end_epoch_ms'] >= 20_000:
                after = slots()
                (run / 'region-slots-after-import.json').write_text(json.dumps(after))
                report['same_region_slots'] = before == after
                report['reload_applied'] = '配置已重新读取并应用' in content
                (run / 'import.json').write_text(json.dumps(report, indent=2))
                if report['failed'] or not report['completed'] or before != after or (reloaded and not report['reload_applied']):
                    raise AssertionError(f'Import validation failed: {report}')
                print('IMPORT_PASS', report, flush=True)
                return
            time.sleep(1)
    raise TimeoutError('Import did not finish in one hour')


def voxy_import_world():
    run = base / 'live' / f'vi-t{options.voxy_threads}-{options.tag or "run"}'
    run.mkdir(parents=True)
    game = run / 'game'
    shutil.copytree(client_source, game, ignore=shutil.ignore_patterns('logs', 'crash-reports', '.voxy'))
    source = server_source / 'world'
    target = game / 'saves/world'
    shutil.copytree(source, target, ignore=shutil.ignore_patterns('session.lock', '.voxy', 'voxy-distant'))
    regions = {}
    for path in sorted(source.rglob('region/r.*.*.mca')):
        relative = path.relative_to(source)
        with path.open('rb') as original, (target / relative).open('rb') as copied:
            digest = hashlib.file_digest(original, 'sha256').hexdigest()
            if hashlib.file_digest(copied, 'sha256').hexdigest() != digest:
                raise AssertionError(f'World copy differs: {relative}')
        regions[str(relative)] = digest
    (run / 'region-hashes.json').write_text(json.dumps(regions, indent=2))
    (game / 'config/voxy_distant.toml').write_text(f'[server]\nenabled = false\n[client]\nreceiveServerLods = false\n[client.localGeneration]\nenabled = false\n[debug]\nenabled = {str(options.debug).lower()}\nverbose = {str(options.verbose).lower()}\n', encoding='utf-8')
    if options.client_cache:
        shutil.copytree(options.client_cache,target/'voxy')
    config = json.loads((game / 'config/voxy-config.json').read_text())
    config.update(service_threads=options.voxy_threads, section_render_distance=options.radius/32,
                  dont_use_sodium_builder_threads=options.voxy_dedicated)
    (game / 'config/voxy-config.json').write_text(json.dumps(config, indent=2))
    if options.prediction_local:
        options_file=game / 'options.txt'
        text=options_file.read_text()
        text=re.sub(r'(?m)^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false', text)
        options_file.write_text(text)
    report = {'mode': 'Voxy native singleplayer import', 'service_threads': options.voxy_threads,
              'dedicated_threads_only': options.voxy_dedicated,
              'source_world': str(source), 'regions': len(regions), 'radius': options.radius,
              'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()}
    (run / 'run.json').write_text(json.dumps(report, indent=2))
    (run / 'excluded.json').write_text(json.dumps({'reason': 'Native importer comparison, not a network benchmark'}))
    (run / 'source.patch').write_bytes(subprocess.check_output(['git', 'diff', 'HEAD', '--binary'], cwd=root))
    client, log = launch('client', game, run / 'client')
    spec = json.loads((base / 'client-launch.json').read_text())
    jcmd = str(Path(spec['args'][0]).with_name('jcmd.exe' if os.name == 'nt' else 'jcmd'))
    output = run / 'client'
    try:
        wait_state(client, output, 'ready')
        client_command(client, output, 'open-world', world='world')
        wait_state(client, output, 'in_world', 120)
        time.sleep(20)
        report['captures'] = [client_command(client, output, 'screenshot')]
        subprocess.run([jcmd, str(client.pid), 'JFR.start', 'name=import', 'settings=profile', 'maxsize=512m'], check=True, capture_output=True, timeout=20)
        report['start_epoch_ms'] = int(time.time()*1000)
        if options.prediction_local:
            client_command(client,output,'settings-movement-open')
            report['settings_capture']=client_command(client,output,'screenshot')
            client_command(client,output,'settings-movement-check')
            report['start_ack']=client_command(client,output,'prediction-local')
            client_command(client,output,'cruise',waypoints=[[4,0],[4,4],[0,4],[0,0]],speed=22)
            time.sleep(3)
            active=client_command(client,output,'screenshot')
            motion_samples=[json.loads(line) for line in (output/'states.jsonl').read_text(encoding='utf-8').splitlines()]
            if not any(row['prediction_speed']>=21 and row['prediction_amount']>=.99 for row in motion_samples):raise AssertionError('Local prediction did not activate')
            time.sleep(30)
            moving=client_command(client,output,'screenshot')
            time.sleep(30)
            stopped=client_command(client,output,'screenshot')
            if not moving['cruise_done'] or stopped['prediction_amount']!=0 or stopped.get('local_completed',0)==0 or stopped.get('local_failure'):
                raise AssertionError('Local prediction did not stop')
            if moving['local_reason']=='范围内已完成' and moving['local_checks']!=stopped['local_checks']:raise AssertionError('Stationary local discovery rescanned')
            subprocess.run([jcmd,str(client.pid),'JFR.stop','name=import','filename='+str(output/'profile.jfr')],check=True,capture_output=True,timeout=30)
            report.update(mode='local prediction route',moving=moving,stopped=stopped)
            client_command(client,output,'move-camera',x=2048,z=0)
            time.sleep(2)
            client_command(client,output,'local-pause')
            time.sleep(3)
            paused=client_command(client,output,'screenshot')
            if paused['local_generating'] or paused['local_converting'] or paused['local_bytes']:raise AssertionError('Local pause did not release resources')
            report['paused']=paused
            client_command(client,output,'local-disable')
            time.sleep(3)
            released=client_command(client,output,'screenshot')
            if released['local_generating'] or released['local_converting'] or released['local_bytes']:raise AssertionError('Local disable did not release resources')
            report.update(active=active,disabled=released)
            (run / 'prediction.json').write_text(json.dumps(report,indent=2))
            print('PREDICTION_LOCAL_PASS',flush=True)
            return
        report['start_ack'] = client_command(client, output, 'voxy-import')
        process = psutil.Process(client.pid)
        process.cpu_percent()
        stable = None
        deadline = time.monotonic()+3600
        last_progress = last_capture = time.monotonic()
        with (run / 'process.csv').open('w', newline='', encoding='utf-8') as destination:
            writer = csv.writer(destination)
            writer.writerow(['epoch_ms', 'cpu_percent', 'rss', 'private', 'read_bytes', 'write_bytes'])
            while time.monotonic()<deadline:
                if client.poll() is not None:
                    raise RuntimeError('Client exited during Voxy import')
                memory = process.memory_info(); io = process.io_counters()
                writer.writerow([int(time.time()*1000), process.cpu_percent(), memory.rss, memory.private, io.read_bytes, io.write_bytes]); destination.flush()
                with (output / 'voxy-import.csv').open(encoding='utf-8') as source_csv:
                    records = list(csv.DictReader(source_csv))
                if records:
                    row = records[-1]
                    if time.monotonic()-last_progress>=20:
                        print('VOXY_IMPORT', options.voxy_threads, row, flush=True); last_progress=time.monotonic()
                    if row['running']=='false':
                        if 'finished_epoch_ms' not in report:
                            report['finished_epoch_ms']=int(row['epoch_ms']); report['final']=row
                        if int(row['saving_jobs'])==0:
                            stable=stable or time.monotonic()
                            if time.monotonic()-stable>=5:
                                break
                        else:
                            stable=None
                if time.monotonic()-last_capture>=30:
                    report['captures'].append(client_command(client, output, 'screenshot')); last_capture=time.monotonic()
                time.sleep(1)
            else:
                raise TimeoutError('Voxy import did not drain in one hour')
        report['drained_epoch_ms']=int(time.time()*1000)
        if int(report['final']['completed'])==0 or report['final']['completed']!=report['final']['total']:
            raise AssertionError(f'Incomplete Voxy import: {report["final"]}')
        subprocess.run([jcmd, str(client.pid), 'JFR.stop', 'name=import', 'filename='+str(output/'profile.jfr')], check=True, capture_output=True, timeout=30)
        time.sleep(10)
        report['captures'].append(client_command(client, output, 'screenshot'))
        print('VOXY_IMPORT_PASS', options.voxy_threads, report['final'], 'seconds', (report['finished_epoch_ms']-report['start_epoch_ms'])/1000, flush=True)
    finally:
        if client.poll() is None:
            client_command(client, output, 'quit', 10)
            client.wait(timeout=120)
        log.close()
        report['exit']=client.returncode
        report['exit_epoch_ms']=int(time.time()*1000)
        (run / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        if (game / 'logs').exists():
            shutil.copytree(game / 'logs', output / 'game-logs')
        if (game / 'crash-reports').exists():
            shutil.copytree(game / 'crash-reports', output / 'crash-reports')
        if not options.retain_run_games and client.returncode == 0:
            shutil.rmtree(game)


for round_index in range(options.rounds):
    profiles = options.profiles.split(',')
    for profile in profiles:
        if options.voxy_import:
            voxy_import_world()
            continue
        name = f'scene-{options.rate}-{profile}-r{round_index}-radius{options.radius}'
        if options.tag:
            name += '-' + options.tag
        # Refuse to share the scene with an existing server.
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 25576))
        run = base / 'live' / name
        run.mkdir(parents=True)
        server_home = run / 'server-game'
        shutil.copytree(server_source, server_home, ignore=shutil.ignore_patterns('session.lock', 'logs', 'crash-reports'))
        client_dir = run / 'game'
        shutil.copytree(client_source, client_dir, ignore=shutil.ignore_patterns('logs', 'crash-reports', *(['.voxy'] if options.client_cache else [])))
        if options.client_cache:
            shutil.copytree(options.client_cache, client_dir / '.voxy')
        (client_dir / 'config/voxy_distant.toml').write_text(f'''[client]
receiveServerLods = false
radius = {options.radius}
receiveMemoryMiB = {options.receive_memory}
requestWindowColumns = {options.request_window}
indexMemoryMiB = 256
processingDutyCycle = 1.0
[client.localGeneration]
enabled = false
[debug]
enabled = {str(options.debug).lower()}
verbose = {str(options.verbose).lower()}
meshDetails = {str(options.verbose).lower()}
''', encoding='utf-8')
        voxy = json.loads((client_dir / 'config/voxy-config.json').read_text())
        voxy['section_render_distance'] = options.radius / 32
        (client_dir / 'config/voxy-config.json').write_text(json.dumps(voxy))
        text = (client_dir / 'options.txt').read_text()
        text = text.replace('tutorialStep:movement', 'tutorialStep:none')
        if options.prediction_check:
            text=re.sub(r'(?m)^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false', text)
        (client_dir / 'options.txt').write_text(text)
        original_server_config = (server_home / 'config/voxy_distant.toml').read_bytes()
        config_server(profile)
        (run / 'original-server-config.toml').write_bytes(original_server_config)
        (run / 'server-config.toml').write_bytes((server_home / 'config/voxy_distant.toml').read_bytes())
        (run / 'client-config.toml').write_bytes((client_dir / 'config/voxy_distant.toml').read_bytes())
        (run / 'run.json').write_text(json.dumps({'mode': 'acceptance' if options.acceptance else 'benchmark',
            'profile': profile, 'bands': bands[profile], 'radius': options.radius, 'debug': options.debug,
            'receive_memory_mib': options.receive_memory, 'player_send_memory_mib': options.player_send_memory,
            'request_window_columns': options.request_window,
            'max_batch_columns': options.max_batch_columns,
            'submissions_per_second': submission_rate,
            'bandwidth_mbps': options.bandwidth_mbps,
            'visibility_diagnostic': options.visibility_diagnostic, 'capture_interval_seconds': options.capture_interval,
            'turn_check': options.turn_check,
            'record_process': options.record_process,
            'import_first': options.import_first,
            'client_source': str(client_source),
            'client_cache_source': str(options.client_cache.resolve()) if options.client_cache else str(client_source / '.voxy') if (client_source / '.voxy').is_dir() else None,
            'server_source': str(server_source),
            'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
            'working_tree': subprocess.check_output(['git', 'status', '--short'], cwd=root, text=True)}), encoding='utf-8')
        (run / 'source.patch').write_bytes(subprocess.check_output(['git', 'diff', 'HEAD', '--binary'], cwd=root))
        artifacts = [*sorted((root / 'build/libs').glob('*.jar')),
                     *sorted((root / 'build/classes/java/main').rglob('*.class')),
                     *sorted((root / 'build/classes/java/smoke').rglob('*.class'))]
        (run / 'build-hashes.json').write_text(json.dumps({str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts}, indent=2))
        if options.acceptance:
            (run / 'excluded.json').write_text(json.dumps({'reason': 'Functional acceptance / diagnostics; not a performance benchmark'}))
        server, server_log = launch('server', server_home, run / 'server')
        client = client_log = None
        memory_samples = None
        try:
            wait_log(server, run / 'server', 'Done (')
            if options.import_first:
                import_world(server, run)
            client, client_log = launch('client', client_dir, run / 'client')
            wait_state(client, run / 'client', 'ready')
            captures = [client_command(client, run / 'client', 'screenshot')]
            client_command(client, run / 'client', 'connect')
            wait_state(client, run / 'client', 'in_world', 45)
            wait_state(client, run / 'client', 'lod_hello', 30)
            if options.debug_overlay:
                debug_ack = client_command(client, run / 'client', 'debug-on')
                if not debug_ack['debug_overlay']:
                    raise AssertionError(f'{name}: F3 overlay was not enabled')
            command(server, 'gamemode spectator LodBench\ntp LodBench 0 180 0 0 15\ntime set noon\ngamerule doDaylightCycle false\nweather clear\ngamerule doWeatherCycle false')
            time.sleep(2 if options.capture_check else 20)
            captures.append(client_command(client, run / 'client', 'screenshot'))
            if options.reconnect_check:
                client_command(client, run / 'client', 'disconnect')
                wait_state(client, run / 'client', 'ready')
                captures.append(client_command(client, run / 'client', 'screenshot'))
                client_command(client, run / 'client', 'connect')
                wait_state(client, run / 'client', 'in_world', 45)
                wait_state(client, run / 'client', 'lod_hello', 30)
                if options.debug_overlay:
                    debug_ack = client_command(client, run / 'client', 'debug-on')
                    if not debug_ack['debug_overlay']:
                        raise AssertionError(f'{name}: F3 overlay was not enabled after reconnect')
                time.sleep(10)
                captures.append(client_command(client, run / 'client', 'screenshot'))
            if options.capture_check:
                captures.append(client_command(client, run / 'client', 'screenshot'))
                (run / 'capture-check.json').write_text(json.dumps({'first_connection': True, 'captures': captures, 'visual_verified': False}), encoding='utf-8')
                print('CAPTURE_CHECK_PASS', name, flush=True)
                continue
            if options.request_check:
                result=client_command(client,run/'client','request-check',120)
                (run/'request-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
                print('REQUEST_COMPLETION_PASS',name,flush=True)
                continue
            if options.settings_check:
                import runpy
                settings_check = runpy.run_path(str(root / 'tools/lod-settings-check.py'))['check_settings']
                settings_check(client, server, run / 'client', server_home / 'config/voxy_distant.toml', client_command, command)
            start = time.time()
            receive_ack = client_command(client, run / 'client', 'receive')
            if options.prediction_check:
                spec=json.loads((base/'client-launch.json').read_text());jcmd=str(Path(spec['args'][0]).with_name('jcmd.exe' if os.name=='nt' else 'jcmd'))
                subprocess.run([jcmd,str(client.pid),'JFR.start','name=prediction','settings=profile'],check=True,capture_output=True,timeout=20)
                client_command(client,run/'client','cruise',waypoints=[[8,0],[8,8],[0,8],[0,0]],speed=22)
                time.sleep(65)
                stopped=client_command(client,run/'client','screenshot')
                if not stopped['cruise_done'] or stopped['prediction_amount']!=0:raise AssertionError('Prediction route did not stop')
                time.sleep(30)
                subprocess.run([jcmd,str(client.pid),'JFR.stop','name=prediction','filename='+str(run/'client/prediction.jfr')],check=True,capture_output=True,timeout=30)
                (run/'prediction.json').write_text(json.dumps({'route_speed':22,'stopped':stopped,'final':latest_state(run/'client/states.jsonl')},ensure_ascii=False,indent=2),encoding='utf-8')
                print('PREDICTION_REMOTE_PASS',name,flush=True)
                continue
            if options.boundary_check:
                time.sleep(2)
                command(server,'tp LodBench 16 180 0 0 15')
                client_command(client,run/'client','move-camera',x=16,z=0)
                captures.append(client_command(client,run/'client','screenshot'))
            if receive_ack['receive_memory_mib'] != options.receive_memory:
                raise AssertionError(f'Unexpected client receive budget: {receive_ack}')
            if receive_ack['request_window_columns'] != options.request_window:
                raise AssertionError(f'Unexpected client request window: {receive_ack}')
            if options.settings_check:
                settings_check(client, server, run / 'client', server_home / 'config/voxy_distant.toml', client_command, command, receive=True)
            if options.cruise_only:
                deadline=time.monotonic()+120
                while time.monotonic()<deadline:
                    if client.poll() is not None:raise RuntimeError('Client exited before first LOD geometry')
                    sample=latest_csv(run/'client/client-samples.csv')
                    if sample and int(sample['geometry_sections'])>0:break
                    time.sleep(1)
                else:raise TimeoutError('No rendered LOD geometry before cruise')
                cruise_check(client,server,run,captures)
            if options.mask_check:
                command(server,f'tp LodBench {options.mask_x} 180 {options.mask_z} {options.mask_yaw} 15')
                client_command(client,run/'client','move-camera',x=options.mask_x,z=options.mask_z,yaw=options.mask_yaw)
                time.sleep(120)
                normal=client_command(client,run/'client','screenshot');captures.append(normal)
                client_command(client,run/'client','mask-off')
                time.sleep(3)
                bypass=client_command(client,run/'client','screenshot');captures.append(bypass)
                client_command(client,run/'client','mask-on')
                time.sleep(3)
                restored=client_command(client,run/'client','screenshot');captures.append(restored)
                (run/'mask-check.json').write_text(json.dumps({'normal':normal,'mask_bypassed':bypass,
                    'mask_restored':restored,
                    'stationary_seconds':120,'visual_verified':False},ensure_ascii=False,indent=2),encoding='utf-8')
            if options.settle_timeline:
                command(server,f'tp LodBench {options.mask_x} 180 {options.mask_z} {options.mask_yaw} 15')
                client_command(client,run/'client','move-camera',x=options.mask_x,z=options.mask_z,yaw=options.mask_yaw)
                arrived=time.monotonic();timeline=[]
                for second in (1,5,15,30,60,120):
                    time.sleep(max(0,arrived+second-time.monotonic()))
                    timeline.append({'seconds':second,'capture':client_command(client,run/'client','screenshot')})
                (run/'settle-timeline.json').write_text(json.dumps({'x':options.mask_x,'z':options.mask_z,
                    'yaw':options.mask_yaw,'timeline':timeline,'visual_verified':False},ensure_ascii=False,indent=2),encoding='utf-8')
            if options.exclusive_import_check:
                time.sleep(8)
                captures.append(client_command(client,run/'client','screenshot'))
                command(server,'voxydistant import')
                time.sleep(8)
                paused=client_command(client,run/'client','screenshot');captures.append(paused)
                if paused['status']!='服务端正在导入远景':raise AssertionError(paused)
                # Stop old fragments/request retries; ordinary vanilla traffic is allowed.
                rows=list(csv.DictReader((run/'server/server-samples.csv').open(encoding='utf-8')))
                sent=int(rows[-1]['sent_bytes'])
                command(server,'execute in minecraft:the_nether run tp LodBench 0 180 0')
                time.sleep(4)
                nether=client_command(client,run/'client','screenshot');captures.append(nether)
                if nether['status']!='服务端正在导入远景':raise AssertionError(nether)
                command(server,'execute in minecraft:overworld run tp LodBench 0 180 0')
                time.sleep(4)
                client_command(client,run/'client','disconnect');wait_state(client,run/'client','ready')
                client_command(client,run/'client','connect');wait_state(client,run/'client','in_world',60)
                time.sleep(3)
                joined=client_command(client,run/'client','screenshot');captures.append(joined)
                if joined['status']!='服务端正在导入远景':raise AssertionError(joined)
                samples_before=list(csv.DictReader((run/'server/server-samples.csv').open(encoding='utf-8')))
                if int(samples_before[-1]['sent_bytes'])!=sent:raise AssertionError('LOD payload sent during import')
                command(server,'forceload add 512 512')
                time.sleep(2)
                command(server,'setblock 512 319 512 minecraft:diamond_block\nforceload remove 512 512')
                command(server,'voxydistant reload')
                deadline=time.monotonic()+1800
                while 'import 维护任务已结束' not in (run/'server/console.log').read_text(encoding='utf-8',errors='replace'):
                    if time.monotonic()>deadline:raise TimeoutError('Exclusive import')
                    time.sleep(1)
                command(server,'gamemode spectator LodBench\ntp LodBench 0 180 0 0 15')
                rows=list(csv.DictReader((run/'server/server-samples.csv').open(encoding='utf-8')))
                (run/'exclusive-check.json').write_text(json.dumps({'paused':paused,'nether_paused':nether,'joined_paused':joined,'sent_at_pause':sent,'resumed':True},ensure_ascii=False,indent=2),encoding='utf-8')
                start=time.time()
            if options.cruise_only or options.mask_check or options.settle_timeline:
                (run/'acceptance.json').write_text(json.dumps({'profile':profile,'bands':bands[profile],
                    'cruise_only':options.cruise_only,'mask_check':options.mask_check,'settle_timeline':options.settle_timeline,
                    'captures':captures,'visual_verified':False}),encoding='utf-8')
                print('DIAGNOSTIC_CAPTURED',name,flush=True)
                continue
            expected = sum(x*x+z*z <= options.radius*options.radius for z in range(-options.radius, options.radius+1) for x in range(-options.radius, options.radius+1))
            deadline = time.monotonic() + max(600, expected / 20)
            last_progress = 0
            settled = None
            previous_applied = 0
            last_capture = time.monotonic()
            memory_samples = (run / 'process-memory.csv').open('w', newline='', encoding='utf-8')
            memory_writer = csv.writer(memory_samples)
            memory_writer.writerow(['epoch_ns', 'client_rss', 'client_private', 'server_rss', 'server_private'])
            while time.monotonic() < deadline:
                if client.poll() is not None:
                    raise RuntimeError(f'Client exited in {name}')
                client_memory = psutil.Process(client.pid).memory_info()
                server_memory = psutil.Process(server.pid).memory_info()
                memory_writer.writerow([time.time_ns(), client_memory.rss, client_memory.private, server_memory.rss, server_memory.private])
                memory_samples.flush()
                if time.monotonic() - last_capture >= options.capture_interval:
                    captures.append(client_command(client, run / 'client', 'screenshot'))
                    last_capture = time.monotonic()
                with (run / 'client/client-samples.csv').open(encoding='utf-8') as source:
                    samples = list(csv.DictReader(source))
                if samples:
                    row = samples[-1]
                    applied, pending, queued, active = (int(row[k]) for k in ['applied', 'pending', 'queue', 'active'])
                    if applied < previous_applied:
                        raise AssertionError(f'{name}: receive session reset during measurement')
                    previous_applied = applied
                    if options.diagnostic_columns and applied >= options.diagnostic_columns:
                        captures.append(client_command(client, run / 'client', 'screenshot'))
                        (run / 'pipeline-window.json').write_text(json.dumps({'columns': applied, 'elapsed_seconds': time.time()-start,
                            'complete': False, 'visual_verified': False, 'captures': captures}), encoding='utf-8')
                        break
                    if time.monotonic() - last_progress > 30:
                        print(name, 'applied', applied, 'pending', pending, 'queue', queued, flush=True)
                        last_progress = time.monotonic()
                    idle = (pending == queued == active == int(row['full_retry']) == int(row['memory']) == int(row['retries']) == 0
                            and row['scanning'] == 'false' and int(row['invalid']) == 0 and (applied > 0 or options.client_cache or (client_dir/'.voxy').is_dir()))
                    if idle:
                        settled = settled or time.monotonic()
                        if time.monotonic() - settled >= 5:
                            break
                    else:
                        settled = None
                time.sleep(1)
            else:
                raise TimeoutError(f'{name}: incomplete reception')
            elapsed = time.time() - start
            memory_samples.close()
            expected_receive = options.receive_memory * 1048576
            expected_snapshot = min(32 * 1048576, expected_receive // 4)
            expected_credit = expected_receive - expected_snapshot
            observed_client = {tuple(int(r[k]) for k in ['receive_limit', 'snapshot_limit', 'advertised_credit'])
                               for r in samples if int(r['receive_limit']) > 0}
            with (run / 'server/server-samples.csv').open(encoding='utf-8') as source:
                observed_server = {(int(r['players']), int(r['effective_credit'])) for r in csv.DictReader(source)
                                   if int(r['effective_credit']) > 0}
            expected_server = {(1, min(expected_credit, options.player_send_memory * 1048576 * 4))}
            if options.settings_check:
                expected_server.add((1, min(expected_credit, 8 * 1048576 * 4)))
            if observed_client != {(expected_receive, expected_snapshot, expected_credit)} or observed_server != expected_server:
                raise AssertionError(f'Unexpected negotiated budgets: client={observed_client}, server={observed_server}')
            (run / 'observed-budgets.json').write_text(json.dumps({'client': sorted(observed_client), 'server': sorted(observed_server)}), encoding='utf-8')
            if options.diagnostic_columns:
                print('DIAGNOSTIC_WINDOW_CAPTURED', name, applied, flush=True)
                continue
            if options.cached_import_check:
                # Repeat with a complete, existing cache and no reconnect at any point.
                console=run/'server/console.log';offset=len(console.read_text(encoding='utf-8',errors='replace'))
                previous=applied;command(server,'voxydistant import');time.sleep(5)
                retained=client_command(client,run/'client','screenshot');captures.append(retained)
                if retained['status']!='服务端正在导入远景':raise AssertionError(retained)
                command(server,'forceload add 512 512');time.sleep(2)
                command(server,'setblock 512 319 512 minecraft:diamond_block\nforceload remove 512 512')
                deadline=time.monotonic()+1800
                while 'import 维护任务已结束' not in console.read_text(encoding='utf-8',errors='replace')[offset:]:
                    if time.monotonic()>deadline:raise TimeoutError('Cached-client import')
                    time.sleep(1)
                stable=None
                while time.monotonic()<deadline:
                    records=list(csv.DictReader((run/'client/client-samples.csv').open(encoding='utf-8')));row=records[-1]
                    idle=(all(int(row[k])==0 for k in ['pending','queue','active','full_retry','memory','invalid']) and row['scanning']=='false' and int(row['applied'])>previous)
                    stable=(stable or time.monotonic()) if idle else None
                    if stable and time.monotonic()-stable>=5:break
                    time.sleep(1)
                else:raise TimeoutError('Cached-client automatic recovery')
                refreshed=client_command(client,run/'client','screenshot');captures.append(refreshed)
                (run/'cached-recovery.json').write_text(json.dumps({'retained':retained,'refreshed':refreshed,'before_applied':previous,'after_applied':int(row['applied']),'reconnected':False},ensure_ascii=False,indent=2),encoding='utf-8')
            (run / 'client/coverage.json').unlink(missing_ok=True)
            client_command(client, run / 'client', 'audit')
            wait_log_deadline = time.monotonic() + 90
            while not (run / 'client/coverage.json').exists():
                if time.monotonic() > wait_log_deadline:
                    raise TimeoutError('Coverage audit did not finish')
                time.sleep(1)
            coverage = json.loads((run / 'client/coverage.json').read_text())
            if sum(coverage['level_counts']) != expected or coverage['level_counts'][5] != 0 or coverage['insufficient'] != 0:
                raise AssertionError(f'Incomplete continuous scene: {coverage}')
            if int(samples[-1]['geometry_sections']) == 0:
                raise AssertionError(f'{name}: visual acceptance has no rendered LOD geometry')
            if options.cruise_check:
                cruise_check(client,server,run,captures)
            if options.travel_check:
                travel=[]
                for blocks in (56*16,0):
                    command(server,f'tp LodBench {blocks} 180 0 0 15')
                    client_command(client,run/'client','move-camera',x=blocks,z=0)
                    time.sleep(5)
                    shot=client_command(client,run/'client','screenshot')
                    captures.append(shot)
                    travel.append({'x':blocks,'capture':shot})
                time.sleep(120)
                settled_shot=client_command(client,run/'client','screenshot')
                captures.append(settled_shot)
                (run/'travel-check.json').write_text(json.dumps({'out_and_back':travel,'stationary_seconds':120,'stationary_capture':settled_shot,'visual_verified':False},ensure_ascii=False,indent=2),encoding='utf-8')
            time.sleep(30)
            steady_start = time.time_ns()
            captures.append(client_command(client, run / 'client', 'screenshot'))
            time.sleep(120 if options.boundary_check else 60)
            captures.append(client_command(client, run / 'client', 'screenshot'))
            if options.turn_check:
                for _ in range(4):
                    client_command(client, run / 'client', 'turn')
                    time.sleep(3)
                    captures.append(client_command(client, run / 'client', 'screenshot'))
            (run / ('acceptance.json' if options.acceptance else 'complete.json')).write_text(json.dumps({'profile': profile, 'rate': options.rate,
                'round': round_index, 'columns': applied, 'elapsed_seconds': elapsed,
                'continuous': True, 'radius': options.radius, 'steady_start_epoch_ns': steady_start,
                'bands': bands[profile], 'captures': captures, 'visual_verified': False}))
            print('ACCEPTANCE_CAPTURED' if options.acceptance else 'LIVE_CAPTURED', name, 'seconds', elapsed, flush=True)
            if options.hold:
                hold = run / 'hold.txt'
                hold.write_text('hold')
                while hold.read_text().strip() != 'stop':
                    if client.poll() is not None:
                        break
                    time.sleep(1)
        except Exception as error:
            failure = {'error': repr(error), 'epoch_ms': int(time.time()*1000)}
            if client is not None and client.poll() is None:
                try:
                    failure['capture'] = client_command(client, run / 'client', 'screenshot', 10)
                except Exception as capture_error:
                    failure['capture_error'] = repr(capture_error)
            for kind, process in [('client', client), ('server', server)]:
                if process is not None and process.poll() is None:
                    spec = json.loads((base / f'{kind}-launch.json').read_text())
                    diagnostic = Path(spec['args'][0]).with_name('jcmd.exe' if os.name == 'nt' else 'jcmd')
                    try:
                        result = subprocess.run([str(diagnostic), str(process.pid), 'Thread.print', '-l'], capture_output=True, text=True, timeout=15)
                        (run / f'{kind}-failure-threads.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
                        failure[kind + '_thread_dump_exit'] = result.returncode
                    except (OSError, subprocess.TimeoutExpired) as diagnostic_error:
                        failure[kind + '_thread_dump_error'] = repr(diagnostic_error)
            (run / 'failure.json').write_text(json.dumps(failure), encoding='utf-8')
            raise
        finally:
            if memory_samples is not None:
                memory_samples.close()
            try:
                if client is not None and client.poll() is None:
                    try:
                        client_command(client, run / 'client', 'quit', 10)
                        client.wait(timeout=90)
                    except (RuntimeError, TimeoutError, subprocess.TimeoutExpired) as stop_error:
                        print('CLIENT_STOP_ERROR', repr(stop_error), flush=True)
                        if client.poll() is None:
                            client.terminate()
                            client.wait(timeout=30)
            finally:
                if client_log:
                    client_log.close()
                try:
                    if server.poll() is None:
                        command(server, 'stop')
                        server.wait(timeout=90)
                finally:
                    server_log.close()
                    (run / 'exits.json').write_text(json.dumps({'client_exit': client.returncode if client else None, 'server_exit': server.returncode}), encoding='utf-8')
                    if (server_home / 'logs').exists():
                        shutil.copytree(server_home / 'logs', run / 'server/game-logs')
                    if (client_dir / 'logs').exists():
                        shutil.copytree(client_dir / 'logs', run / 'client/game-logs')
                    for kind, directory in [('server', server_home), ('client', client_dir)]:
                        if (directory / 'crash-reports').exists():
                            shutil.copytree(directory / 'crash-reports', run / kind / 'crash-reports')
                    if server.poll() is not None:
                        (run / 'final-server-config.toml').write_bytes((server_home / 'config/voxy_distant.toml').read_bytes())
                    if not options.retain_run_games and client is not None and client.returncode == server.returncode == 0:
                        shutil.rmtree(client_dir)
                        shutil.rmtree(server_home)
