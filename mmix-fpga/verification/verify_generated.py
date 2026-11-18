"""Independent HDL checks of generated RTL. Run only when explicitly requested.

Uses source-map for generated identifiers, never the compiler's evaluator as
the arithmetic oracle. Requires pyslang plus Icarus Verilog for simulation.
"""
from pathlib import Path
import argparse
import json
import os
import random
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".tools" / "hdl"))
MASK = (1 << 64) - 1


def enc(*parts):
    return "s_" + "_".join(f"{len(str(x))}_{x}" for x in parts)


def cache_ready():
    """Both CPU caches clear their valid bits for 512 clocks after reset; wait on this before running."""
    ready = lambda unit: f"dut.{enc('instance', unit)}.{enc('instance', 'cache')}.{enc('port', 'ready')}"
    return f"({ready('fetch')} && {ready('memory')})"


def signed(value):
    return value - (1 << 64) if value >> 63 else value


class Checks:
    def __init__(self, rtl, output, iverilog):
        self.rtl, self.output, self.iverilog = rtl, output, iverilog
        self.mapping = json.loads((rtl / "source-map.json").read_text(encoding="utf-8"))
        self.modules = self.mapping["modules"]
        self.sources = [str((rtl / name).resolve()) for name in (rtl / "sources.f").read_text().splitlines()]
        self.results = {}

    def find(self, source):
        return next(m for m in self.modules if m["source"] and m["source"]["file"] == source)

    def declaration(self, module):
        lines, connections = [], []
        for port in module["ports"]:
            kind = "reg" if port["direction"] == "in" else "wire"
            lines.append(f"{kind} [{port['width']-1}:0] {port['name']};")
            connections.append(f".{port['name']}({port['name']})")
        lines.append(f"{module['module']} dut ({', '.join(connections)});")
        return lines

    def simulate(self, name, text):
        tb = self.output / f"{name}.sv"
        tb.write_text(text, encoding="utf-8", newline="\n")
        executable = self.output / f"{name}.vvp"
        args_file = self.output / f"{name}.f"
        args_file.write_text("\n".join(s.replace("\\", "/") for s in self.sources + [str(tb.resolve())]) + "\n", encoding="utf-8")
        command = [str(self.iverilog), "-g2012", "-s", "tb", "-o", str(executable)] + self.sources + [str(tb.resolve())]
        compiled = subprocess.run(command, capture_output=True, text=True, timeout=120)
        (self.output / f"{name}-compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        if compiled.returncode:
            raise RuntimeError(f"{name}: HDL compilation failed\n{compiled.stderr}")
        runtime = self.iverilog.with_name("vvp.exe" if os.name == "nt" else "vvp")
        run = subprocess.run([str(runtime), str(executable)], capture_output=True, text=True, timeout=120)
        (self.output / f"{name}.log").write_text(run.stdout + run.stderr, encoding="utf-8")
        if run.returncode or "PASS" not in run.stdout:
            raise RuntimeError(f"{name}: simulation failed\n{run.stdout}\n{run.stderr}")
        self.results[name] = run.stdout.strip()
        print(run.stdout.strip(), flush=True)

    def lint(self):
        import pyslang
        compilation = pyslang.ast.Compilation()
        for source in self.sources:
            compilation.addSyntaxTree(pyslang.syntax.SyntaxTree.fromFile(source))
        diagnostics = compilation.getAllDiagnostics()
        report = pyslang.DiagnosticEngine.reportAll(compilation.sourceManager, diagnostics)
        (self.output / "slang.log").write_text(report, encoding="utf-8")
        self.results["slang_diagnostics"] = len(diagnostics)
        if diagnostics:
            raise RuntimeError(report)
        print(f"PASS syntax/elaboration: {len(self.sources)} modules, zero diagnostics", flush=True)

    def parity(self):
        sys.path.insert(0, str(ROOT))
        from crosscompiler.compiler import Compiler
        compiler = Compiler(ROOT / "pseudo", ROOT / "pseudo/grammar.peg")
        first, _, warnings = compiler.build("cpu::Cpu")
        second, _, _ = compiler.build("cpu::Cpu")
        if warnings or first != second:
            raise RuntimeError("Compiler output is non-deterministic or has warnings")
        for name, contents in first.items():
            if (self.rtl / name).read_text(encoding="utf-8") != contents:
                raise RuntimeError(f"Generated RTL differs from current source/compiler: {name}")
        self.results["source_parity"] = f"{len(first)} matching artifacts, two identical independent builds"
        print(f"PASS source parity: {len(first)} artifacts, deterministic output", flush=True)

    def alu(self):
        module = self.find("cpu/alu/alu.pseudo")
        # alu.pseudo contains both Alu and ExcCheck; select Alu by its arg1 port.
        module = next(m for m in self.modules if any(p["path"] == ["arg1"] for p in m["ports"])
                      and m["source"] and m["source"]["file"] == "cpu/alu/alu.pseudo")
        lines = ["`timescale 1ns/1ps", "module tb;"] + self.declaration(module)
        lines += ["integer count=0;", "initial begin"]
        rng = random.Random(20261102)
        operations = [(0, op) for op in range(10)] + [(unit, op) for unit in (1, 2, 3) for op in range(2)]
        operations += [(4, op) for op in (0, 1, 2, 3, 4, 5, 6, 7, 12)] + [(5, op) for op in range(4)] + [(6, op) for op in range(16)]
        edge = [0, 1, 2, 63, 64, 65, (1 << 63)-1, 1 << 63, MASK-1, MASK]
        cases = 0
        def assign(path, value, width=64):
            return f"{enc('port', *path)} = {width}'h{value:x};"
        def check(path, value, width=64):
            return f"if ({enc('port', *path)} !== {width}'h{value:x}) $fatal(1, \"ALU case {cases}: {'.'.join(path)}\");"
        for unit, op in operations:
            vectors = [(a, b) for a in edge for b in edge]
            vectors += [(rng.getrandbits(64), rng.getrandbits(64)) for _ in range(64)]
            for a, b in vectors:
                rd, mask, ra, enable = rng.getrandbits(64), rng.getrandbits(64), rng.randrange(16), rng.randrange(2)
                if unit == 2 and op == 1 and cases % 2:
                    rd = rng.randrange(b) if b else 0
                result, overflow, divide_zero, write, high, rem = 0, False, False, True, 0, 0
                sa, sb = signed(a), signed(b)
                if unit == 0:
                    result = a + b if op < 2 else a - b if op in (2, 3, 8, 9) else (a << (op - 3)) + b
                    exact = sa + sb if op == 0 else sa - sb
                    overflow = op in (0, 2, 8) and not -(1 << 63) <= exact < (1 << 63)
                elif unit == 1:
                    full = sa * sb if op == 0 else a * b
                    result, high = full & MASK, (full >> 64) & MASK
                    overflow = op == 0 and not -(1 << 63) <= full < (1 << 63)
                elif unit == 2:
                    if op == 0:
                        result, rem = (sa // sb, sa % sb) if b else (0, a)
                        divide_zero = b == 0
                        overflow = a == 1 << 63 and b == MASK
                    elif rd >= b:
                        result, rem = rd, a
                    else:
                        result, rem = divmod((rd << 64) | a, b)
                elif unit == 3:
                    x, y = (sa, sb) if op == 0 else (a, b)
                    result = (x > y) - (x < y)
                elif unit == 4:
                    result = {0: a | b, 1: a | ~b, 2: ~(a | b), 3: a ^ b,
                              4: a & b, 5: a & ~b, 6: ~(a & b), 7: ~(a ^ b),
                              12: (a & mask) | (b & ~mask)}[op]
                elif unit == 5:
                    if op < 2:
                        result = (a << b) & MASK if b < 64 else 0
                        overflow = op == 0 and ((a != 0) if b >= 64 else not -(1 << 63) <= sa * (1 << b) < (1 << 63))
                    else:
                        result = (sa >> min(b, 64)) if op == 2 else (a >> min(b, 64))
                else:
                    condition = [sa < 0, a == 0, sa > 0, bool(a & 1), sa >= 0, a != 0, sa <= 0, not(a & 1)][op & 7]
                    result, write = b if condition else 0, bool(condition or op & 8)
                trip = (overflow and bool(ra & 4)) or (divide_zero and bool(ra & 8))
                ra_write = (overflow and not(ra & 4)) or (divide_zero and not(ra & 8))
                ra_out = ra | (1 if overflow and not(ra & 4) else 0) | (2 if divide_zero and not(ra & 8) else 0)
                lines += [assign(("alu_sel",), unit, 3), assign(("op",), op, 4), assign(("arg1",), a), assign(("arg2",), b),
                          assign(("write_en",), enable, 1), assign(("spec_direct", "rA_in"), ra, 4),
                          assign(("spec_direct", "rD_in"), rd), assign(("spec_direct", "rM_in"), mask), "#1;",
                          check(("res",), result & MASK), check(("res_w_en",), int(write and enable), 1),
                          check(("trip_req",), int(trip), 1), check(("spec_direct", "rA_out"), ra_out, 4),
                          check(("spec_direct", "rA_w_en"), int(ra_write and enable), 1),
                          check(("spec_direct", "rH_w_en"), int(unit == 1 and op == 1 and enable), 1),
                          check(("spec_direct", "rR_w_en"), int(unit == 2 and enable), 1)]
                if unit == 1:
                    lines.append(check(("spec_direct", "rH_out"), high))
                if unit == 2:
                    lines.append(check(("spec_direct", "rR_out"), rem & MASK))
                cases += 1
        lines += [f'$display("PASS ALU: {cases} independent vectors");', "$finish; end endmodule"]
        self.simulate("alu", "\n".join(lines))

    def cpu(self):
        module = next(m for m in self.modules if m["module"] == self.mapping["top"])
        p = lambda *path: enc("port", *path)
        reg = "dut." + enc("instance", "reg") + "."
        lines = ["`timescale 1ns/1ps", "module tb;"] + self.declaration(module)
        clock, reset, calib = p("clock"), p("reset"), p("init_calib_complete")
        arvalid, arready, araddr = [p("axi", "read", x) for x in ("arvalid", "arready", "araddr")]
        rvalid, rready, rdata, rresp, rid, rlast = [p("axi", "read", x) for x in ("rvalid", "rready", "rdata", "rresp", "rid", "rlast")]
        lines += [f"initial {clock}=0; always #5 {clock}=~{clock};",
                  "reg [7:0] memory[0:255]; integer expected_dest[0:63]; reg [63:0] expected_value[0:63];",
                  "integer cycle=0, pending=0, delay_count=0, saved_address=0, requests=0, writes=0, j=0;",
                  "integer mode=0, expected_writes=0, expected_requests=0; reg stalled=0; reg [29:0] last_address;",
                  f"always @(negedge {clock}) begin",
                  f"  if ({reset}) begin {arready}=0; {rvalid}=0; end else begin",
                  f"    {arready}=(!pending && !{rvalid} && cycle%4==2);",
                  f"    if(pending && !{rvalid}) begin",
                  "      if(delay_count>0) delay_count=delay_count-1; else begin",
                  f"        for(j=0;j<32;j=j+1) {rdata}[j*8 +: 8]=memory[saved_address+j];",
                  f"        {rid}=(mode==3)?4'd1:4'd0; {rresp}=(mode==2)?2'b10:2'b00; {rlast}=1; {rvalid}=1;",
                  "      end end end end",
                  f"always @(posedge {clock}) begin",
                  f"  if({reset}) begin cycle=0;pending=0;requests=0;writes=0;stalled=0; end else begin",
                  "    cycle=cycle+1;",
                  f"    if(!{calib} && {arvalid}) $fatal(1,\"request before calibration\");",
                  f"    if(stalled && (!{arvalid} || {araddr}!==last_address)) $fatal(1,\"AR changed under backpressure\");",
                  f"    stalled={arvalid} && !{arready}; last_address={araddr};",
                  f"    if({arvalid} && {arready}) begin",
                  f"      if({araddr} !== (requests*32)) $fatal(1,\"wrong fetch cache-miss block\");",
                  f"      if({p('axi','read','arsize')}!==3'd5 || {p('axi','read','arlen')}!==0 || {p('axi','read','arburst')}!==1 || {p('axi','read','arid')}!==0) $fatal(1,\"AXI shape\");",
                  f"      pending=1;delay_count=3;saved_address={araddr};requests=requests+1;end",
                  f"    if({rvalid} && {rready}) begin pending=0;{rvalid}<=0;end",
                  f"    if({reg}{p('general','w_en')}) begin",
                  f"      if(writes>=expected_writes || {reg}{p('general','w_sel')}!==expected_dest[writes][7:0] || {reg}{p('general','w_data')}!==expected_value[writes]) $fatal(1,\"writeback mismatch at %0d\", writes);",
                  "      writes=writes+1; end",
                  f"    if({p('axi','write','awvalid')} || {p('axi','write','wvalid')}) $fatal(1,\"unexpected AXI write\");",
                  "  end end",
                  "task instruction; input integer address; input [31:0] word; begin",
                  "memory[address]=word[31:24];memory[address+1]=word[23:16];memory[address+2]=word[15:8];memory[address+3]=word[7:0];end endtask",
                  "task begin_case; input integer new_mode; begin",
                  f"@(negedge {clock}); {reset}=1;{calib}=0;mode=new_mode;",
                  "for(j=0;j<256;j=j+1) memory[j]=0;",
                  f"repeat(3) @(negedge {clock}); end endtask",
                  "task run_case; input integer reason; integer limit; begin",
                  f"{reset}=0; wait({cache_ready()}); repeat(7) @(negedge {clock}); {calib}=1; limit=0;",
                  f"while(!{p('done')} && !{p('fault')} && limit<600) begin @(negedge {clock});limit=limit+1;end",
                  f"if(limit==600) $fatal(1,\"CPU timeout\");",
                  f"if(reason==0) begin if(!{p('done')} || {p('fault')}) $fatal(1,\"expected HALT\"); end",
                  f"else if(!{p('fault')} || {p('done')} || {p('fault_reason')}!==reason[2:0]) $fatal(1,\"wrong fault reason\");",
                  "if(writes!=expected_writes || requests!=expected_requests) $fatal(1,\"request/write count\");",
                  f"repeat(10) begin @(negedge {clock}); if({arvalid} || {reg}{p('general','w_en')}) $fatal(1,\"activity after stop\");end",
                  "$display(\"PASS CPU scenario mode=%0d writes=%0d requests=%0d reason=%0d\",mode,writes,requests,reason);",
                  "end endtask", "initial begin"]
        for port in module["ports"]:
            if port["direction"] == "in" and port["path"] != ["clock"]:
                lines.append(f"{port['name']}=0;")
        lines += ["begin_case(0); expected_writes=1;expected_requests=1;expected_dest[0]=1;expected_value[0]=0;",
                  "instruction(0,32'h20010203);run_case(0);",
                  "begin_case(0);expected_writes=4;expected_requests=1;",
                  "expected_dest[0]=2;expected_value[0]=7;expected_dest[1]=3;expected_value[1]=9;expected_dest[2]=1;expected_value[2]=16;expected_dest[3]=4;expected_value[3]=23;",
                  "instruction(0,32'h21020007);instruction(4,32'h21030009);instruction(8,32'h20010203);instruction(12,32'h20040102);run_case(0);",
                  "begin_case(0);expected_writes=11;expected_requests=2;",
                  "for(j=0;j<10;j=j+1) begin instruction(j*4,32'h210a0a01);expected_dest[j]=10;expected_value[j]=j+1;end",
                  "instruction(40,32'h200b0a0a);expected_dest[10]=11;expected_value[10]=20;run_case(0);",
                  "begin_case(0);expected_writes=3;expected_requests=1;expected_dest[0]=2;expected_value[0]=1;expected_dest[1]=2;expected_value[1]=64'h8000000000000000;expected_dest[2]=1;expected_value[2]=0;",
                  "instruction(0,32'h21020001);instruction(4,32'h3b02023f);instruction(8,32'h20010202);run_case(0);",
                  f"if({reg}{p('alu_direct','rA_in')}!==4'b0001) $fatal(1,\"ADD overflow rA event not recorded\");",
                  "begin_case(1);expected_writes=0;expected_requests=1;instruction(0,32'h01000000);run_case(2);",
                  "begin_case(2);expected_writes=0;expected_requests=1;instruction(0,32'h20010203);run_case(1);",
                  "begin_case(3);expected_writes=0;expected_requests=1;instruction(0,32'h20010203);run_case(1);",
                  '$display("PASS CPU: seven scenarios including ADD overflow rA event");$finish;end',
                  'initial begin #500000; $fatal(1,"global timeout"); end', "endmodule"]
        self.simulate("cpu", "\n".join(lines))

    def decoder(self):
        module = self.find("cpu/decode/decode.pseudo")
        lines = ["`timescale 1ns/1ps", "module tb;"] + self.declaration(module) + ["initial begin"]
        table = {}
        for opcode in range(0x18, 0x20): table[opcode] = (1 if opcode < 0x1c else 2, (opcode >> 1) & 1)
        for opcode in range(0x20, 0x30): table[opcode] = (0, (opcode - 0x20) // 2)
        for opcode in range(0x30, 0x34): table[opcode] = (3, (opcode >> 1) & 1)
        for opcode in range(0x34, 0x38): table[opcode] = (0, 8 + (opcode - 0x34) // 2)
        for opcode in range(0x38, 0x40): table[opcode] = (5, (opcode - 0x38) // 2)
        for opcode in range(0x60, 0x80): table[opcode] = (6, (opcode - 0x60) // 2)
        for opcode in range(0xc0, 0xd0): table[opcode] = (4, (opcode - 0xc0) // 2)
        table[0xd8] = table[0xd9] = (4, 12)
        for opcode in range(0xe0,0xf0):
            group=(opcode-0xe0)//4
            table[opcode]=(0,1) if group<2 else (4,0 if group==2 else 5)
        vectors = [(opcode << 24 | 0xa55ac3, valid) for opcode in range(256) for valid in (0, 1)]
        vectors += [(word, valid) for word in (0, 1, 0x100, 0x10000) for valid in (0, 1)]
        vectors += [(word,valid) for index in range(33) for word in (
            0xfe030000|index,0xf6000002|(index<<16),0xf70000c0|(index<<16)) for valid in (0,1)]
        vectors += [(word,valid) for word in (0xfaff0000,0xfaff0001,0xfaff0100,0xfb0000ff,0xfb0100ff,0xfb0001ff) for valid in (0,1)]
        for index, (word, input_valid) in enumerate(vectors):
            opcode, x, y, z = word >> 24, (word >> 16) & 255, (word >> 8) & 255, word & 255
            constant=0xe0<=opcode<=0xef
            jump=opcode in (0xf0,0xf1)
            conditional=0x40<=opcode<=0x5f
            branch=jump or conditional
            address_get=opcode in (0xf4,0xf5)
            special_get=opcode==0xfe
            special_put=opcode in (0xf6,0xf7)
            special=(special_get or special_put) and y==0 and (z<32 if special_get else x<32)
            supported, halt = opcode in table, word == 0
            store = (opcode&~1) in (0xa2,0xa6,0xaa,0xac,0xae,0xb4)
            memory = 0x80<=opcode<=0x8f or store
            store_constant=(opcode&~1)==0xb4
            push_go=opcode in (0xbe,0xbf)
            go=opcode in (0x9e,0x9f)
            push=push_go or opcode in (0xf2,0xf3)
            pop=opcode==0xf8
            save=opcode==0xfa and y==0 and z==0
            unsave=opcode==0xfb and x==0 and y==0
            stack=push or pop or save or unsave
            trap=opcode==0 and word!=0
            swym=opcode==0xfd
            load=0x80<=opcode<=0x8f
            unit, op = table.get(opcode, (3, 0))
            bits=24 if jump else 16
            displacement=(word&((1<<bits)-1))-((opcode&1)<<bits)
            expected = {"valid": int(input_valid and (supported or halt or memory or branch or address_get or go or special or stack or trap or swym)),
                        "unsupported": int(input_valid and not(supported or halt or memory or branch or address_get or go or special or stack or trap or swym)),
                        "trap": int(input_valid and trap), "swym": int(input_valid and swym),
                        "writes_x": int(input_valid and (supported or constant or load or address_get or go or (special and special_get) or push)),
                        "push": int(input_valid and push), "push_go": int(input_valid and push_go), "go": int(input_valid and go), "pop": int(input_valid and pop),
                        "save": int(input_valid and save), "unsave": int(input_valid and unsave),
                        "put_g": int(input_valid and special and special_put and x==19),
                        "put_l": int(input_valid and special and special_put and x==20),
                        "memory": int(input_valid and memory), "store": int(input_valid and store),
                        "memory_size": 3 if store_constant else (opcode>>2)&3,
                        "load_unsigned": (opcode>>1)&1,
                        "store_constant": int(input_valid and store_constant),
                        "address_get": int(input_valid and address_get),
                        "special_get": int(input_valid and special and special_get),
                        "special_put": int(input_valid and special and special_put),
                        "special_immediate": int(input_valid and special and opcode==0xf7),
                        "special_index": (x if special_put else z)&31,
                        "halt": int(input_valid and halt), "alu_sel": unit, "op": op,
                        "r1_sel": x if constant or conditional else y, "r2_sel": z, "w_sel": x,
                        "branch": int(input_valid and branch),
                        "branch_unconditional": int(input_valid and jump),
                        "branch_condition": (opcode>>1)&7,
                        "branch_offset": (4*displacement)&MASK,
                        "arg1_imm": int(input_valid and supported and 0x34 <= opcode <= 0x37),
                        "arg2_imm": int(input_valid and not constant and (supported or memory or push_go or go) and opcode & 1),
                        "arg1_zero": int(input_valid and 0xe0<=opcode<=0xe3),
                        "arg2_wyde": int(input_valid and constant),
                        "imm_x": x,"imm_y": y, "imm_z": z, "imm_yz": ((y<<8)|z) << (16*(3-(opcode&3)))}
            lines += [f"{enc('port','instruction')}=32'h{word:08x};{enc('port','instruction_valid')}=1'b{input_valid};#1;"]
            for key, value in expected.items():
                width = next(p["width"] for p in module["ports"] if p["path"] == ["control", key])
                lines.append(f"if({enc('port','control',key)} !== {width}'d{value}) $fatal(1,\"decoder case {index} {key}\");")
        lines += [f'$display("PASS decoder: {len(vectors)} cases, all 256 opcodes");$finish;end endmodule']
        self.simulate("decoder", "\n".join(lines))

    def cache(self):
        module = self.find("cpu/cache/cache.pseudo")
        p = lambda key: enc("port", key)
        lines = ["`timescale 1ns/1ps", "module tb;"] + self.declaration(module)
        lines += [f"initial {p('clock')}=0;always #5 {p('clock')}=~{p('clock')};", "initial begin"]
        for port in module["ports"]:
            if port["direction"] == "in" and port["path"] != ["clock"]:
                lines.append(f"{port['name']}=0;")
        def start():
            lines.append(f"{p('reset')}=1;repeat(2) @(negedge {p('clock')});{p('reset')}=0;")
            lines.append(f"@(negedge {p('clock')});if({p('ready')}!==1'b0) $fatal(1,\"cache ready during clear\");")
            lines.append(f"wait({p('ready')}===1'b1);@(negedge {p('clock')});")
        def read(address):
            # Synchronous read: the result appears after the next rising edge and is held while read=0.
            lines.append(f"{p('read_address')}=30'd{address};{p('compare_address')}=30'd{address};{p('read')}=1;"
                         f"@(negedge {p('clock')});{p('read')}=0;#1;")
        def lookup(address, hit, data=None):
            read(address)
            lines.append(f"if({p('hit')}!==1'b{hit}) $fatal(1,\"cache hit addr {address}\");")
            if data is not None:
                lines.append(f"if({p('line')}!==256'h{data:064x}) $fatal(1,\"cache data addr {address}\");")
        def write(port, address, data=0, mask=0, extra=""):
            lines.append(f"@(negedge {p('clock')});{p('write_address')}=30'd{address};{p('invalidate_address')}=30'd{address};"
                         f"{p('write_data')}=256'h{data:064x};{p('store_mask')}=32'h{mask:08x};{p(port)}=1;{extra}"
                         f"@(negedge {p('clock')});{p(port)}=0;")
        start()
        values = [int.from_bytes(bytes([i+1])*32, "little") for i in range(8)]
        base, alias_ = 0x1000, 0x1000 + 16384  # same index, different tag
        for i in range(8):
            lookup(base+i*32, 0)
            write("fill_en", base+i*32, values[i])
        for i in range(8): lookup(base+i*32, 1, values[i])
        # Byte-mask store hit: update bytes 16..23 only. store_en uses the held lookup result.
        pattern = int.from_bytes(bytes([0xab])*32, "little")
        lookup(base+3*32, 1)
        write("store_en", base+3*32, pattern, 0x00ff0000)
        bytes_ = bytearray([4]*32)
        bytes_[16:24] = bytes([0xab])*8
        values[3] = int.from_bytes(bytes_, "little")
        for i in range(8): lookup(base+i*32, 1, values[i])
        # Store miss must not allocate or overwrite the conflicting resident line.
        lookup(alias_, 0)
        write("store_en", alias_, pattern, 0xffffffff)
        lookup(alias_, 0)
        lookup(base, 1, values[0])
        # Fill at the same index replaces one line, preserving the others.
        write("fill_en", alias_, pattern)
        lookup(alias_, 1, pattern)
        lookup(base, 0)
        for i in range(1,8): lookup(base+i*32, 1, values[i])
        # Invalidate clears only the addressed line.
        write("invalidate", base+1*32)
        lookup(base+1*32, 0)
        for i in range(2,8): lookup(base+i*32, 1, values[i])
        # Invalidate wins over a fill in the same clock.
        write("fill_en", base+1*32, values[1], extra=f"{p('invalidate')}=1;")
        lines.append(f"{p('invalidate')}=0;")
        lookup(base+1*32, 0)
        # A read in the same clock as a write reports a miss.
        lines.append(f"{p('read_address')}=30'd{base+2*32};{p('compare_address')}=30'd{base+2*32};{p('read')}=1;"
                     f"{p('write_address')}=30'd{base+5*32};{p('write_data')}=256'h{values[5]:064x};{p('fill_en')}=1;"
                     f"@(negedge {p('clock')});{p('read')}=0;{p('fill_en')}=0;#1;"
                     f"if({p('hit')}!==1'b0) $fatal(1,\"cache stale read\");")
        lookup(base+2*32, 1, values[2])
        # Reset clears every line.
        start()
        for i in range(2,8): lookup(base+i*32, 0)
        lookup(alias_, 0)
        lines += ['$display("PASS cache: resident lines, masked store hit, no-write-allocate, conflict replacement, line invalidate, stale read, reset clear");$finish;end endmodule']
        self.simulate("cache", "\n".join(lines))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rtl", type=Path, default=ROOT / "src/cpu")
    parser.add_argument("--out", type=Path, default=ROOT / "build/verification")
    parser.add_argument("--iverilog", type=Path, default=ROOT / ".tools/iverilog/app/bin/iverilog.exe")
    parser.add_argument("--only", choices=("lint", "alu", "cpu", "decoder", "cache", "parity"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    checks = Checks(args.rtl, args.out, args.iverilog)
    if args.only:
        getattr(checks, args.only)()
    else:
        checks.parity()
        checks.lint()
        checks.alu()
        checks.decoder()
        checks.cache()
        checks.cpu()
    result_file = args.out / "results.json"
    previous = json.loads(result_file.read_text(encoding="utf-8")) if args.only and result_file.exists() else {}
    result_file.write_text(json.dumps(dict(previous, **checks.results), indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
