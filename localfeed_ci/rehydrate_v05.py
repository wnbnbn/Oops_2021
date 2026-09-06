from pathlib import Path
import base64, gzip, hashlib, re, subprocess, sys

ROOT = Path.cwd()
CI = ROOT / 'localfeed_ci'
PROJECT = Path('/tmp/localfeed/LocalFeed_v0.2')

subprocess.run([sys.executable, str(CI / 'rehydrate_v04.py')], check=True)

def clean_b64(path: Path) -> bytes:
    return re.sub(rb'[^A-Za-z0-9+/=]', b'', path.read_bytes())

def checked_parts(spec):
    parts = []
    for name, expected_len, expected_sha in spec:
        part = clean_b64(CI / name)
        got = hashlib.sha256(part).hexdigest()
        print(f'{name}: len={len(part)} sha256={got}')
        assert len(part) == expected_len, (name, len(part), expected_len)
        assert got == expected_sha, (name, got, expected_sha)
        parts.append(part)
    return b''.join(parts)

patch_spec = [
    ('v05_patch_00.b64', 5000, '056524fc378c13b41da5ac01c5ac0196c06696677fb7bb042dad8deaedda7fef'),
    ('v05_patch_01.b64', 5000, 'a6a2b2d6fed58d2459e8ef2d786871c3ae975eda60ae3400c2963c3c7cabce4d'),
    ('v05_patch_02.b64', 2544, '6cf6ef94decf2d2440bca9db99f57aec7f4a10701e1ba07b9994fc5e87b57766'),
]
joined = checked_parts(patch_spec)
assert len(joined) == 12544
assert hashlib.sha256(joined).hexdigest() == '1940607d3397e4729607e28ac0c2db0ec6b209f0e0e72bf79d4cfc90a317f49f'
raw = gzip.decompress(base64.b64decode(joined, validate=True))
assert len(raw) == 33792
assert hashlib.sha256(raw).hexdigest() == '26c7ed83f4c70b44e0a916f4948f8c51ddd8316bd86104dea8c5d781ec73a99a'
patch_path = Path('/tmp/v05.patch')
patch_path.write_bytes(raw)
subprocess.run(['patch', '--batch', '--forward', '-p1', '-i', str(patch_path)], cwd=PROJECT, check=True)

new_spec = [
    ('v05nf_00.b64', 3000, '664abb400bb892e7e930c8341d9cb4ae85defdaf7e74f4e5b35810785d043333'),
    ('v05nf_01.b64', 3000, 'c163eca5773b8d3b20f20bc556875817e14248dde4b3cf50ea65d149152b65d2'),
    ('v05nf_02.b64', 852, 'bcc2394e5342bfb85d6969ef8993366e95c3ee78d9322e1541885e7c0192bc1b'),
]
new_b64 = checked_parts(new_spec)
assert len(new_b64) == 6852
assert hashlib.sha256(new_b64).hexdigest() == '9934f9100bb5b96efed9ade6a9d4b4af0fd0d898b7ce63495fdc8179be314abf'
archive_bytes = base64.b64decode(new_b64, validate=True)
assert len(archive_bytes) == 5139
assert hashlib.sha256(archive_bytes).hexdigest() == 'cb6952a2f2750fe2dfecc56a0cb3855ff53ea2b500d87aaeca360d43daefdb85'
archive = Path('/tmp/v05-new-files.tar.gz')
archive.write_bytes(archive_bytes)
subprocess.run(['tar', '-xzf', str(archive)], cwd=PROJECT, check=True)

app = PROJECT / 'app/build.gradle.kts'
assert 'versionName = "0.5.0"' in app.read_text(encoding='utf-8')
assert (PROJECT / 'app/src/main/java/com/localfeed/app/data/SimilarVideoScanner.kt').is_file()
assert 'private var awaitingFirstFrameMediaId' in (PROJECT / 'app/src/main/java/com/localfeed/app/media/PlaybackCoordinator.kt').read_text(encoding='utf-8')
print('READY V0.5', PROJECT)
