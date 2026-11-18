"""Capture actual post-NBA RTL state, then embed it in an offline HTML viewer."""
from pathlib import Path
import json

from verify_generated import enc


SPECIAL_NAMES = ["rB", "rD", "rE", "rH", "rJ", "rM", "rR", "rBB", "rC", "rN", "rO", "rS",
                 "rI", "rT", "rTT", "rK", "rQ", "rU", "rV", "rG", "rL", "rA", "rF", "rP",
                 "rW", "rX", "rY", "rZ", "rWW", "rXX", "rYY", "rZZ"]
STAGES = ["id", "rf", "ex", "wb"]


def instrumentation(path):
    p = lambda *parts: enc("port", *parts)
    instance = lambda *parts: "dut." + enc("instance", *parts)
    regs = instance("reg") + "."
    ctl = instance("control") + "."
    fetch = instance("fetch") + "."
    signal = lambda side, name: p("axi", side, name)
    edges = {"AR": ("read", "arvalid", "arready"), "R": ("read", "rvalid", "rready"),
             "AW": ("write", "awvalid", "awready"), "W": ("write", "wvalid", "wready"),
             "B": ("write", "bvalid", "bready")}
    lines = ["integer trace_file, trace_cycle=0, trace_index;",
             "reg edge_AR,edge_R,edge_AW,edge_W,edge_B,edge_write;reg [7:0] edge_dest;reg [63:0] edge_value;",
             "reg edge_exec,edge_wb;reg [63:0] edge_exec_pc,edge_wb_pc;reg [31:0] edge_exec_word;",
             f'initial trace_file=$fopen("{path.as_posix()}","w");',
             f"always @(posedge {p('clock')}) begin"]
    for name, (side, valid, ready) in edges.items():
        lines.append(f"edge_{name}=({signal(side,valid)}===1'b1 && {signal(side,ready)}===1'b1);")
    lines += [f"edge_write=({regs}{p('general','w_en')}===1'b1 && {p('reset')}!==1'b1);",
              f"edge_dest={regs}{p('general','w_sel')};edge_value={regs}{p('general','w_data')};",
              # Before the edge: the EX instruction that ran, and the instruction in WB (writes, store B).
              f"edge_exec=({ctl}{p('executed')}===1'b1);edge_exec_pc={ctl}{enc('instance','ex_pc')}.{p('out')};",
              f"edge_exec_word={ctl}{enc('instance','ex_instruction_q')}.{p('out')};",
              f"edge_wb=({ctl}{enc('instance','wb_valid')}.{p('out')}===1'b1);edge_wb_pc={ctl}{enc('instance','wb_pc')}.{p('out')};",
              "#1; // Wait for sequential nonblocking updates and combinational settling."]
    def write(format_, *values):
        escaped = format_.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        tail = "," + ",".join(values) if values else ""
        lines.append(f'$fwrite(trace_file,"{escaped}"{tail});')
    write('{"cycle":%0d,"time_ns":%0.1f,', "trace_cycle", "$realtime")
    out = lambda name: f"{ctl}{enc('instance', name)}.{p('out')}"
    # pc/instruction: the EX stage. stop_pc: the instruction that stopped (HALT or fault).
    write('"pc":"%016h","instruction":"%08h","instruction_valid":"%b","stop_pc":"%016h",',
          out("ex_pc"), out("ex_instruction_q"), out("ex_full"), out("pc"))
    write('"stages":{"id":"%b","id_pc":"%016h","rf":"%b","rf_pc":"%016h","ex":"%b","ex_pc":"%016h","wb":"%b","wb_pc":"%016h"},',
          f"{fetch}{p('control','res_valid')}", f"{fetch}{p('control','res_pc')}", out("rf_valid"), out("rf_pc"),
          out("ex_full"), out("ex_pc"), out("wb_valid"), out("wb_pc"))
    write('"reset":"%b","calibration":"%b","done":"%b","fault":"%b",',
          p("reset"), p("init_calib_complete"), p("done"), p("fault"))
    write('"edge":{"AR":%0d,"R":%0d,"AW":%0d,"W":%0d,"B":%0d,"write":%0d,"dest":"%h","value":"%016h",',
          "edge_AR", "edge_R", "edge_AW", "edge_W", "edge_B", "edge_write", "edge_dest", "edge_value")
    write('"exec":%0d,"exec_pc":"%016h","exec_word":"%08h","wb":%0d,"wb_pc":"%016h"},',
          "edge_exec", "edge_exec_pc", "edge_exec_word", "edge_wb", "edge_wb_pc")
    write('"axi":{')
    pins = [(side, name) for side, names in [("read", ["arvalid", "arready", "araddr", "rvalid", "rready", "rresp"]),
                                           ("write", ["awvalid", "awready", "awaddr", "wvalid", "wready", "wstrb", "bvalid", "bready", "bresp"])] for name in names]
    for index, (side, name) in enumerate(pins):
        write((',' if index else '') + f'"{name}":"%h"', signal(side, name))
    write('},"registers":[')
    for index in range(512):  # slots: ring l[0..255], then global g[k] at 256+k
        value = f"{regs}{enc('instance','ram_r1')}.mem[{index}]"
        write((',' if index else '') + '"%016h"', value)
    write('],"special":[')
    for index, name in enumerate(SPECIAL_NAMES):
        if name in ("rA", "rD", "rH", "rM", "rR", "rJ", "rO", "rS", "rG", "rL", "rT"):
            value = f"{regs}{enc('instance','direct_'+name)}.{p('direct_out')}"
            if name == "rA":
                # Physical compact storage -> architectural rA bits 6,7,14,15.
                value = "{48'b0," + value + "[3:2],6'b0," + value + "[1:0],6'b0}"
            elif name in ("rG", "rL"):
                value = "{56'b0," + value + "}"
        else:
            value = f"{regs}{enc('instance','special_ram')}.mem[{index}]"
        write((',' if index else '') + '"%016h"', value)
    write('],"memory":[')
    lines += ['for(trace_index=0;trace_index<256;trace_index=trace_index+1) begin',
              'if(trace_index!=0) $fwrite(trace_file,",");',
              '$fwrite(trace_file,"\\\"%02h\\\"",memory[trace_index]);', 'end']
    write(']}\n')
    lines += ["$fflush(trace_file);trace_cycle=trace_cycle+1;end"]
    return lines


def load_frames(trace):
    frames = [json.loads(line) for line in trace.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not frames or any(len(f["registers"]) != 512 or len(f["special"]) != 32 or len(f["memory"]) != 256 for f in frames):
        raise RuntimeError("Incomplete cycle dumps")
    return frames


def render(trace, output, program, focus_words):
    """Embed a trace in the viewer. program: [{address, text}]; focus_words: highlighted RAM word numbers."""
    frames = load_frames(trace)
    changes = []
    for index, frame in enumerate(frames):
        previous = frames[index - 1] if index else None
        frame["changed"] = {key: [i for i, value in enumerate(frame[key]) if previous and value != previous[key][i]]
                            for key in ("registers", "special", "memory")}
        if frame["edge"]["write"] or frame["changed"]["memory"] or frame["edge"]["exec"]:
            changes.append(index)
    payload = {"frames": frames, "events": changes, "stages": STAGES, "specialNames": SPECIAL_NAMES,
               "program": program, "focusWords": focus_words}
    template = Path(__file__).with_name("cycle_viewer.html").read_text(encoding="utf-8")
    embedded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    output.write_text(template.replace("__TRACE_DATA__", embedded), encoding="utf-8", newline="\n")
    print(f"Recorded {len(frames)} clock snapshots: {output}")
