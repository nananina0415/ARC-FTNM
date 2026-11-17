from __future__ import annotations

from collections import defaultdict

from . import builtins
from .ir import Gate, Instance, Module, Pin, Vector
from .model import CompileError, Declaration
from .typesys import SEQUENTIAL_PRIMITIVES, Array, Context, Port, Record, Spec, leaves


OPEN = object()


def flatten(value):
    if isinstance(value, Vector):
        return [value]
    if isinstance(value, list):
        return [part for element in value for part in flatten(element)]
    raise CompileError("Expected a signal or a signal array")


def packed(value, location):
    try:
        parts = flatten(value)
    except CompileError:
        raise CompileError("Expected a packed signal", location)
    return Vector(tuple(bit for part in parts for bit in part.bits),
                  all(x.read for x in parts), all(x.write for x in parts))


class Elaborator:
    def __init__(self, symbols):
        self.symbols = symbols
        self.modules = {}
        self.active = set()

    def compile(self, spec):
        if spec in self.active or len(self.active) >= 128:
            raise CompileError(f"Recursive or excessively deep circuit: {'::'.join(spec.name)}")
        if spec in self.modules:
            return self.modules[spec]
        self.active.add(spec)
        try:
            module = self._compile(spec)
            self.modules[spec] = module
            return module
        finally:
            self.active.remove(spec)

    def _compile(self, spec):
        if spec.builtin:
            module = Module(spec, builtins.interface(spec), spec.name[-1] in SEQUENTIAL_PRIMITIVES)
            ports = list(leaves(module.interface))
            if not module.sequential:
                for out_path, output in ports:
                    if output.direction == "out":
                        for in_path, input_ in ports:
                            if input_.direction == "in":
                                for out_bit in range(output.width):
                                    for in_bit in range(input_.width):
                                        name = spec.name[-1]
                                        relevant = True
                                        if name == "TriState":
                                            relevant = in_path == ("enable",) or in_bit == out_bit
                                        elif name == "Mux":
                                            relevant = in_path == ("select",) or in_bit == out_bit
                                        elif name == "Adder":
                                            relevant = in_path == ("c_in",) or out_path == ("c_out",) or in_bit <= out_bit
                                        elif name == "Multiplier" and out_path == ("lo",):
                                            relevant = in_path != ("signed",) and in_bit <= out_bit
                                        if relevant:
                                            module.dependencies.add((in_path, in_bit, out_path, out_bit))
            if spec.name[-1] == "TriState":
                module.tri_outputs.add(("out",))
            return module
        declaration = self.symbols.definitions[spec.name]
        if len(declaration.params) != len(spec.arguments):
            raise CompileError("Generic argument count mismatch", declaration.loc)
        wire = self.symbols.wires[spec.name]
        context = Context(declaration.namespace, dict(zip(declaration.params, spec.arguments)))
        for item in declaration.body + wire.body:
            if isinstance(item, Declaration):
                if item.name in context.aliases or item.name in context.parameters:
                    raise CompileError(f"Duplicate local name {item.name}", item.loc)
                context.aliases[item.name] = item
        resolved = []
        for member in declaration.members:
            if member.name in context.values or member.name in context.aliases or member.name in context.parameters:
                raise CompileError(f"Duplicate member {member.name}", member.loc)
            type_ = self.symbols.type(member.type, context)
            resolved.append((member, type_))
            # Reserve names before allocation, including later members.
            context.values[member.name] = None
        public = []
        for member, type_ in resolved:
            if member.modifier == "pub":
                if isinstance(type_, Spec):
                    raise CompileError("pub requires a bundle/port type", member.loc)
                public.append((member.name, type_))
        module = Module(spec, Record(tuple(public)), declaration.kind == "seq", declaration.loc)
        for member, type_ in resolved:
            context.values[member.name] = self.allocate(module, type_, (member.name,),
                                                        member.modifier, member.loc)
        for statement in wire.body:
            if not isinstance(statement, Declaration):
                self.connection(module, context, statement)
        self.validate(module)
        return module

    def allocate(self, module, type_, path, modifier, location, instance=None):
        if isinstance(type_, Spec):
            child = self.compile(type_)
            if child.sequential and not module.sequential:
                raise CompileError("comb cannot contain a sequential circuit", location)
            if child.sequential and not (type_.builtin and type_.name[-1] == "D_FlipFlop") and modifier != "mut":
                raise CompileError("Sequential instance requires mut", location)
            if modifier == "mut" and not child.sequential:
                raise CompileError("mut requires a sequential circuit", location)
            item = Instance(path, child, [], location)
            module.instances.append(item)
            return self.allocate(module, child.interface, (), None, location, item)
        if isinstance(type_, Record):
            return {name: self.allocate(module, field, path + (name,), modifier, location, instance)
                    for name, field in type_.fields}
        if isinstance(type_, Array):
            return [self.allocate(module, type_.element, path + (str(i),), modifier, location, instance)
                    for i in range(type_.length)]
        if not isinstance(type_, Port):
            raise CompileError("Unknown member type", location)
        if instance is None and modifier != "pub":
            raise CompileError("Signal members must be public ports; use wire aliases internally", location)
        if instance is not None and modifier == "mut":
            raise CompileError("mut cannot qualify a port", location)
        bits = module.nets.allocate(type_.width)
        owner = builtins.identifier(("instance",) + instance.path) if instance is not None else None
        tri = instance is not None and (path in instance.module.tri_outputs or type_.direction == "inout")
        pin = Pin(path, type_.direction, bits, owner, location, tri)
        module.pins.append(pin)
        if instance is None:
            module.ports.append(pin)
        else:
            instance.pins.append(pin)
        source = (type_.direction == "in") if instance is None else (type_.direction == "out")
        sink = (type_.direction == "out") if instance is None else (type_.direction == "in")
        # Connected pin nets can be tapped, including a child's input pin.
        # Direction controls who may drive; validate() checks that a driver exists.
        return Vector(bits, True, sink or type_.direction == "inout")

    def expression(self, expression, context, module):
        kind, args = expression.kind, expression.args
        if kind in ("int", "binary", "unary"):
            return self.symbols.constant(expression, context)
        if kind == "ref":
            child, names = expression.value
            if not child and len(names) == 1 and not args:
                if names[0] == "OPENED":
                    return OPEN
                if names[0] in context.values:
                    return context.values[names[0]]
                if names[0] in context.parameters:
                    value = context.parameters[names[0]]
                    if isinstance(value, int): return value
                    raise CompileError("Type parameter is not a signal", expression.loc)
            declaration, scope = self.symbols.lookup(expression, context)
            if isinstance(declaration, Spec) or declaration.kind != "alias":
                raise CompileError("A type/circuit cannot be used as a signal", expression.loc)
            def evaluate(value, alias_context):
                return self.expression(value, alias_context, module)
            return self.symbols.alias(declaration, scope, evaluate)
        if kind == "field":
            base = self.expression(args[0], context, module)
            def field(item):
                if isinstance(item, list): return [field(element) for element in item]
                if not isinstance(item, dict) or expression.value not in item:
                    raise CompileError(f"Unknown field {expression.value}", expression.loc)
                return item[expression.value]
            return field(base)
        if kind == "index":
            base = self.expression(args[0], context, module)
            if not isinstance(base, (Vector, list)):
                raise CompileError("Cannot index this expression", expression.loc)
            size = len(base.bits) if isinstance(base, Vector) else len(base)
            indices = []
            for item in args[1:]:
                if item.kind == "all":
                    indices.extend(range(size))
                elif item.kind == "range":
                    start, end = [self.symbols.constant(x, context) for x in item.args]
                    if start < 0 or end <= start or end > size:
                        raise CompileError("Invalid index range", item.loc)
                    indices.extend(range(start, end))
                else:
                    indices.append(self.symbols.constant(item, context))
            if not indices or any(index < 0 or index >= size for index in indices):
                raise CompileError(f"Index outside [0,{size})", expression.loc)
            if isinstance(base, Vector):
                return Vector(tuple(base.bits[i] for i in indices), base.read, base.write, True)
            selected = [base[i] for i in indices]
            # A single array index selects one element, a range/list retains array shape.
            single = len(args) == 2 and args[1].kind not in ("all", "range")
            return selected[0] if single else selected
        if kind == "pack":
            values = []
            for item in args:
                value = self.expression(item, context, module)
                if isinstance(value, int):
                    if value not in (0, 1):
                        raise CompileError("Packed integer literals must be a single bit", item.loc)
                    value = self.constant_vector(module, value, 1, item.loc)
                values.append(value)
            return packed(values, expression.loc)
        if kind == "fanout":
            result = []
            for item in args:
                value = self.expression(item, context, module)
                for part in flatten(value):
                    result.extend(Vector((bit,), part.read, part.write) for bit in part.bits) if part.spread else result.append(part)
            return result
        if kind == "call":
            inputs = []
            for item in args:
                value = self.expression(item, context, module)
                if isinstance(value, int):
                    if value not in (0, 1):
                        raise CompileError("Gate constants must be 0 or 1", item.loc)
                    value = self.constant_vector(module, value, 1, item.loc)
                for part in flatten(value):
                    inputs.extend(Vector((bit,), part.read, part.write) for bit in part.bits) if part.spread else inputs.append(part)
            if expression.value not in ("and", "or", "xor", "not") or not inputs:
                raise CompileError("Unknown gate or empty gate input", expression.loc)
            if expression.value == "not" and len(inputs) != 1:
                raise CompileError("not requires one packed input", expression.loc)
            width = len(inputs[0].bits)
            if any(len(item.bits) != width or not item.read for item in inputs):
                raise CompileError("Gate input width/direction mismatch", expression.loc)
            bits = module.nets.allocate(width)
            module.gates.append(Gate(expression.value, [item.bits for item in inputs], bits, expression.loc))
            module.touched.update(bit for item in inputs for bit in item.bits)
            return Vector(bits, True, False)
        raise CompileError(f"Unsupported signal expression: {kind}", expression.loc)

    def constant_vector(self, module, value, width, location):
        if value is not OPEN and (value < 0 or value >= 1 << width):
            raise CompileError(f"Constant does not fit {width} bits", location)
        bits = module.nets.allocate(width)
        module.constants.append((bits, value, location))
        return Vector(bits, True, False)

    def connection(self, module, context, connection):
        left = self.expression(connection.left, context, module)
        right = self.expression(connection.right, context, module)
        arrow = connection.arrow
        if arrow == "<=>":
            if isinstance(left, list):
                pairs = [(item, right) for item in left]
            elif isinstance(right, list):
                pairs = [(left, item) for item in right]
            else:
                pairs = [(left, right)]
            for a, b in pairs:
                a, b = packed(a, connection.loc), packed(b, connection.loc)
                if not (a.read and a.write and b.read and b.write):
                    raise CompileError("<=> requires bidirectional endpoints", connection.loc)
                self.join(module, a, b, connection.loc)
            return
        source, destination = (right, left) if arrow in ("<-", "<=") else (left, right)
        broadcast = arrow in ("=>", "<=")
        if isinstance(destination, list) and broadcast:
            pairs = [(source, item) for item in destination]
        elif isinstance(source, list) and broadcast:
            pairs = [(item, destination) for item in source]
        elif broadcast:
            raise CompileError("Broadcast requires a {...} list", connection.loc)
        else:
            if connection.left.kind == "fanout" or connection.right.kind == "fanout":
                raise CompileError("Use broadcast arrows with {...} lists", connection.loc)
            pairs = [(source, destination)]
        for source, destination in pairs:
            if destination is OPEN:
                source = packed(source, connection.loc)
                if not source.read:
                    raise CompileError("Only readable output can be discarded", connection.loc)
                module.opened.update(source.bits)
                continue
            destination = packed(destination, connection.loc)
            if not destination.write:
                raise CompileError("Destination is not writable", connection.loc)
            if isinstance(source, int) or source is OPEN:
                source = self.constant_vector(module, source, len(destination.bits), connection.loc)
            else:
                source = packed(source, connection.loc)
            if not source.read:
                raise CompileError("Source is not readable", connection.loc)
            self.join(module, source, destination, connection.loc)

    def join(self, module, source, destination, location):
        if len(source.bits) != len(destination.bits):
            raise CompileError(f"Width mismatch: {len(source.bits)} vs {len(destination.bits)}", location)
        if len(set(destination.bits)) != len(destination.bits):
            raise CompileError("Duplicate destination bit", location)
        for a, b in zip(source.bits, destination.bits):
            module.nets.join(a, b)
        module.touched.update(source.bits + destination.bits)

    def validate(self, module):
        nets = module.nets
        drivers = defaultdict(list)
        for pin in module.pins:
            is_driver = (pin.owner is None and pin.direction == "in") or (pin.owner is not None and pin.direction == "out")
            if is_driver or pin.direction == "inout":
                for bit in pin.bits:
                    drivers[nets.find(bit)].append((bit, pin.tristate or pin.direction == "inout", pin.location))
        for gate in module.gates:
            for bit in gate.output:
                drivers[nets.find(bit)].append((bit, False, gate.location))
        for bits, value, loc in module.constants:
            for bit in bits:
                drivers[nets.find(bit)].append((bit, value is OPEN, loc))
        for root, entries in drivers.items():
            ordinary = [entry for entry in entries if not entry[1]]
            if len(ordinary) > 1:
                raise CompileError(f"Multiple ordinary drivers on net {root}; other driver at {ordinary[0][2]}", ordinary[1][2])
            if ordinary and len(entries) > 1:
                module.warnings.append(f"{ordinary[0][2]}: ordinary and tristate drivers share net {root}")
        for pin in module.pins:
            sink = (pin.owner is None and pin.direction == "out") or (pin.owner is not None and pin.direction == "in")
            for bit in pin.bits:
                if sink and not drivers[nets.find(bit)]:
                    raise CompileError(f"Undriven required pin {pin.owner or 'self'}.{'.'.join(pin.path)}", pin.location)
                if pin.owner is not None and pin.direction == "out" and bit not in module.touched and bit not in module.opened:
                    raise CompileError(f"Unused output {pin.owner}.{'.'.join(pin.path)}[{pin.bits.index(bit)}]; mark OPENED", pin.location)
            if pin.owner is None and pin.direction == "out":
                if all(drivers[nets.find(bit)] and all(entry[1] for entry in drivers[nets.find(bit)]) for bit in pin.bits):
                    module.tri_outputs.add(pin.path)
        # Shared net roots are vertices; port summaries retain individual bit indices.
        # DFFs contribute no combinational edges, so sequential feedback is legal.
        edges = defaultdict(set)
        for gate in module.gates:
            for source_bits in gate.inputs:
                for source, destination in zip(source_bits, gate.output):
                    edges[nets.find(destination)].add(nets.find(source))
        for instance in module.instances:
            pins = {pin.path: pin for pin in instance.pins}
            for input_path, input_bit, output_path, output_bit in instance.module.dependencies:
                source = pins[input_path].bits[input_bit]
                destination = pins[output_path].bits[output_bit]
                edges[nets.find(destination)].add(nets.find(source))
        visiting, memo = set(), {}
        def ancestors(root):
            if root in visiting:
                raise CompileError(f"Combinational loop at net {root}", module.location)
            if root not in memo:
                visiting.add(root)
                found = {root}
                for source in sorted(edges[root]):
                    found.update(ancestors(source))
                visiting.remove(root)
                memo[root] = found
            return memo[root]
        # Iterate over a snapshot: accessing an absent key can grow defaultdict.
        for root in sorted(set(edges) | {x for values in edges.values() for x in values}):
            ancestors(root)
        inputs = defaultdict(set)
        for pin in module.ports:
            if pin.direction in ("in", "inout"):
                for index, bit in enumerate(pin.bits):
                    inputs[nets.find(bit)].add((pin.path, index))
        for output in module.ports:
            if output.direction not in ("out", "inout"):
                continue
            for output_index, bit in enumerate(output.bits):
                for root in ancestors(nets.find(bit)):
                    for input_path, input_index in inputs[root]:
                        if output.direction == "inout" and (input_path, input_index) == (output.path, output_index):
                            continue
                        module.dependencies.add((input_path, input_index, output.path, output_index))
