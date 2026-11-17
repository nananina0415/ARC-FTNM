from __future__ import annotations

from dataclasses import dataclass, field

from .model import CompileError


@dataclass(frozen=True)
class Port:
    direction: str
    width: int


@dataclass(frozen=True)
class Array:
    element: object
    length: int


@dataclass(frozen=True)
class Record:
    fields: tuple


@dataclass(frozen=True)
class Spec:
    name: tuple
    arguments: tuple = ()
    builtin: bool = False


@dataclass
class Context:
    namespace: tuple
    parameters: dict = field(default_factory=dict)
    aliases: dict = field(default_factory=dict)
    values: dict = field(default_factory=dict)


ARITIES = {"D_FlipFlop": 0, "Mux": 2, "Decoder": 2, "TriState": 1,
           "Adder": 1, "Multiplier": 1, "Divider": 1, "SyncRam": 3}
SEQUENTIAL_PRIMITIVES = ("D_FlipFlop", "SyncRam")


def leaves(type_, prefix=()):
    if isinstance(type_, Port):
        yield prefix, type_
    elif isinstance(type_, Array):
        for index in range(type_.length):
            yield from leaves(type_.element, prefix + (str(index),))
    elif isinstance(type_, Record):
        for name, item in type_.fields:
            yield from leaves(item, prefix + (name,))
    else:
        raise CompileError("Circuit instances cannot be exposed as a port")


def invert(type_):
    if isinstance(type_, Port):
        return Port({"in": "out", "out": "in", "inout": "inout"}[type_.direction], type_.width)
    if isinstance(type_, Array):
        return Array(invert(type_.element), type_.length)
    if isinstance(type_, Record):
        return Record(tuple((name, invert(item)) for name, item in type_.fields))
    raise CompileError("Only a bundle/port type can be reversed")


