from __future__ import annotations

import hashlib
from pathlib import Path

from .elaborator import Elaborator
from .frontend import Frontend
from .model import CompileError
from .peg import Grammar
from .typesys import Spec, Symbols
from .verilog import artifacts


class Compiler:
    def __init__(self, source: Path, grammar: Path):
        self.source = source.resolve()
        self.grammar_path = grammar.resolve()

    def build(self, top_name, single_file=False):
        if not self.source.is_dir():
            raise CompileError(f"Source directory does not exist: {self.source}")
        grammar_bytes = self.grammar_path.read_bytes()
        grammar = Grammar(self.grammar_path)
        declarations, fingerprints = [], {}
        paths = sorted(self.source.rglob("*.pseudo"), key=lambda path: path.relative_to(self.source).as_posix())
        if not paths:
            raise CompileError("No .pseudo files found")
        for path in paths:
            relative = path.relative_to(self.source)
            if not path.resolve().is_relative_to(self.source):
                raise CompileError(f"Source symlink leaves source directory: {relative}")
            namespace = ("$global",) if relative == Path("global.pseudo") else tuple(relative.parent.parts)
            payload = path.read_bytes()
            fingerprints[relative.as_posix()] = hashlib.sha256(payload).hexdigest()
            try:
                text = payload.decode("utf-8")
            except UnicodeDecodeError as error:
                raise CompileError(f"{relative.as_posix()}: source is not UTF-8: {error}")
            declarations.extend(Frontend(grammar, text, relative.as_posix(), namespace).parse())
        symbols = Symbols(declarations)
        key = tuple(top_name.removeprefix("::").split("::"))
        declaration = symbols.definitions.get(key)
        if declaration is None or declaration.kind not in ("comb", "seq"):
            raise CompileError(f"Unknown top circuit: {top_name}")
        if declaration.params:
            raise CompileError("Top must be a concrete, non-generic circuit; instantiate a generic in a wrapper", declaration.loc)
        elaborator = Elaborator(symbols)
        top = elaborator.compile(Spec(key))
        generated = artifacts(list(elaborator.modules.values()), top, single_file)
        metadata = {"schema": 1, "compiler": "0.1.0", "top": top_name,
                    "single_file": single_file,
                    "grammar_sha256": hashlib.sha256(grammar_bytes).hexdigest(), "sources": fingerprints}
        warnings = sorted({warning for module in elaborator.modules.values() for warning in module.warnings})
        return generated, metadata, warnings
