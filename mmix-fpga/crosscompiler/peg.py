"""Small packrat PEG interpreter. The grammar file is the source of syntax.

No generated parser, third-party dependency, or host-language eval is needed.
Only named rule matches are retained in the concrete syntax tree.
"""
from __future__ import annotations

import ast
import bisect
import re
from dataclasses import dataclass
from pathlib import Path

from .model import CompileError, Location


@dataclass
class Node:
    rule: str
    start: int
    end: int
    children: list


class Grammar:
    def __init__(self, path: Path):
        self.rules = {}
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = re.fullmatch(r"(\w+)\s*<-\s*(.*)", line)
            if not match:
                raise CompileError(f"Invalid PEG rule at {path.name}:{number}")
            name, source = match.groups()
            if name in self.rules:
                raise CompileError(f"Duplicate PEG rule: {name}")
            self.rules[name] = GrammarExpression(source).parse()
        for rule in self.rules.values():
            self._validate(rule)

    def _validate(self, item):
        kind, value = item
        if kind == "ref" and value not in self.rules:
            raise CompileError(f"Undefined PEG rule: {value}")
        if kind in ("seq", "choice"):
            for child in value:
                self._validate(child)
        elif kind in ("!", "&", "?", "*", "+"):
            self._validate(value)


class GrammarExpression:
    token = re.compile(r"\s*(?:('(?:\\.|[^'\\])*')|(\[(?:\\.|[^\]\\])*\])|(\w+)|([()/!?*+&.]))")

    def __init__(self, text):
        self.tokens = []
        position = 0
        while position < len(text):
            match = self.token.match(text, position)
            if not match:
                if not text[position:].strip():
                    break
                raise CompileError(f"Unsupported PEG expression: {text[position:]}")
            self.tokens.append(next(x for x in match.groups() if x is not None))
            position = match.end()
        self.pos = 0

    def take(self, value):
        if self.pos < len(self.tokens) and self.tokens[self.pos] == value:
            self.pos += 1
            return True
        return False

    def parse(self):
        result = self.choice()
        if self.pos != len(self.tokens):
            raise CompileError("Trailing PEG tokens")
        return result

    def choice(self):
        items = [self.sequence()]
        while self.take("/"):
            items.append(self.sequence())
        return ("choice", items) if len(items) > 1 else items[0]

    def sequence(self):
        items = []
        while self.pos < len(self.tokens) and self.tokens[self.pos] not in ("/", ")"):
            items.append(self.prefix())
        return ("seq", items)

    def prefix(self):
        if self.pos >= len(self.tokens):
            raise CompileError("Incomplete PEG expression")
        if self.tokens[self.pos] in ("!", "&"):
            op = self.tokens[self.pos]
            self.pos += 1
            return op, self.prefix()
        if self.take("("):
            item = self.choice()
            if not self.take(")"):
                raise CompileError("Unclosed PEG group")
        else:
            token = self.tokens[self.pos]
            self.pos += 1
            if token.startswith("'"):
                item = "literal", ast.literal_eval(token)
            elif token.startswith("["):
                item = "class", re.compile(token)
            elif token == ".":
                item = "any", None
            elif re.fullmatch(r"\w+", token):
                item = "ref", token
            else:
                raise CompileError(f"Unexpected PEG token: {token}")
        if self.pos < len(self.tokens) and self.tokens[self.pos] in ("?", "*", "+"):
            op = self.tokens[self.pos]
            self.pos += 1
            item = op, item
        return item


class Parser:
    def __init__(self, grammar, source, filename):
        self.grammar = grammar
        self.source = source
        self.filename = filename
        self.lines = [0] + [match.end() for match in re.finditer(r"\r\n|\r|\n", source)]
        self.memo = {}
        self.active = set()
        self.farthest = 0
        self.expected = set()
        self.silent = 0

    def location(self, position):
        line = bisect.bisect_right(self.lines, position)
        return Location(self.filename, line, position - self.lines[line - 1] + 1)

    def failure(self, position, expected):
        if not self.silent:
            if position > self.farthest:
                self.farthest, self.expected = position, set()
            if position == self.farthest:
                self.expected.add(expected)
        return None

    def parse(self):
        result = self.rule("Source", 0)
        if result is None:
            expected = ", ".join(sorted(self.expected)[:8])
            raise CompileError(f"Syntax error; expected {expected}", self.location(self.farthest))
        return result[1][0]

    def rule(self, name, position):
        key = (name, position)
        if key in self.memo:
            return self.memo[key]
        if key in self.active:
            raise CompileError(f"Left-recursive PEG rule: {name}", self.location(position))
        self.active.add(key)
        try:
            result = self.match(self.grammar.rules[name], position)
            if result is not None:
                end, children = result
                result = end, [Node(name, position, end, children)]
            self.memo[key] = result
            return result
        finally:
            self.active.remove(key)

    def match(self, item, position):
        kind, value = item
        if kind == "ref":
            return self.rule(value, position)
        if kind == "literal":
            if self.source.startswith(value, position):
                return position + len(value), []
            return self.failure(position, repr(value))
        if kind == "class":
            match = value.match(self.source, position)
            return (match.end(), []) if match else self.failure(position, value.pattern)
        if kind == "any":
            return (position + 1, []) if position < len(self.source) else self.failure(position, "character")
        if kind == "seq":
            children = []
            for part in value:
                result = self.match(part, position)
                if result is None:
                    return None
                position, nodes = result
                children.extend(nodes)
            return position, children
        if kind == "choice":
            for part in value:
                result = self.match(part, position)
                if result is not None:
                    return result
            return None
        if kind in ("!", "&"):
            self.silent += 1
            try:
                found = self.match(value, position) is not None
            finally:
                self.silent -= 1
            return (position, []) if found == (kind == "&") else None
        children, count = [], 0
        while True:
            result = self.match(value, position)
            if result is None:
                break
            end, nodes = result
            if end == position:
                raise CompileError("PEG repetition matched empty input", self.location(position))
            position = end
            children.extend(nodes)
            count += 1
            if kind == "?":
                break
        return None if kind == "+" and not count else (position, children)
