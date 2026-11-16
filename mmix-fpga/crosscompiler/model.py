from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Location:
    file: str
    line: int
    column: int

    def __str__(self):
        return f"{self.file}:{self.line}:{self.column}"


class CompileError(Exception):
    def __init__(self, message, location=None):
        self.location = location
        super().__init__(f"{location}: {message}" if location else message)


@dataclass
class Expr:
    kind: str
    value: object = None
    args: list = field(default_factory=list)
    loc: Location | None = None


@dataclass
class Declaration:
    kind: str
    name: str
    namespace: tuple
    loc: Location
    params: tuple = ()
    members: list = field(default_factory=list)
    body: list = field(default_factory=list)
    value: Expr | None = None


@dataclass
class Member:
    name: str
    type: Expr
    modifier: str | None
    loc: Location


@dataclass
class Connection:
    left: Expr
    arrow: str
    right: Expr
    loc: Location
