from pathlib import Path
import hashlib, json
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / "localfeed_ci/v05_source_sha256.json").read_text())
actual_paths = {str(p.relative_to(root)) for p in (root / "LocalFeed").rglob("*") if p.is_file() and not any(x in {"build", ".gradle"} for x in p.relative_to(root / "LocalFeed").parts) and p.name != "local.properties"}
assert actual_paths == set(manifest), (actual_paths - set(manifest), set(manifest) - actual_paths)
for name, expected in manifest.items():
    raw = (root / name).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == expected, name
    if Path(name).suffix.lower() in {".png", ".webp", ".jpg", ".jpeg"}:
        if name.lower().endswith(".png"):
            assert raw.startswith(b"\x89PNG\r\n\x1a\n"), name
        continue
    text = raw.decode("utf-8", errors="strict")
    assert "\ufffd" not in text and "\x00" not in text, name
print(f"Source integrity: PASS ({len(manifest)} files, SHA256; text strict UTF-8)")
