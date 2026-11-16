"""Publish generated artifacts without overwriting files owned by the user."""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path, PurePosixPath

from .model import CompileError


MANIFEST = ".crosscompiler-manifest.json"


def safe_target(root, relative):
    path = PurePosixPath(relative)
    if path.is_absolute() or not path.parts or any(part in ("..", ".") or ":" in part or "\\" in part for part in path.parts):
        raise CompileError(f"Invalid generated path: {relative}")
    destination = root.joinpath(*path.parts)
    if not destination.resolve().is_relative_to(root):
        raise CompileError(f"Generated path leaves output directory: {relative}")
    if destination.is_symlink():
        raise CompileError(f"Refusing generated symlink target: {relative}")
    return destination


def publish(directory, source, generated, metadata):
    root = directory.resolve()
    source = source.resolve()
    if root.is_relative_to(source) or source.is_relative_to(root):
        raise CompileError("Output and source directories must not contain one another")
    old_files = set()
    manifest_path = safe_target(root, MANIFEST)
    if manifest_path.exists():
        try:
            previous = json.loads(manifest_path.read_text(encoding="utf-8"))
            if not isinstance(previous, dict) or previous.get("schema") != 1 or not isinstance(previous.get("files"), list):
                raise ValueError("unsupported manifest")
            old_files = set(previous["files"])
            if any(not isinstance(name, str) for name in old_files):
                raise ValueError("invalid file list")
        except (ValueError, TypeError) as error:
            raise CompileError(f"Invalid existing compiler manifest: {error}")
    for name in old_files | set(generated):
        target = safe_target(root, name)
        if target.exists() and not target.is_file():
            raise CompileError(f"Generated target is not a regular file: {name}")
        if target.exists() and name not in old_files:
            raise CompileError(f"Refusing to overwrite unowned file: {target}")
    metadata = dict(metadata, files=sorted(generated))
    contents = dict(generated)
    contents[MANIFEST] = json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    root.parent.mkdir(parents=True, exist_ok=True)
    # Stage complete contents first. Back up replaced/obsolete files, then install;
    # roll back the managed file set if an I/O operation fails. No recursive cleanup
    # of the user's output tree, and no deletion of files outside the manifest.
    with tempfile.TemporaryDirectory(prefix=".crosscompiler-", dir=root.parent) as temporary:
        stage = Path(temporary) / "new"
        backup = Path(temporary) / "old"
        for name, content in sorted(contents.items()):
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8", newline="\n")
        affected = sorted(old_files | set(contents) | ({MANIFEST} if manifest_path.exists() else set()))
        moved, installed = [], []
        try:
            root.mkdir(parents=True, exist_ok=True)
            for name in affected:
                target = safe_target(root, name)
                if target.exists():
                    saved = backup / name
                    saved.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(target, saved)
                    moved.append(name)
            # Install manifest last, so ownership reflects the completed file set.
            for name in sorted(contents, key=lambda item: (item == MANIFEST, item)):
                target = safe_target(root, name)
                target.parent.mkdir(parents=True, exist_ok=True)
                os.replace(stage / name, target)
                installed.append(name)
        except (OSError, CompileError):
            for name in reversed(installed):
                safe_target(root, name).unlink(missing_ok=True)
            for name in moved:
                os.replace(backup / name, safe_target(root, name))
            raise
