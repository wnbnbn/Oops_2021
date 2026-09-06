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
    ('v05nf2_00.b64', 1000, 'e84ce11683301bfbf131a1fcd7d8cf6c1707234af0f5f0f885d59a76bfa09672'),
    ('v05nf2_01.b64', 1000, '8a48b26fc833b88d7c1faf1b74d2b08d8a3c886437584f903bc77b0439f2a57d'),
    ('v05nf2_02.b64', 1000, '85f6c1ed7b25c4e9d8bbb183de306e16d3911e8cebdafdafca13c4be641fb4f0'),
    ('v05nf2_03.b64', 1000, '556329e6a858d53e632470d8d5607e23dc432920500551c67d4c485ac856c05f'),
    ('v05nf2_04.b64', 1000, 'dac4044fd4cd517fb566bc04722b04bb679147aed893d82292255a6e5f5db738'),
    ('v05nf2_05.b64', 1000, '14f93866df61c369a744e232e5aaaa1e173469071ad658bdd50cd82b27c3ad4d'),
    ('v05nf2_06.b64', 852, 'bcc2394e5342bfb85d6969ef8993366e95c3ee78d9322e1541885e7c0192bc1b'),
]
new_b64 = checked_parts(new_spec)
assert len(new_b64) == 6852
print(f'v0.5 new-file base64: len={len(new_b64)} sha256={hashlib.sha256(new_b64).hexdigest()}')
archive_bytes = base64.b64decode(new_b64, validate=True)
print(f'v0.5 new-file archive: len={len(archive_bytes)} sha256={hashlib.sha256(archive_bytes).hexdigest()}')
archive = Path('/tmp/v05-new-files.tar.gz')
archive.write_bytes(archive_bytes)

# Historical archive is damaged. Extract as much as gzip/tar can recover, then normalize
# the recovered Kotlin text so we can reconstruct the damaged helper section deterministically.
extract = subprocess.run(['tar', '-xzf', str(archive)], cwd=PROJECT, check=False)
print(f'v0.5 archive extraction exit={extract.returncode}')

app = PROJECT / 'app/build.gradle.kts'
assert 'versionName = "0.5.0"' in app.read_text(encoding='utf-8')
scanner = PROJECT / 'app/src/main/java/com/localfeed/app/data/SimilarVideoScanner.kt'
assert scanner.is_file() and scanner.stat().st_size > 1000
scanner_bytes = scanner.read_bytes()
print(f'SimilarVideoScanner.kt recovered len={len(scanner_bytes)} sha256={hashlib.sha256(scanner_bytes).hexdigest()}')
scanner_text = scanner_bytes.decode('utf-8', errors='replace')
replacement_count = scanner_text.count('\ufffd')
print(f'SimilarVideoScanner.kt replacement chars={replacement_count}')
scanner_lines = scanner_text.splitlines()
for i, line in enumerate(scanner_lines, 1):
    if '\ufffd' in line:
        print(f'CORRUPT_LINE {i}: {line[:500]}')

print('===== SIMILAR_VIDEO_SCANNER_PREFIX_BEGIN =====')
for i, line in enumerate(scanner_lines[:205], 1):
    print(f'{i:04d}: {line}')
print('===== SIMILAR_VIDEO_SCANNER_PREFIX_END =====')

print('===== SIMILAR_VIDEO_CALLSITES_BEGIN =====')
needles = ('SimilarVideoScanner', 'SimilarVideoMatch', 'SimilarVideoGroup', 'markNotDuplicate', 'findSimilar', 'scanSimilar')
for source in sorted((PROJECT / 'app/src/main/java').rglob('*.kt')):
    if source == scanner:
        continue
    text = source.read_text(encoding='utf-8', errors='replace')
    lines = text.splitlines()
    for idx, line in enumerate(lines, 1):
        if any(needle in line for needle in needles):
            lo = max(1, idx - 3)
            hi = min(len(lines), idx + 5)
            print(f'--- {source.relative_to(PROJECT)}:{idx} ---')
            for j in range(lo, hi + 1):
                print(f'{j:04d}: {lines[j-1]}')
print('===== SIMILAR_VIDEO_CALLSITES_END =====')

# Normalize recovered text only for diagnostic compilation; final build will copy a clean source file.
scanner.write_text(scanner_text, encoding='utf-8')
assert 'class SimilarVideoScanner' in scanner_text
playback = PROJECT / 'app/src/main/java/com/localfeed/app/media/PlaybackCoordinator.kt'
assert 'private var awaitingFirstFrameMediaId' in playback.read_text(encoding='utf-8')
print('READY V0.5', PROJECT)
