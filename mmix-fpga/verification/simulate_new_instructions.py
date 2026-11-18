"""Trace verification/programs/new_instructions.mms clock by clock and judge the full instruction cycle.

Expected values below are hand-derived from the MMIX specification. They do not
come from the RTL, the crosscompiler or verification/cpu_suite/reference.py.
The viewer is written even when a check fails so the failing clock can be inspected.
"""
import json
import sys

from verify_generated import Checks, ROOT, cache_ready, enc

sys.path.insert(0, str(ROOT))
from verification.cpu_suite.assembler import assemble  # noqa: E402
from cycle_trace import STATES, SPECIAL_NAMES, instrumentation, load_frames, render  # noqa: E402

REQUEST, WAIT, EXECUTE, HALTED, MEMORY_REQUEST, MEMORY_WAIT, MEMORY_COMMIT, STACK, READ = 0, 1, 2, 3, 5, 6, 7, 8, 9
PATH_EXECUTE = [REQUEST, WAIT, READ, EXECUTE]
PATH_MEMORY = [REQUEST, WAIT, READ, EXECUTE, MEMORY_REQUEST, MEMORY_WAIT, MEMORY_COMMIT]
PATH_HALT = [REQUEST, WAIT, READ, EXECUTE, HALTED]

# (pc, opcode, source text). Immediate and backward forms have their own opcodes.
PROGRAM = [
    (0x00, 0xF4, "GETA $1,Data"), (0x04, 0xF4, "GETA $2,Out"),
    (0x08, 0x81, "LDB $3,$1,0"), (0x0C, 0x83, "LDBU $4,$1,0"),
    (0x10, 0x85, "LDW $5,$1,1"), (0x14, 0x87, "LDWU $6,$1,1"),
    (0x18, 0x89, "LDT $7,$1,5"), (0x1C, 0x8B, "LDTU $8,$1,5"),
    (0x20, 0xE3, "SETL $9,8"),
    (0x24, 0x80, "LDB $10,$1,$9"), (0x28, 0x82, "LDBU $11,$1,$9"),
    (0x2C, 0x84, "LDW $12,$1,$9"), (0x30, 0x86, "LDWU $13,$1,$9"),
    (0x34, 0x88, "LDT $14,$1,$9"), (0x38, 0x8A, "LDTU $15,$1,$9"),
    (0x3C, 0xE3, "SETL $16,20"), (0x40, 0xE3, "SETL $17,12"), (0x44, 0xE3, "SETL $21,24"),
    (0x48, 0xA3, "STBU $3,$2,1"), (0x4C, 0xA7, "STWU $5,$2,10"),
    (0x50, 0xA2, "STBU $11,$2,$9"), (0x54, 0xA6, "STWU $13,$2,$17"),
    (0x58, 0xB5, "STCO 200,$2,16"), (0x5C, 0xAA, "STTU $7,$2,$16"),
    (0x60, 0x8F, "LDOU $18,$2,0"), (0x64, 0x8F, "LDOU $19,$2,8"), (0x68, 0x8F, "LDOU $20,$2,16"),
    (0x6C, 0xB4, "STCO 127,$2,$21"), (0x70, 0xAB, "STTU $15,$2,24"), (0x74, 0x8E, "LDOU $22,$2,$21"),
    (0x78, 0xF6, "PUT rD,$6"), (0x7C, 0xFE, "GET $23,rD"),
    (0x80, 0xF7, "PUT rM,255"), (0x84, 0xFE, "GET $24,rM"),
    (0x88, 0xE3, "SETL $25,#40"), (0x8C, 0xF6, "PUT rA,$25"), (0x90, 0xFE, "GET $26,rA"),
    (0x94, 0xE3, "SETL $27,3"), (0x98, 0xE3, "SETL $28,0"),
    (0x9C, 0x23, "Loop: ADDU $28,$28,1"), (0xA0, 0x27, "SUBU $27,$27,1"), (0xA4, 0x4B, "BNZ $27,Loop"),
    (0xA8, 0x54, "PBP $28,Skip"), (0xAC, 0xE3, "SETL $29,#DEAD (must be skipped)"),
    (0xB0, 0x40, "Skip: BN $28,Wrong"), (0xB4, 0xF0, "JMP Fwd"),
    (0xB8, 0xE3, "Wrong: SETL $29,#DEAD (must be skipped)"), (0xBC, 0xE3, "Back: SETL $29,1"),
    (0xC0, 0xF0, "JMP End"), (0xC4, 0xF1, "Fwd: JMP Back"),
    (0xC8, 0xF5, "End: GETA $30,Loop"), (0xCC, 0x00, "TRAP 0,Halt,0"),
]
OPCODE = {pc: op for pc, op, _ in PROGRAM}

