from __future__ import annotations

import re

from .model import Connection, Declaration, Expr, Member
from .peg import Parser


class Frontend:
    def __init__(self, grammar, source, filename, namespace):
        self.parser = Parser(grammar, source, filename)
        self.source = source
        self.namespace = namespace

    @staticmethod
    def children(node, rule):
        return [child for child in node.children if child.rule == rule]

    def one(self, node, rule):
        return self.children(node, rule)[0]

    def loc(self, node):
        position = node.start
        while position < node.end:
            if self.source[position].isspace():
                position += 1
            elif self.source.startswith("//", position):
                end = re.search(r"\r\n|\r|\n", self.source[position:])
                position = node.end if end is None else position + end.end()
            else:
                break
        return self.parser.location(position)

    def name(self, node):
        start = self.one(node, "IdentifierStart").start
        return self.source[start:node.end].strip()

    def parse(self):
        root = self.parser.parse()
        declarations = []
        for wrapper in self.children(root, "Declaration"):
            node = wrapper.children[0]
            if node.rule == "AliasDeclaration":
                declarations.extend(self.aliases(node))
                continue
            name = self.name(self.one(node, "Identifier"))
            kind = {"BundleDeclaration": "bundle", "WireDeclaration": "wire"}.get(node.rule)
            if kind is None:
                kind_node = self.one(node, "CircuitKind")
                kind = "seq" if kind_node.children[0].rule == "KSeq" else "comb"
            decl = Declaration(kind, name, self.namespace, self.loc(node))
            params = self.children(node, "Parameters")
            if params:
                decl.params = tuple(self.name(x) for x in self.children(params[0], "Identifier"))
            if kind == "bundle":
                types = self.children(node, "Type")
                if types:
                    decl.value = self.expression(types[0])
                else:
                    body = self.one(node, "BundleBody")
                    for field in self.children(body, "BundleField"):
                        decl.members.append(self.member(field))
            elif kind == "wire":
                for wrapper in self.children(node, "WireStatement"):
                    statement = wrapper.children[0]
                    if statement.rule == "AliasDeclaration":
                        decl.body.extend(self.aliases(statement))
                    else:
                        arrow = self.one(statement, "Arrow")
                        text = self.source[arrow.start:arrow.end].strip()
                        # A preceding comment may itself contain arrow characters.
                        text = re.sub(r"//[^\r\n]*(?:\r\n|\r|\n|$)", "", text).strip()
                        decl.body.append(Connection(
                            self.expression(self.one(statement, "Endpoint")), text,
                            self.expression(self.one(statement, "RightEndpoint")), self.loc(statement)))
            else:
                for wrapper in self.children(node, "Member"):
                    item = wrapper.children[0]
                    if item.rule == "AliasDeclaration":
                        decl.body.extend(self.aliases(item))
                    else:
                        decl.members.append(self.member(item))
            declarations.append(decl)
        return declarations

    def aliases(self, node):
        blocks = self.children(node, "AliasBlock")
        bindings = self.children(blocks[0] if blocks else node, "AliasBinding")
        return [Declaration("alias", self.name(self.one(binding, "Identifier")), self.namespace,
                            self.loc(binding), value=self.expression(self.one(binding, "RightExpression")))
                for binding in bindings]

    def member(self, node):
        modifiers = self.children(node, "Modifier")
        modifier = None
        if modifiers:
            modifier = "pub" if modifiers[0].children[0].rule == "KPub" else "mut"
        return Member(self.name(self.one(node, "Identifier")), self.expression(self.one(node, "Type")),
                      modifier, self.loc(node))

    def expression(self, node):
        kind, children, loc = node.rule, node.children, self.loc(node)
        if kind == "Integer":
            token = next(c for c in children if c.rule in ("Hex", "Binary", "Decimal"))
            text = self.source[token.start:token.end]
            return Expr("int", int(text, 0) if text.lower().startswith(("0x", "0b")) else int(text), loc=loc)
        if kind in ("Reference", "RightReference", "NamedType"):
            path = self.one(node, "NamePath")
            names = tuple(self.name(x) for x in self.children(path, "Identifier"))
            absolute_child = bool(path.children and path.children[0].rule == "Namespace")
            args = self.children(node, "Arguments")
            value = Expr("ref", (absolute_child, names),
                         [self.expression(x) for x in self.children(args[0], "Expression")] if args else [], loc)
            for wrapper in self.children(node, "Selector"):
                selector = wrapper.children[0]
                if selector.rule == "Field":
                    value = Expr("field", self.name(self.one(selector, "Identifier")), [value], loc)
                else:
                    indices = []
                    if self.children(selector, "WholeRange"):
                        indices.append(Expr("all", loc=self.loc(selector)))
                    for item in self.children(selector, "IndexItem"):
                        expressions = [self.expression(x) for x in self.children(item, "Expression")]
                        indices.append(Expr("range", args=expressions, loc=self.loc(item))
                                       if self.children(item, "Range") else expressions[0])
                    value = Expr("index", args=[value] + indices, loc=loc)
            return value
        if kind in ("PackedList", "FanoutList"):
            return Expr("pack" if kind == "PackedList" else "fanout", args=[
                self.expression(x) for x in self.children(node, "Expression")], loc=loc)
        if kind == "ArrayType":
            return Expr("array", args=[self.expression(self.one(node, "Type")),
                                       self.expression(self.one(node, "Expression"))], loc=loc)
        if kind == "Type":
            value = self.expression(next(c for c in children if c.rule in ("ArrayType", "NamedType")))
            for _ in self.children(node, "Reverse"):
                value = Expr("unary", "*", [value], loc)
            return value
        if kind == "Call":
            return Expr("call", self.name(self.one(node, "Identifier")),
                        [self.expression(x) for x in self.children(node, "Expression")], loc)
        if kind in ("Unary", "RightUnary"):
            if len(children) > 1:
                op = {"Plus": "+", "Minus": "-", "Reverse": "*"}[children[0].rule]
                return Expr("unary", op, [self.expression(children[1])], loc)
            return self.expression(children[0])
        if kind in ("Additive", "Multiplicative", "RightAdditive", "RightMultiplicative"):
            value = self.expression(children[0])
            operators = {"Plus": "+", "Minus": "-", "Multiply": "*", "Divide": "/", "Modulo": "%"}
            for i in range(1, len(children), 2):
                value = Expr("binary", operators[children[i].rule],
                             [value, self.expression(children[i + 1])], loc)
            return value
        # Transparent grammar wrappers, excluding lexical spacing and delimiters.
        relevant = [c for c in children if c.rule not in ("S", "H", "LParen", "RParen")]
        if len(relevant) == 1:
            return self.expression(relevant[0])
        raise ValueError(f"Unhandled grammar node {kind}")
