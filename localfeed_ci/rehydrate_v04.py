from pathlib import Path
import base64, gzip, hashlib, re, shutil, subprocess, zipfile

ROOT = Path.cwd()
CI = ROOT / 'localfeed_ci'
TMP = Path('/tmp/localfeed')
PROJECT = TMP / 'LocalFeed_v0.2'

def clean_b64(path: Path) -> bytes:
    return re.sub(rb'[^A-Za-z0-9+/=]', b'', path.read_bytes())

def checked_parts(spec):
    parts=[]
    for name, size, sha in spec:
        part=clean_b64(CI/name)
        got=hashlib.sha256(part).hexdigest()
        print(f'{name}: len={len(part)} sha256={got}')
        assert len(part)==size, (name, len(part), size)
        assert got==sha, (name, got, sha)
        parts.append(part)
    return b''.join(parts)

base_names=['chunk_00.b64','chunk_01.b64','chunk_02.b64','chunk_03.b64','chunk_04a.b64','chunk_04b.b64','chunk_05.b64','chunk_06.b64','chunk_07.b64']
base=re.sub(rb'[^A-Za-z0-9+/=]', b'', b''.join((CI/n).read_bytes() for n in base_names))
assert len(base)==62604
zip_bytes=base64.b64decode(base)
shutil.rmtree(TMP, ignore_errors=True)
TMP.mkdir(parents=True)
zip_path=Path('/tmp/localfeed.zip'); zip_path.write_bytes(zip_bytes)
with zipfile.ZipFile(zip_path) as z: z.extractall(TMP)
assert (PROJECT/'settings.gradle.kts').is_file()

v03_names=['v03_patch_00.b64','v03_patch_01.b64','v03_patch_02_0.b64','v03_patch_02_1.b64','v03_patch_02_2_0.b64','v03_patch_02_2_1.b64','v03_patch_02_3.b64','v03_patch_02_4.b64','v03_patch_02_5.b64','v03_patch_02_6.b64']
v03=re.sub(rb'[^A-Za-z0-9+/=]', b'', b''.join((CI/n).read_bytes() for n in v03_names))
assert len(v03)==22816
p03=Path('/tmp/v03.patch'); p03.write_bytes(gzip.decompress(base64.b64decode(v03)))
subprocess.run(['patch','-p1','-i',str(p03)], cwd=PROJECT, check=True)

v04_spec=[
('v04r_patch_00.b64',6000,'8cec325139132ff4869565edb74e65f1669ab4f9ac2a2db3ce0c07adec4f232a'),
('v04r_patch_01.b64',6000,'9e8a1d8087e08fd875db96a06b8f231db15b76c9c9f0a42628bd3942dca0e8af'),
('v04r_patch_02.b64',6000,'9dcfeeeff60edda06df7c95507f9feb238cdb759a79431bf6a5f744dc823bd55'),
('v04r_patch_03.b64',6000,'abeaaddf06918cbc48bb12425282af543ab33fcde6de59e0eac822fbc770a1f1'),
('v04r_patch_04.b64',6000,'ca6758dbd9c7d4ba5e8d0e46b1d5da8d082377ae8c277cb61b11be3953d4aa63'),
('v04r_patch_05.b64',6000,'dd01957168ff963f183170aed5405f0cc15b7eaba42398485a461f0cfc950bec'),
('v04r_patch_06.b64',4956,'a2c286fdfc7d772dcd7995bfa90cc2f65613eb595263fdf96bf1af79578d50b0')]
v04_b64=checked_parts(v04_spec)
assert len(v04_b64)==40956
v04=gzip.decompress(base64.b64decode(v04_b64, validate=True))
assert len(v04)==147365
assert hashlib.sha256(v04).hexdigest()=='d02bc35059a4cd807a6ae02f0e755d7e76785eabe1561daf98877499c8fee566'
text=v04.decode('utf-8')
kept=[]
for i, sec in enumerate(text.split('diff --git ')):
    if i==0:
        kept.append(sec); continue
    header=sec.splitlines()[0].strip()
    if header in {'a/app/build.gradle.kts b/app/build.gradle.kts','a/build.gradle.kts b/build.gradle.kts'}:
        print('skip normalized build diff', header); continue
    kept.append('diff --git '+sec)
p04=Path('/tmp/v04.patch'); p04.write_text(''.join(kept), encoding='utf-8')
subprocess.run(['patch','--batch','--forward','-p1','-i',str(p04)], cwd=PROJECT, check=True)

nf_spec=[
('v04nf_00.b64',3000,'851e38feb6e88917b849a5d876b8fc9d461cdc431942d94f34535eb01531a580'),
('v04nf_01.b64',3000,'41fb2dab63ea3191c4c10f3a4cd2a1463743faf5598915c553b66a3ca8e84d28'),
('v04nf_02.b64',3000,'5ed857a3b6579168d63c081717e7fd90f3887c0434ab7e62749ec07593c0b40e'),
('v04nf_03.b64',292,'7619e1c772f45de136cb7bfa7764bb9b962a610e669d458f30fbc35c9856853c')]
nf_b64=checked_parts(nf_spec)
assert len(nf_b64)==9292 and hashlib.sha256(nf_b64).hexdigest()=='bf8db9ae56e96dbf50569d188c913975e6a761d00724dd977315cd0eea9bdd93'
nf=base64.b64decode(nf_b64, validate=True)
assert len(nf)==6968 and hashlib.sha256(nf).hexdigest()=='b4a66545ffcda3c3479a243a6428b3b5cbaec3907da50fd91b5abb20618f666e'
archive=Path('/tmp/v04new.tar.gz'); archive.write_bytes(nf)
subprocess.run(['tar','-xzf',str(archive)], cwd=PROJECT, check=True)

app=PROJECT/'app/build.gradle.kts'; s=app.read_text()
s=s.replace('    id("org.jetbrains.kotlin.android")\n','')
s=re.sub(r'versionCode\s*=\s*\d+','versionCode = 4',s)
s=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "0.4.0"',s)
s=re.sub(r'\n\s*kotlinOptions\s*\{.*?\n\s*\}\s*\n','\n',s,flags=re.S)
app.write_text(s)
root=PROJECT/'build.gradle.kts'; s=root.read_text(); s=re.sub(r'^\s*id\("org\.jetbrains\.kotlin\.android"\).*\n','',s,flags=re.M); root.write_text(s)

assert 'versionName = "0.4.0"' in app.read_text()
assert 'org.jetbrains.kotlin.android' not in app.read_text()
assert (PROJECT/'app/src/main/java/com/localfeed/app/data/DuplicateScanner.kt').is_file()
assert (PROJECT/'app/src/main/java/com/localfeed/app/ui/AlbumDragSelectTouchListener.kt').is_file()
assert (PROJECT/'app/src/main/java/com/localfeed/app/ui/AlbumQuery.kt').is_file()
print('READY', PROJECT)