# Dynamic execution order. The loop body runs three times; #AC and #B8 never run.
PC_ORDER = ([pc for pc, _, _ in PROGRAM if pc <= 0x98] + [0x9C, 0xA0, 0xA4] * 3
            + [0xA8, 0xB0, 0xB4, 0xC4, 0xBC, 0xC0, 0xC8, 0xCC])

# General register writes in order: (pc of retiring instruction, $X, value).
WRITES = [
    (0x00, 1, 0xD0), (0x04, 2, 0xE0),
    (0x08, 3, 0xFFFFFFFFFFFFFF80), (0x0C, 4, 0x80),
    (0x10, 5, 0xFFFFFFFFFFFF80FF), (0x14, 6, 0x80FF),
    (0x18, 7, 0xFFFFFFFFA5B6C7D8), (0x1C, 8, 0xA5B6C7D8),
    (0x20, 9, 8),
    (0x24, 10, 0xFFFFFFFFFFFFFF81), (0x28, 11, 0x81),
    (0x2C, 12, 0xFFFFFFFFFFFF817E), (0x30, 13, 0x817E),
    (0x34, 14, 0xFFFFFFFF817E8001), (0x38, 15, 0x817E8001),
    (0x3C, 16, 20), (0x40, 17, 12), (0x44, 21, 24),
    (0x60, 18, 0xA180A3A4A5A6A7A8), (0x64, 19, 0x81B280FF817EB7B8), (0x68, 20, 0x00000000A5B6C7D8),
    (0x74, 22, 0x817E80010000007F),
    (0x7C, 23, 0x80FF), (0x84, 24, 0xFF), (0x88, 25, 0x40), (0x90, 26, 0x40),
    (0x94, 27, 3), (0x98, 28, 0),
    (0x9C, 28, 1), (0xA0, 27, 2), (0x9C, 28, 2), (0xA0, 27, 1), (0x9C, 28, 3), (0xA0, 27, 0),
    (0xBC, 29, 1), (0xC8, 30, 0x9C),
]

# Stores in order: (pc, line address, WSTRB, {byte address: value}). Every store targets line #E0.
STORES = [
    (0x48, 0xE0, 0x00000002, {0xE1: 0x80}),
    (0x4C, 0xE0, 0x00000C00, {0xEA: 0x80, 0xEB: 0xFF}),
    (0x50, 0xE0, 0x00000100, {0xE8: 0x81}),
    (0x54, 0xE0, 0x00003000, {0xEC: 0x81, 0xED: 0x7E}),
    (0x58, 0xE0, 0x00FF0000, dict(zip(range(0xF0, 0xF8), bytes.fromhex("00000000000000C8")))),
    (0x5C, 0xE0, 0x00F00000, dict(zip(range(0xF4, 0xF8), bytes.fromhex("A5B6C7D8")))),
    (0x6C, 0xE0, 0xFF000000, dict(zip(range(0xF8, 0x100), bytes.fromhex("000000000000007F")))),
    (0x70, 0xE0, 0x0F000000, dict(zip(range(0xF8, 0xFC), bytes.fromhex("817E8001")))),
]
# The program starts at L=0 with G=255. Its highest destination is $30, so L ends at 31.
FINAL_SPECIAL = {"rD": 0x80FF, "rM": 0xFF, "rA": 0x40, "rG": 255, "rL": 31}
FOCUS_WORDS = [26, 27, 28, 29, 30, 31]


