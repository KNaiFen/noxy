"""Compare identical single-thread region work with an already compiled baseline.

Requires the existing client launch specification and main classes. This does not
launch Minecraft; asynchronous execution cannot affect the CPU time comparison.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--baseline', type=Path, default=root / 'build/client-cpu-baseline')
parser.add_argument('--runs', type=int, default=100)
options = parser.parse_args()
args = json.loads((root / 'build/benchmarks/lod-square256-cache128/client-launch.json').read_text())['args']
java = Path(args[0])
classpath = args[args.index('-cp') + 1]
results = {}
for variant, checkout in [('baseline', options.baseline.resolve()), ('current', root)]:
    classes = checkout / 'build/classes/java/main'
    output = root / ('build/region-cpu-' + variant)
    output.mkdir(exist_ok=True)
    subprocess.run([str(java.with_name('javac.exe')), '-proc:none', '-encoding', 'UTF-8',
                    '-cp', str(classes) + ';' + classpath, '-d', str(output),
                    str(root / 'tools/RegionCpuCheck.java')], check=True)
    results[variant] = {}
    for mode in ('axis', 'diagonal', 'turns', 'replies'):
        run = subprocess.run([str(java), '-cp', str(output) + ';' + str(classes) + ';' + classpath,
                              'dev.voxydistant.client.RegionCpuCheck', mode, str(options.runs)],
                             check=True, capture_output=True, text=True)
        print(variant, run.stdout.strip(), flush=True)
        (output / (mode + '-result.log')).write_text(run.stdout)
        values = dict(re.findall(r'(\w+)=(-?[\d.]+)', run.stdout))
        results[variant][mode] = {key: float(value) if '.' in value else int(value)
                                  for key, value in values.items()}
for mode in results['baseline']:
    before, after = results['baseline'][mode], results['current'][mode]
    for key in ('moves', 'requests', 'missing', 'order'):
        if before[key] != after[key]:
            raise AssertionError(f'{mode} changed {key}: {before[key]} -> {after[key]}')
    print(f'{mode}: CPU reduction {100 * (1 - after["cpu_ms"] / before["cpu_ms"]):.2f}%', flush=True)
(root / 'build/region-cpu-comparison.json').write_text(json.dumps(results, indent=2))
