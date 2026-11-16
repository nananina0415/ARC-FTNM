"""MMO version 1 loader, based on MMIXware's documented load records.

Memory writes/fixups are XOR, not replacement. Special hook payloads are
rejected rather than silently discarded. Symbol-table bytes are retained as
metadata; they are not needed to execute a loaded image.
"""
from dataclasses import dataclass, field


@dataclass
class Image:
    memory: dict = field(default_factory=dict)
    registers: dict = field(default_factory=dict)
    lines: dict = field(default_factory=dict)
    entry: int = 0
    global_threshold: int = 255


def load_mmo(path):
    raw = path.read_bytes()
    offset, location, current_file, line = 0, 0, None, 0
    files = {}
    image = Image()
    def word():
        nonlocal offset
        if offset+4 > len(raw):
            raise ValueError(f"Truncated MMO at {offset}")
        result = int.from_bytes(raw[offset:offset+4], "big")
        offset += 4
        return result
    def xor(address, value, width=4):
        for i, byte in enumerate(value.to_bytes(width, "big")):
            image.memory[address+i] = image.memory.get(address+i, 0) ^ byte
    pre = word()
    if pre >> 16 != 0x9809 or (pre >> 8) & 255 != 1:
        raise ValueError("Expected MMO v1 preamble")
    for _ in range(pre & 255): word()
    while True:
        value = word()
        if value >> 24 != 0x98:
            xor(location, value)
            image.lines[location] = {"file": current_file, "line": line}
            location = (location+4) & ~3
            if line: line += 1
            continue
        op, y, z = (value >> 16) & 255, (value >> 8) & 255, value & 255
        yz = value & 0xffff
        if op == 0:
            if yz != 1: raise ValueError("Invalid MMO quote")
            xor(location, word()); location = (location+4) & ~3
            if line: line += 1
        elif op in (1, 3):
            if z not in (1, 2): raise ValueError("Invalid MMO address record")
            high = (y << 24) + (word() if z == 2 else 0)
            address = (high << 32) | word()
            if op == 1: location = address
            else: xor(address, location, 8)
        elif op == 2:
            location += yz
        elif op in (4, 5):
            if op == 5 and yz not in (16, 24): raise ValueError("Invalid extended fixup")
            delta = word() if op == 5 else yz
            if op == 5 and delta & 0xfe000000: raise ValueError("MMO fixup too large")
            signed_delta = (delta & 0xffffff) - (1 << yz) if delta >= 0x1000000 else delta
            xor(location - 4*signed_delta, delta)
        elif op == 6:
            if z:
                if y in files: raise ValueError("Duplicate MMO file number")
                files[y] = b''.join(word().to_bytes(4,"big") for _ in range(z)).rstrip(b'\0').decode('utf-8')
            if y not in files: raise ValueError("Unknown MMO file number")
            current_file, line = files[y], 0
        elif op == 7:
            if current_file is None: raise ValueError("MMO line without file")
            line = yz
        elif op == 8:
            raise ValueError("MMO special hooks are not supported")
        elif op == 10:
            if y or z < 32: raise ValueError("Invalid MMO postamble")
            image.global_threshold = z
            for register in range(z, 256):
                contents = (word() << 32) | word()
                if register == 255: image.entry = contents
                else: image.registers[register] = contents
            if word() >> 16 != 0x980b: raise ValueError("Missing MMO symbol table")
            if len(raw) < 4 or int.from_bytes(raw[-4:-2],"big") != 0x980c:
                raise ValueError("Missing MMO end record")
            return image
        else:
            raise ValueError(f"Unsupported MMO lopcode {op:#x}")