class Symbols:
    def __init__(self, declarations):
        self.definitions, self.wires = {}, {}
        self.resolving = set()
        for declaration in declarations:
            if len(set(declaration.params)) != len(declaration.params):
                raise CompileError("Duplicate generic parameter", declaration.loc)
            key = declaration.namespace + (declaration.name,)
            table = self.wires if declaration.kind == "wire" else self.definitions
            if key in table:
                raise CompileError(f"Duplicate declaration {'::'.join(key)}", declaration.loc)
            table[key] = declaration
        for key, declaration in self.definitions.items():
            if declaration.kind in ("comb", "seq"):
                if key not in self.wires:
                    raise CompileError("Circuit has no wire block", declaration.loc)
                if declaration.params != self.wires[key].params:
                    raise CompileError("Circuit/wire parameters differ", self.wires[key].loc)
        for key, wire in self.wires.items():
            if key not in self.definitions or self.definitions[key].kind not in ("comb", "seq"):
                raise CompileError("wire has no matching circuit", wire.loc)

    def lookup(self, expression, context):
        child, parts = expression.value
        arity = len(expression.args)
        if len(parts) == 1 and not child:
            if not arity and parts[0] in context.aliases:
                return context.aliases[parts[0]], context
            candidates = [context.namespace + parts, ("$global",) + parts]
        elif child:
            base = () if context.namespace == ("$global",) else context.namespace
            candidates = [base + parts]
        else:
            candidates = [context.namespace[:-1] + parts]
        for key in candidates:
            declaration = self.definitions.get(key)
            if declaration is not None and len(declaration.params) == arity:
                return declaration, Context(declaration.namespace)
        if len(parts) == 1 and not child and ARITIES.get(parts[0]) == arity:
            return Spec(("$builtin", parts[0]), builtin=True), context
        raise CompileError(f"Unknown name/arity: {'::'.join(parts)}/{arity}", expression.loc)

    def alias(self, declaration, context, callback):
        key = (id(declaration), callback.__name__)
        if key in self.resolving:
            raise CompileError(f"Cyclic alias {declaration.name}", declaration.loc)
        self.resolving.add(key)
        try:
            return callback(declaration.value, context)
        finally:
            self.resolving.remove(key)

    def constant(self, expression, context):
        kind = expression.kind
        if kind == "int":
            return expression.value
        if kind == "ref":
            child, names = expression.value
            if not child and len(names) == 1 and not expression.args and names[0] in context.parameters:
                value = context.parameters[names[0]]
                if isinstance(value, int):
                    return value
                raise CompileError("Expected an integer parameter", expression.loc)
            declaration, scope = self.lookup(expression, context)
            if isinstance(declaration, Spec) or declaration.kind != "alias":
                raise CompileError("Expected integer constant", expression.loc)
            return self.alias(declaration, scope, self.constant)
        if kind == "unary" and expression.value in ("+", "-"):
            value = self.constant(expression.args[0], context)
            return value if expression.value == "+" else -value
        if kind == "binary":
            left, right = (self.constant(x, context) for x in expression.args)
            op = expression.value
            if op in ("/", "%") and not right:
                raise CompileError("Constant division by zero", expression.loc)
            if op == "+": return left + right
            if op == "-": return left - right
            if op == "*": return left * right
            quotient = (abs(left) // abs(right)) * (-1 if (left < 0) != (right < 0) else 1)
            return quotient if op == "/" else left - quotient * right
        raise CompileError("Expression is not a constant integer", expression.loc)

    def positive(self, expression, context):
        value = self.constant(expression, context)
        if value <= 0:
            raise CompileError("Width/length must be positive", expression.loc)
        return value

    def type(self, expression, context):
        if expression.kind == "unary" and expression.value == "*":
            return invert(self.type(expression.args[0], context))
        if expression.kind == "array":
            return Array(self.type(expression.args[0], context), self.positive(expression.args[1], context))
        if expression.kind != "ref":
            raise CompileError("Expected a type", expression.loc)
        child, names = expression.value
        if not child and len(names) == 1:
            if names[0] in context.parameters and not expression.args:
                value = context.parameters[names[0]]
                if not isinstance(value, int):
                    return value
            if names[0] in ("In", "Out", "InOut"):
                if len(expression.args) != 2:
                    raise CompileError("Port type requires scalar and width", expression.loc)
                scalar = expression.args[0]
                if scalar.kind != "ref" or scalar.value != (False, ("bit",)) or scalar.args:
                    raise CompileError("Only bit scalar ports are supported", scalar.loc)
                return Port({"In": "in", "Out": "out", "InOut": "inout"}[names[0]],
                            self.positive(expression.args[1], context))
        declaration, scope = self.lookup(expression, context)
        if isinstance(declaration, Spec):
            args = tuple(self.positive(x, context) for x in expression.args)
            return Spec(declaration.name, args, True)
        if declaration.kind == "alias":
            return self.alias(declaration, scope, self.type)
        if declaration.kind == "bundle":
            key = ("bundle", declaration.namespace, declaration.name)
            if key in self.resolving:
                raise CompileError("Recursive bundle", expression.loc)
            self.resolving.add(key)
            try:
                if declaration.value:
                    return self.type(declaration.value, scope)
                fields = []
                for member in declaration.members:
                    if member.name in dict(fields):
                        raise CompileError("Duplicate bundle field", member.loc)
                    fields.append((member.name, self.type(member.type, scope)))
                return Record(tuple(fields))
            finally:
                self.resolving.remove(key)
        if declaration.kind in ("comb", "seq"):
            arguments = []
            for arg in expression.args:
                try:
                    arguments.append(self.constant(arg, context))
                except CompileError as constant_error:
                    try:
                        arguments.append(self.type(arg, context))
                    except CompileError:
                        raise constant_error
            return Spec(declaration.namespace + (declaration.name,), tuple(arguments))
        raise CompileError("Not a type or circuit", expression.loc)
