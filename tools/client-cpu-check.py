"""Compare warm client reception and 20-block strafes with 2,000 active pigs."""
import csv
import json
import math
import os
import re
from pathlib import Path
import shutil
import socket
import subprocess
import time

root = Path(__file__).resolve().parents[1]
base = root / 'build/benchmarks/lod-square256-cache128'
run = root / ('build/client-cpu-check-' + str(time.time_ns()))
run.mkdir(exist_ok=False)
with socket.socket() as probe:
    probe.bind(('127.0.0.1', 25576))
server_home = run / 'server-game'
shutil.copytree(base / 'server', server_home, ignore=shutil.ignore_patterns('session.lock', 'logs', 'crash-reports'))
config = '''[client]
receiveServerLods = false
radius = 96
[client.localGeneration]
enabled = false
[server]
enabled = true
radius = 128
distanceBands = ["32:0", "64:1", "96:2"]
generateMissingChunks = false
'''
(server_home / 'config/voxy_distant.toml').write_text(config, encoding='utf-8')


def launch(kind, home, output, variant):
    spec = json.loads((base / f'{kind}-launch.json').read_text())
    folders = spec['mod_classes'].split(';distant_smoke')[0]
    args = [a for a in spec['args'] if not a.startswith(('-Dfml.modFolders=', '-Xmx', '-Xms'))]
    if variant == 'baseline':
        args.insert(1, '-DvoxyDistant.cpuBaseline=true')
        for name in ('classes/java/main', 'resources/main'):
            before = str(root / 'build' / name)
            after = str(root / 'build/client-cpu-baseline/build' / name)
            folders = folders.replace(before, after)
            args = [a.replace(before, after) for a in args]
    folders += f';distant_smoke%%{root / "build/classes/java/smoke"};distant_smoke%%{root / "build/resources/smoke"}'
    args[1:1] = ['-Xmx6G', '-Xms1G', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dfml.modFolders=' + folders,
                 '-DvoxyDistant.lodBenchmark=true', '-DvoxyDistant.benchmarkOutput=' + str(output)]
    args += ['--mixin.config', 'distant_benchmark.mixins.json']
    if kind == 'client':
        args.insert(1, '-DvoxyDistant.cpuCheck=true')
        args += ['--username', 'LodBench', '--width', '1280', '--height', '720']
    output.mkdir()
    log = (output / 'console.log').open('w', encoding='utf-8')
    env = os.environ.copy()
    env['MOD_CLASSES'] = folders
    process = subprocess.Popen(args, cwd=home, env=env, stdin=subprocess.PIPE, stdout=log,
                               stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    return process, log


def server_command(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def server_position(selector):
    console = run / 'server/console.log'
    offset = len(console.read_text(encoding='utf-8'))
    server_command('data get entity ' + selector + ' Pos')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        text = console.read_text(encoding='utf-8')[offset:]
        match = re.search(r'\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]', text)
        if match:
            return [float(value) for value in match.groups()]
        time.sleep(.1)
    raise TimeoutError('Server did not report position: ' + selector)


def state(output):
    lines = (output / 'states.jsonl').read_text(encoding='utf-8').splitlines()
    return json.loads(lines[-1])


def wait_state(process, output, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'Client exited: {output}')
        if (output / 'states.jsonl').exists() and predicate(state(output)):
            return state(output)
        time.sleep(.5)
    raise TimeoutError(f'Client state: {output}')


def command(process, output, name, **values):
    identity = name + '-' + str(time.time_ns())
    temp = output / 'client-command.tmp'
    temp.write_text(json.dumps(dict(id=identity, command=name, **values)))
    temp.replace(output / 'client-command.json')
    ack = output / 'acks' / (identity + '.json')
    deadline = time.monotonic() + 120
    while not ack.exists():
        if process.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError(f'Command did not complete: {name}')
        time.sleep(.1)
    result = json.loads(ack.read_text(encoding='utf-8'))
    if result['result'] != 'ok':
        raise AssertionError(result)
    return result


def measure(process, output, label, moving=False):
    command(process, output, 'screenshot')
    if moving:
        command(process, output, 'cruise', speed=4.3, waypoints=[[-1.25, .0625], [1.25, .0625], [0, .0625]])
    start = state(output)['nano_time']
    time.sleep(20)
    end = state(output)['nano_time']
    frames = [int(row['frame_ns']) / 1e6 for row in csv.DictReader((output / 'frames.csv').open(encoding='utf-8'))
              if start <= int(row['time_ns']) <= end and row['in_world'] == 'true']
    frames.sort()
    samples = [row for row in csv.DictReader((output / 'client-samples.csv').open(encoding='utf-8')) if start <= int(row['time_ns']) <= end]
    times = {}
    for row in csv.DictReader((output / 'threads.csv').open(encoding='utf-8')):
        if start <= int(row['time_ns']) <= end:
            times.setdefault(row['name'], []).append((int(row['time_ns']), int(row['cpu_ns'])))
    cpu = {name: (rows[-1][1] - rows[0][1]) / (rows[-1][0] - rows[0][0]) * 100
           for name, rows in times.items() if len(rows) > 1}
    first, last = samples[0], samples[-1]
    low1 = frames[-max(1, math.ceil(len(frames)*.01)):]
    low01 = frames[-max(1, math.ceil(len(frames)*.001)):]
    result = dict(label=label, frames=len(frames), low_1_fps=1000/(sum(low1)/len(low1)),
                  low_01_fps=1000/(sum(low01)/len(low01)),
                  over_33ms=sum(value>33.333 for value in frames),over_50ms=sum(value>50 for value in frames),
                  maximum_ms=frames[-1],median_ms=frames[len(frames)//2],
                  p95_ms=frames[math.floor(.95 * (len(frames)-1))],
                  render_cpu_percent=cpu['Render thread'],
                  receive_cpu_percent=cpu.get('Voxy Distant receive', 0),
                  process_cpu_percent=(int(last['process_cpu_ns'])-int(first['process_cpu_ns'])) /
                  (int(last['time_ns'])-int(first['time_ns']))*100,
                  received_columns=int(last['applied'])-int(first['applied']))
    states = [json.loads(line) for line in (output / 'states.jsonl').read_text(encoding='utf-8').splitlines()]
    states = [s for s in states if start <= s['nano_time'] <= end]
    result.update(entity_count_min=min(s['cpu_entity_count'] for s in states),
                  moving_entities_mean=sum(s['cpu_moving_entities'] for s in states)/len(states))
    if result['entity_count_min'] != 2000 or result['moving_entities_mean'] < 1:
        raise AssertionError('The measured scene must contain 2,000 active, surviving entities: ' + str(result))
    if any(s['cpu_vsync'] or s['cpu_max_fps'] != 260 for s in states):
        raise AssertionError('Frame rate settings changed during the measurement')
    position = server_position('LodBench')
    client_position = [states[-1][axis] for axis in ('x', 'y', 'z')]
    result.update(server_position=position, client_position=client_position)
    if max(abs(a-b) for a,b in zip(position, client_position)) > .5:
        raise AssertionError('Client and server positions diverged: ' + str(result))
    print(json.dumps(result), flush=True)
    return result


def wait_lods(process, output):
    deadline = time.monotonic() + 240
    stable = 0
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Client exited while warming LODs')
        rows = list(csv.DictReader((output / 'client-samples.csv').open(encoding='utf-8')))
        if rows:
            row = rows[-1]
            done = all(int(row[name]) == 0 for name in ('pending', 'queue', 'active', 'mesh_queue', 'invalid', 'full_retry')) and row['scanning'] == 'false' and int(row['geometry_sections']) > 0
            stable = stable + 1 if done else 0
            if stable >= 10:
                print('VOXY_WARM', output, row['geometry_sections'], flush=True)
                return
        time.sleep(1)
    raise TimeoutError('Voxy did not finish showing LODs: ' + str(output))


server, server_log = launch('server', server_home, run / 'server', 'current')
results = []
try:
    deadline = time.monotonic() + 120
    while 'Done (' not in (run / 'server/console.log').read_text(encoding='utf-8'):
        if server.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError('Server failed to start')
        time.sleep(.5)
    server_command('gamerule doMobSpawning false')
    server_command('gamerule doDaylightCycle false')
    server_command('time set noon')
    server_command('kill @e[type=!minecraft:player]')
    server_command('gamerule maxEntityCramming 0')
    server_command('forceload add -64 -16 64 80')
    time.sleep(5)
    server_command('execute positioned 0 0 26 positioned over motion_blocking_no_leaves run summon minecraft:marker ~ ~ ~ {Tags:["cpu_ground"]}')
    ground = int(server_position('@e[type=minecraft:marker,tag=cpu_ground,limit=1]')[1])
    server_command(f'fill -22 {ground-1} 4 22 {ground-1} 48 minecraft:grass_block')
    server_command(f'fill -22 {ground} 4 22 {ground+4} 48 minecraft:air')
    for fence in (f'-22 {ground} 4 22 {ground} 4', f'-22 {ground} 48 22 {ground} 48', f'-22 {ground} 5 -22 {ground} 47', f'22 {ground} 5 22 {ground} 47'):
        server_command('fill ' + fence + ' minecraft:oak_fence')
    for variant in ('baseline', 'current'):
        home = run / (variant + '-game')
        shutil.copytree(base / 'client', home, ignore=shutil.ignore_patterns('logs', 'crash-reports'))
        (home / 'config/voxy_distant.toml').write_text(config, encoding='utf-8')
        text = (home / 'options.txt').read_text()
        for key, value in {'maxFps': '260', 'enableVsync': 'false', 'pauseOnLostFocus': 'false', 'entityDistanceScaling': '2.0'}.items():
            text = re.sub(r'(?m)^' + key + r':.*$', key + ':' + value, text)
        (home / 'options.txt').write_text(text)
        output = run / variant
        client, log = launch('client', home, output, variant)
        try:
            wait_state(client, output, lambda s: s['ready'])
            command(client, output, 'connect')
            wait_state(client, output, lambda s: s['in_world'] and s['lod_hello'])
            server_command('gamemode spectator LodBench')
            server_command(f'tp LodBench 0.0 {ground+2}.0 1.0 0 15')
            wait_state(client, output, lambda s: abs(s['x']) < .01 and abs(s['y']-ground-2) < .01 and abs(s['z']-1) < .01)
            command(client, output, 'cpu-view')
            command(client, output, 'prediction-toggle', enabled=False)
            command(client, output, 'receive')
            wait_lods(client, output)
            command(client, output, 'cruise', speed=4.3, waypoints=[[-1.25, .0625], [1.25, .0625], [0, .0625]])
            wait_state(client, output, lambda s: s['cruise_done'])
            wait_lods(client, output)
            if variant == 'baseline':
                for i in range(2000):
                    x = (i % 45) * .85 - 18.7
                    z = (i // 45) * .85 + 8
                    server_command(f'summon minecraft:pig {x} {ground+.1} {z} {{Silent:1b,PersistenceRequired:1b}}')
            wait_state(client, output, lambda s: s['cpu_entity_count'] == 2000)
            time.sleep(15)
            for mode in ('receive-off', 'receive-on', 'prediction-on'):
                command(client, output, 'prediction-toggle', enabled=mode == 'prediction-on')
                command(client, output, 'receive-disable' if mode == 'receive-off' else 'receive')
                if mode != 'receive-off':
                    wait_lods(client, output)
                time.sleep(5)
                results.append(measure(client, output, variant + '-' + mode + '-stationary'))
                results.append(measure(client, output, variant + '-' + mode + '-strafe', moving=True))
                wait_state(client, output, lambda s: s['cruise_done'])
            command(client, output, 'quit')
            client.wait(timeout=60)
        finally:
            if client.poll() is None:
                client.terminate()
                client.wait(timeout=30)
            log.close()
finally:
    if server.poll() is None:
        server_command('stop')
        server.wait(timeout=60)
    server_log.close()
(run / 'results.json').write_text(json.dumps(dict(entity_count=2000, ai_enabled=True, ground_y=ground, results=results), indent=2))
