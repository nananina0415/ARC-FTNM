import hashlib
import subprocess
import re

from .image import load_mmo


def assemble(executable, source, directory):
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / "program.mmo"
    listing = directory / "program.mml"
    command = [str(executable), "-b", "4096", "-l", str(listing), "-o", str(output), str(source.resolve())]
    result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    (directory / "assemble.log").write_text(result.stdout+result.stderr, encoding="utf-8")
    # MMIXAL can write a partial MMO and exit zero after reporting errors.
    diagnostics = result.stdout + result.stderr
    if result.returncode or not output.exists() or re.search(r'\(\d+ errors? (?:was|were) found', diagnostics) or ' warning:' in diagnostics:
        raise RuntimeError(f"MMIXAL failed: {result.stdout}{result.stderr}")
    image = load_mmo(output)
    return image, {"assembler_sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
                   "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
