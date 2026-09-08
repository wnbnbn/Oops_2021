"""Compatibility entry point: v0.5 now builds directly from tracked UTF-8 source."""
from pathlib import Path
import subprocess, sys
subprocess.run([sys.executable, str(Path(__file__).with_name("verify_v05_source.py"))], check=True)
print("READY V0.5", Path(__file__).resolve().parents[1] / "LocalFeed")