def testbench(checks, module, words):
    p = lambda *path: enc("port", *path)
    clk, reset, calib = p("clock"), p("reset"), p("init_calib_complete")
    ar = lambda field: p("axi", "read", field)
    aw = lambda field: p("axi", "write", field)
    reg = "dut." + enc("instance", "reg") + "."
    lines = ["`timescale 1ns/1ps", "module tb;"] + checks.declaration(module)
    lines += [f"initial {clk}=0;always #5 {clk}=~{clk};",
              "reg [7:0] memory[0:255];reg [255:0] saved_wdata;reg [31:0] saved_wstrb;",
              "integer cycles=0,j=0,rpending=0,rdelay=0,raddress=0;",
              "integer aw_seen=0,w_seen=0,applied=0,bdelay=0,waddress=0,limit=0;",
              "integer ar_stall=0,aw_stall=0,w_stall=0;reg [29:0] previous_ar,previous_aw;reg [255:0] previous_w;reg [31:0] previous_strb;",
              f"always @(negedge {clk}) begin",
              f" if({reset}) begin {ar('arready')}=0;{ar('rvalid')}=0;{aw('awready')}=0;{aw('wready')}=0;{aw('bvalid')}=0;end",
              " else begin",
              f"  {ar('arready')}=(!rpending && !{ar('rvalid')} && cycles%4==1);",
              f"  {aw('awready')}=(!aw_seen && !applied && cycles%5==2);",
              f"  {aw('wready')}=(!w_seen && !applied && cycles%7==3);",
              f"  if(rpending && !{ar('rvalid')}) begin if(rdelay>0) rdelay=rdelay-1;else begin",
              f"   for(j=0;j<32;j=j+1) {ar('rdata')}[j*8+:8]=memory[raddress+j];",
              f"   {ar('rid')}=0;{ar('rresp')}=0;{ar('rlast')}=1;{ar('rvalid')}=1;end end",
              f"  if(applied==1 && !{aw('bvalid')}) begin if(bdelay>0) bdelay=bdelay-1;else begin {aw('bid')}=0;{aw('bresp')}=0;{aw('bvalid')}=1;end end",
              " end end",
              f"always @(posedge {clk}) begin",
              f" if({reset}) begin cycles=0;rpending=0;aw_seen=0;w_seen=0;applied=0;ar_stall=0;aw_stall=0;w_stall=0;end",
              " else begin cycles=cycles+1;",
              f"  if(!{calib} && ({ar('arvalid')} || {aw('awvalid')} || {aw('wvalid')})) $fatal(1,\"access before RAM ready\");",
              f"  if(ar_stall && (!{ar('arvalid')} || {ar('araddr')}!==previous_ar)) $fatal(1,\"unstable AR\");",
              f"  if(aw_stall && (!{aw('awvalid')} || {aw('awaddr')}!==previous_aw)) $fatal(1,\"unstable AW\");",
              f"  if(w_stall && (!{aw('wvalid')} || {aw('wdata')}!==previous_w || {aw('wstrb')}!==previous_strb)) $fatal(1,\"unstable W\");",
              f"  ar_stall={ar('arvalid')} && !{ar('arready')};previous_ar={ar('araddr')};",
              f"  aw_stall={aw('awvalid')} && !{aw('awready')};previous_aw={aw('awaddr')};",
              f"  w_stall={aw('wvalid')} && !{aw('wready')};previous_w={aw('wdata')};previous_strb={aw('wstrb')};",
              f"  if({ar('arvalid')} && {ar('arready')}) begin",
              f"   if({ar('arlen')}!==0 || {ar('arsize')}!==5 || {ar('arid')}!==0 || {ar('araddr')}[4:0]!==0 || {ar('araddr')}>=256) $fatal(1,\"read transaction shape\");",
              f"   rpending=1;rdelay=2;raddress={ar('araddr')};end",
              f"  if({ar('rvalid')} && {ar('rready')}) begin rpending=0;{ar('rvalid')}<=0;end",
              f"  if({aw('awvalid')} && {aw('awready')}) begin",
              f"   if({aw('awsize')}!==5 || {aw('awlen')}!==0 || {aw('awid')}!==0 || {aw('awaddr')}[4:0]!==0 || {aw('awaddr')}>=256) $fatal(1,\"write transaction shape\");",
              f"   aw_seen=1;waddress={aw('awaddr')};end",
              f"  if({aw('wvalid')} && {aw('wready')}) begin",
              f"   if(!{aw('wlast')}) $fatal(1,\"missing WLAST\");",
              f"   w_seen=1;saved_wdata={aw('wdata')};saved_wstrb={aw('wstrb')};end",
              "  if(aw_seen && w_seen && !applied) begin",
              "   for(j=0;j<32;j=j+1) if(saved_wstrb[j]) memory[waddress+j]=saved_wdata[j*8+:8];",
              "   applied=1;bdelay=11;end",
              f"  if({aw('bvalid')} && {aw('bready')}) begin applied=0;aw_seen=0;w_seen=0;{aw('bvalid')}<=0;end",
              " end end",
              "initial begin"]
    for port in module["ports"]:
        if port["direction"] == "in" and port["path"] != ["clock"]:
            lines.append(f"{port['name']}=0;")
    lines += [f"@(negedge {clk});{reset}=1;{calib}=0;", "for(j=0;j<256;j=j+1) memory[j]=8'ha5;"]
    lines += [f"memory[{address}]=8'h{byte:02x};" for address, byte in sorted(words.items())]
    lines += [f"repeat(3) @(negedge {clk});{reset}=0;wait({cache_ready()});repeat(5) @(negedge {clk});{calib}=1;limit=0;",
              f"while(!{p('done')} && !{p('fault')} && limit<5000) begin @(negedge {clk});limit=limit+1;end",
              f"if(limit==5000) $fatal(1,\"clock limit reached\");",
              f"if({p('fault')}) begin repeat(3) @(negedge {clk});$fatal(1,\"fault reason=%0d\",{p('fault_reason')});end",
              f"repeat(15) begin @(negedge {clk});if({ar('arvalid')} || {aw('awvalid')} || {aw('wvalid')} || {reg}{p('general','w_en')}) $fatal(1,\"activity after HALT\");end",
              '$display("PASS simulation reached HALT");$finish;end',
              'initial begin #2000000;$fatal(1,"global timeout");end', "endmodule"]
    return lines


