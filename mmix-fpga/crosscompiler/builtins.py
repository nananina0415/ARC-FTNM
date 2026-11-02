"""Standard structural primitives, independent of MMIX instructions."""
from .model import CompileError
from .typesys import Array, Port, Record


def identifier(parts):
    # Length-prefixed components are unambiguous, including underscores/digits.
    return "s_" + "_".join(f"{len(str(x))}_{x}" for x in parts)


def interface(spec):
    name, args = spec.name[-1], spec.arguments
    fields = []
    def port(key, direction, width=1):
        fields.append((key, Port(direction, width)))
    if name == "D_FlipFlop":
        port("clock", "in"); port("in", "in"); port("out", "out")
    elif name == "Mux":
        count, width = args
        port("select", "in", max(1, (count - 1).bit_length()))
        fields.append(("option", Array(Port("in", width), count)))
        port("selected", "out", width)
    elif name == "Decoder":
        inputs, outputs = args
        if outputs > 2 ** inputs:
            raise CompileError("Decoder output count exceeds its input range")
        port("in", "in", inputs); port("out", "out", outputs)
    elif name == "TriState":
        width, = args
        port("enable", "in"); port("in", "in", width); port("out", "out", width)
    elif name == "Adder":
        width, = args
        port("a", "in", width); port("b", "in", width); port("c_in", "in")
        port("sum", "out", width); port("c_out", "out")
    elif name == "Multiplier":
        width, = args
        port("a", "in", width); port("b", "in", width); port("signed", "in")
        port("lo", "out", width); port("hi", "out", width)
    elif name == "Divider":
        width, = args
        port("dividend", "in", 2 * width); port("divisor", "in", width)
        port("quotient", "out", width); port("remainder", "out", width)
    else:
        raise CompileError(f"Unknown primitive {name}")
    return Record(tuple(fields))


def body(spec):
    name, args = spec.name[-1], spec.arguments
    def p(*path):
        return identifier(("port",) + path)
    if name == "D_FlipFlop":
        return ["reg q;", f"always @(posedge {p('clock')}) q <= {p('in')};", f"assign {p('out')} = q;"]
    if name == "Mux":
        count, width = args
        select_width = max(1, (count - 1).bit_length())
        result = [f"reg [{width - 1}:0] choice;", "always @* begin", f"  case ({p('select')})"]
        result += [f"    {select_width}'d{i}: choice = {p('option', str(i))};" for i in range(count)]
        result += [f"    default: choice = {width}'bx;", "  endcase", "end", f"assign {p('selected')} = choice;"]
        return result
    if name == "Decoder":
        _, outputs = args
        return [f"assign {p('out')} = ((^{p('in')}) === 1'bx) ? {outputs}'bx : ({outputs}'d1 << {p('in')});"]
    if name == "TriState":
        return [f"assign {p('out')} = {p('enable')} ? {p('in')} : {args[0]}'bz;"]
    if name == "Adder":
        return [f"assign {{{p('c_out')}, {p('sum')}}} = {{1'b0, {p('a')}}} + {{1'b0, {p('b')}}} + {{{args[0]}'b0, {p('c_in')}}};"]
    if name == "Multiplier":
        width, = args
        sign_a = "{{%d{%s[%d]}}, %s}" % (width, p("a"), width - 1, p("a"))
        sign_b = "{{%d{%s[%d]}}, %s}" % (width, p("b"), width - 1, p("b"))
        return [f"wire signed [{2 * width - 1}:0] sa = $signed({sign_a});",
                f"wire signed [{2 * width - 1}:0] sb = $signed({sign_b});",
                f"wire [{2 * width - 1}:0] product = {p('signed')} ? $unsigned(sa * sb) : ({{{width}'b0, {p('a')}}} * {{{width}'b0, {p('b')}}});",
                f"assign {{{p('hi')}, {p('lo')}}} = product;"]
    if name == "Divider":
        width, = args
        return [f"wire [{2 * width - 1}:0] divisor_ext = {{{width}'b0, {p('divisor')}}};",
                f"wire [{2 * width - 1}:0] quotient_full = ({p('divisor')} == {width}'d0) ? {2 * width}'bx : ({p('dividend')} / divisor_ext);",
                f"wire [{2 * width - 1}:0] remainder_full = ({p('divisor')} == {width}'d0) ? {2 * width}'bx : ({p('dividend')} % divisor_ext);",
                f"assign {p('quotient')} = quotient_full[{width - 1}:0];",
                f"assign {p('remainder')} = remainder_full[{width - 1}:0];"]
    raise CompileError(f"No backend for primitive {name}")
