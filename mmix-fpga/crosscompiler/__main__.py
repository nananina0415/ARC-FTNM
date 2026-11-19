from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .compiler import Compiler
from .model import CompileError
from .output import publish


def main(argv=None):
    parser = argparse.ArgumentParser(description="Compile structural .pseudo circuits to Verilog-2001")
    parser.add_argument("source", type=Path, help="Root directory containing .pseudo source files")
    parser.add_argument("--top", required=True, help="Root-relative circuit name, for example cpu::Cpu")
    parser.add_argument("--grammar", type=Path,
                        default=Path(__file__).resolve().parent.parent / "pseudo" / "grammar.peg")
    parser.add_argument("--out", type=Path, help="Generated-only output directory")
    parser.add_argument("--single-file", action="store_true", help="Combine modules in design.v; preserve circuit hierarchy")
    parser.add_argument("--check", action="store_true", help="Perform parsing/elaboration only; write no files")
    args = parser.parse_args(argv)
    if not args.check and args.out is None:
        parser.error("--out is required unless --check is supplied")
    try:
        generated, metadata, warnings = Compiler(args.source, args.grammar).build(args.top, args.single_file, args.out)
        for warning in warnings:
            print(f"warning: {warning}", file=sys.stderr)
        if args.check:
            print(f"Static analysis completed: {args.top}")
        else:
            publish(args.out, args.source, generated, metadata)
            print(f"Generated {len(generated)} artifacts in {args.out}")
        return 0
    except (CompileError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