def state_of(frame):
    return int(frame["state"], 16) if frame["state"] in "012345678" else None


def without_growth(path):
    """Marginal $X adds EXECUTE -> STACK -> EXECUTE before the instruction runs."""
    result = []
    for state in path:
        if state == STACK:
            result.pop()
        elif state == EXECUTE and result and result[-1] == EXECUTE:
            continue
        else:
            result.append(state)
    return result


def split_instructions(frames, start):
    """Group clocks into instructions. A new instruction begins on each entry into REQUEST."""
    items, previous = [], None
    for index in range(start, len(frames)):
        state = state_of(frames[index])
        if state == REQUEST and previous != REQUEST:
            items.append({"first": index, "path": [], "pc": int(frames[index]["pc"], 16)})
        if items and state != previous:
            items[-1]["path"].append(state)
        if items:
            items[-1]["last"] = index
        previous = state
    return items


def judge(frames, memory_image):
    errors = []
    start = max((i for i, f in enumerate(frames) if f["reset"] == "1"), default=-1) + 1
    if any(f["fault"] == "1" for f in frames[start:]):
        errors.append("fault asserted")
    if frames[-1]["done"] != "1":
        errors.append("final clock is not HALTED")

    items = split_instructions(frames, start)
    order = [item["pc"] for item in items]
    if order != PC_ORDER:
        mismatch = next((i for i, (a, b) in enumerate(zip(order, PC_ORDER)) if a != b), min(len(order), len(PC_ORDER)))
        errors.append(f"PC order differs at step {mismatch}: actual={[hex(x) for x in order[mismatch:mismatch+6]]} "
                      f"expected={[hex(x) for x in PC_ORDER[mismatch:mismatch+6]]}")
    for item in items:
        pc = item["pc"]
        opcode = OPCODE.get(pc)
        memory_kind = opcode is not None and (0x80 <= opcode <= 0x8F or 0xA0 <= opcode <= 0xB5)
        expected = PATH_HALT if pc == 0xCC else PATH_MEMORY if memory_kind else PATH_EXECUTE
        if without_growth(item["path"]) != expected:
            errors.append(f"pc={pc:#x} state path {[STATES[s] if s is not None else 'X' for s in item['path']]}")
        executing = [i for i in range(item["first"], item["last"] + 1) if state_of(frames[i]) == EXECUTE]
        if not executing:
            continue
        frame = frames[executing[0]]
        word = int.from_bytes(bytes(memory_image[pc + i] for i in range(4)), "big") if pc + 3 < 256 else None
        if frame["instruction_valid"] != "1" or word is None or int(frame["instruction"], 16) != word:
            errors.append(f"pc={pc:#x} instruction buffer={frame['instruction']} valid={frame['instruction_valid']} RAM={word}")
        if opcode is not None and word is not None and word >> 24 != opcode:
            errors.append(f"pc={pc:#x} opcode {word >> 24:#x}, expected {opcode:#x}")

    actual_writes = [(int(frames[i - 1]["pc"], 16), int(frames[i]["edge"]["dest"], 16), int(frames[i]["edge"]["value"], 16))
                     for i in range(start, len(frames)) if frames[i]["edge"]["write"]]
    if actual_writes != WRITES:
        mismatch = next((i for i, (a, b) in enumerate(zip(actual_writes, WRITES)) if a != b), min(len(actual_writes), len(WRITES)))
        errors.append(f"register write {mismatch} of {len(actual_writes)}/{len(WRITES)}: "
                      f"actual={actual_writes[mismatch:mismatch+2]} expected={WRITES[mismatch:mismatch+2]}")

    aw_edges = [i for i in range(start, len(frames)) if frames[i]["edge"]["AW"]]
    w_edges = [i for i in range(start, len(frames)) if frames[i]["edge"]["W"]]
    b_edges = [i for i in range(start, len(frames)) if frames[i]["edge"]["B"]]
    if not len(aw_edges) == len(w_edges) == len(b_edges) == len(STORES):
        errors.append(f"AW/W/B handshakes {len(aw_edges)}/{len(w_edges)}/{len(b_edges)}, expected {len(STORES)} each")
    snapshot = list(memory_image)
    changed = [i for i in range(start + 1, len(frames)) if frames[i]["memory"] != frames[i - 1]["memory"]]
    if len(changed) != len(STORES):
        errors.append(f"memory changed on {len(changed)} clocks, expected {len(STORES)}")
    for number, (aw, w, b, clock, store) in enumerate(zip(aw_edges, w_edges, b_edges, changed, STORES)):
        pc, line, strobe, data = store
        if int(frames[aw - 1]["pc"], 16) != pc or int(frames[w - 1]["pc"], 16) != pc:
            errors.append(f"store {number}: handshake during pc={frames[aw - 1]['pc']}, expected {pc:#x}")
        if int(frames[aw - 1]["axi"]["awaddr"], 16) != line:
            errors.append(f"store {number}: AWADDR={frames[aw - 1]['axi']['awaddr']}, expected {line:#x}")
        if int(frames[w - 1]["axi"]["wstrb"], 16) != strobe:
            errors.append(f"store {number}: WSTRB={frames[w - 1]['axi']['wstrb']}, expected {strobe:#010x}")
        for address, byte in data.items():
            snapshot[address] = byte
        if [int(x, 16) for x in frames[clock]["memory"]] != snapshot:
            errors.append(f"store {number}: RAM after clock {clock} differs from expected bytes")
        owner = next((item for item in items if item["pc"] == pc), None)
        commit = None
        if owner:
            commit = next((i for i in range(owner["first"], owner["last"] + 1) if state_of(frames[i]) == MEMORY_COMMIT), None)
        if commit is None or commit < b:
            errors.append(f"store {number}: instruction finished before its B response (commit={commit}, B={b})")

    last = frames[-1]
    if int(last["pc"], 16) != 0xCC:
        errors.append(f"final PC={last['pc']}")
    registers = [0] * 256
    for _, dest, value in WRITES:
        registers[dest] = value
    for index, value in enumerate(last["registers"]):
        if not all(c in "0123456789abcdef" for c in value) or int(value, 16) != registers[index]:
            errors.append(f"final ${index}={value}, expected {registers[index]:016x}")
    for index, value in enumerate(last["special"]):
        expected = FINAL_SPECIAL.get(SPECIAL_NAMES[index], 0)
        if not all(c in "0123456789abcdef" for c in value) or int(value, 16) != expected:
            errors.append(f"final {SPECIAL_NAMES[index]}={value}, expected {expected:016x}")
    if [int(x, 16) for x in last["memory"]] != snapshot:
        errors.append("final RAM differs from expected bytes")

    halted = next((i for i in range(start, len(frames)) if frames[i]["done"] == "1"), None)
    if halted is not None:
        for frame in frames[halted + 1:]:
            if frame["pc"] != frames[halted]["pc"] or frame["memory"] != frames[halted]["memory"] or frame["edge"]["write"]:
                errors.append("state changed after HALT")
                break

    report = {"start_clock": start, "clocks": len(frames) - start, "instructions_executed": len(items),
              "register_writes": len(actual_writes), "stores": len(b_edges),
              "axi_reads": sum(1 for f in frames[start:] if f["edge"]["AR"]),
              "opcodes_executed": sorted({f"{OPCODE[i['pc']]:#04x}" for i in items if i["pc"] in OPCODE}),
              "instructions": [{"pc": f"{i['pc']:#04x}", "clocks": i["last"] - i["first"] + 1,
                                "path": [STATES[s] if s is not None else "X" for s in i["path"]]} for i in items]}
    return errors, report


