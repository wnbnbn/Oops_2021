from pathlib import Path
import base64, gzip, hashlib, re, subprocess, sys

ROOT = Path.cwd()
CI = ROOT / 'localfeed_ci'
PROJECT = Path('/tmp/localfeed/LocalFeed_v0.2')

subprocess.run([sys.executable, str(CI / 'rehydrate_v04.py')], check=True)

def clean_b64(path: Path) -> bytes:
    return re.sub(rb'[^A-Za-z0-9+/=]', b'', path.read_bytes())

spec = [
    ('v05_patch_00.b64', 5000, '056524fc378c13b41da5ac01c5ac0196c06696677fb7bb042dad8deaedda7fef'),
    ('v05_patch_01.b64', 5000, 'a6a2b2d6fed58d2459e8ef2d786871c3ae975eda60ae3400c2963c3c7cabce4d'),
    ('v05_patch_02.b64', 2544, '6cf6ef94decf2d2440bca9db99f57aec7f4a10701e1ba07b9994fc5e87b57766'),
]
parts = []
for name, expected_len, expected_sha in spec:
    part = clean_b64(CI / name)
    got = hashlib.sha256(part).hexdigest()
    print(f'{name}: len={len(part)} sha256={got}')
    assert len(part) == expected_len, (name, len(part), expected_len)
    assert got == expected_sha, (name, got, expected_sha)
    parts.append(part)

joined = b''.join(parts)
assert len(joined) == 12544
assert hashlib.sha256(joined).hexdigest() == '1940607d3397e4729607e28ac0c2db0ec6b209f0e0e72bf79d4cfc90a317f49f'
raw = gzip.decompress(base64.b64decode(joined, validate=True))
assert len(raw) == 33792
assert hashlib.sha256(raw).hexdigest() == '26c7ed83f4c70b44e0a916f4948f8c51ddd8316bd86104dea8c5d781ec73a99a'
patch_path = Path('/tmp/v05.patch')
patch_path.write_bytes(raw)
subprocess.run(['patch', '--batch', '--forward', '-p1', '-i', str(patch_path)], cwd=PROJECT, check=True)

app = PROJECT / 'app/build.gradle.kts'
assert 'versionName = "0.5.0"' in app.read_text(encoding='utf-8')
assert (PROJECT / 'app/src/main/java/com/localfeed/app/data/SimilarVideoScanner.kt').is_file()
assert 'private var awaitingFirstFrameMediaId' in (PROJECT / 'app/src/main/java/com/localfeed/app/media/PlaybackCoordinator.kt').read_text(encoding='utf-8')
print('READY V0.5', PROJECT)
