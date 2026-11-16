from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Vector:
    bits: tuple
    read: bool = True
    write: bool = True
    spread: bool = False


@dataclass
class Pin:
    path: tuple
    direction: str
    bits: tuple
    owner: str | None
    location: object
    tristate: bool = False


@dataclass
class Gate:
    operation: str
    inputs: list
    output: tuple
    location: object


@dataclass
class Instance:
    path: tuple
    module: object
    pins: list
    location: object


class Nets:
    def __init__(self):
        self.parent = []

    def allocate(self, count):
        start = len(self.parent)
        self.parent.extend(range(start, start + count))
        return tuple(range(start, start + count))

    def find(self, bit):
        while self.parent[bit] != bit:
            self.parent[bit] = self.parent[self.parent[bit]]
            bit = self.parent[bit]
        return bit

    def join(self, left, right):
        a, b = self.find(left), self.find(right)
        if a != b:
            self.parent[max(a, b)] = min(a, b)


@dataclass
class Module:
    spec: object
    interface: object
    sequential: bool
    location: object = None
    nets: Nets = field(default_factory=Nets)
    ports: list = field(default_factory=list)
    pins: list = field(default_factory=list)
    instances: list = field(default_factory=list)
    gates: list = field(default_factory=list)
    constants: list = field(default_factory=list)
    touched: set = field(default_factory=set)
    opened: set = field(default_factory=set)
    dependencies: set = field(default_factory=set)
    tri_outputs: set = field(default_factory=set)
    warnings: list = field(default_factory=list)