def main():
    output = ROOT / "build/new-instructions"
    output.mkdir(parents=True, exist_ok=True)
    image, _ = assemble(ROOT / ".tools/mmix/mmixal.exe", ROOT / "verification/programs/new_instructions.mms", output)
    if image.entry != 0 or any(image.registers.values()) or max(image.memory) > 255:
        raise RuntimeError("Program must start at 0, preset no registers and fit in 256 bytes")
    for pc, opcode, text in PROGRAM:
        if image.memory[pc] != opcode:
            raise RuntimeError(f"{text}: assembled opcode {image.memory[pc]:#x}, expected {opcode:#x}")
    memory_image = [image.memory.get(address, 0xA5) for address in range(256)]

    checks = Checks(ROOT / "src/cpu", output, ROOT / ".tools/iverilog/app/bin/iverilog.exe")
    module = next(m for m in checks.modules if m["module"] == checks.mapping["top"])
    lines = testbench(checks, module, image.memory)
    lines[-1:-1] = instrumentation(output / "cycles.jsonl")

    errors = []
    try:
        checks.simulate("new_instructions", "\n".join(lines))
    except RuntimeError as exc:
        errors.append(f"simulation: {exc}")

    trace = output / "cycles.jsonl"
    frames = load_frames(trace)
    program = [{"address": pc, "text": text} for pc, _, text in PROGRAM]
    render(trace, output / "cycles.html", program, FOCUS_WORDS)

    found, report = judge(frames, memory_image)
    errors += found
    report["errors"] = errors
    (output / "cycle_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"clocks={report['clocks']} instructions={report['instructions_executed']} "
          f"register_writes={report['register_writes']} stores={report['stores']} axi_reads={report['axi_reads']} "
          f"opcodes={len(report['opcodes_executed'])}")
    for error in errors[:30]:
        print("FAIL", error)
    print(f"{'FAIL' if errors else 'PASS'} full instruction cycle: {len(errors)} errors")
    raise SystemExit(1 if errors else 0)


if __name__ == "__main__":
    main()
