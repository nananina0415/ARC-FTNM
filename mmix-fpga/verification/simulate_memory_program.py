"""Simulate LDO/ADD/STO/HALT against a byte-addressed, stalling AXI RAM."""
from pathlib import Path
import argparse
import json
import sys

from verify_generated import Checks, ROOT, enc


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", action="store_true", help="Record every clock of the first dataset and generate standalone HTML")
    args = parser.parse_args()
    output = ROOT / "build/memory-program"
    output.mkdir(parents=True, exist_ok=True)
    checks = Checks(ROOT / "src/cpu", output, ROOT / ".tools/iverilog/app/bin/iverilog.exe")
    checks.lint()
    module = next(m for m in checks.modules if m["module"] == checks.mapping["top"])
    p = lambda *path: enc("port", *path)
    clk, reset, calib = p("clock"), p("reset"), p("init_calib_complete")
    ar = lambda field: p("axi", "read", field)
    aw = lambda field: p("axi", "write", field)
    reg = "dut." + enc("instance", "reg") + "."
    program = [0x8d010050, 0x8d020058, 0x20030102, 0xad030060, 0]
    cases = [(123, 456), (0x0102030405060708, 0x0011223344556677), (0, 0),
             (0xffffffffffffffff, 1), (987654321, 123456789)]
    if args.trace:
        cases = cases[:1]
    lines = ["`timescale 1ns/1ps", "module tb;"] + checks.declaration(module)
    for word in (10, 11, 12):
        values = ",".join(f"memory[{word * 8 + byte}]" for byte in range(8))
        lines.append(f"wire [63:0] memory_word_{word} = {{{values}}};")
    control = "dut." + enc("instance", "control") + "."
    lines += [f"wire [63:0] program_counter = {control}{enc('instance','pc')}.{p('out')};",
              f'initial begin $dumpfile("{(output / "memory_program.vcd").as_posix()}");',
              f"$dumpvars(0,program_counter,memory_word_10,memory_word_11,memory_word_12,{clk},{reset},{calib},{p('done')},{p('fault')},{ar('arvalid')},{ar('arready')},{ar('araddr')},{ar('rvalid')},{ar('rready')},{aw('awvalid')},{aw('awready')},{aw('awaddr')},{aw('wvalid')},{aw('wready')},{aw('wdata')},{aw('wstrb')},{aw('bvalid')},{aw('bready')});end"]
    lines += [f"initial {clk}=0;always #5 {clk}=~{clk};",
              "reg [7:0] memory[0:255];reg [7:0] before_memory[0:255];",
              "reg [63:0] first,second,total;reg [255:0] saved_wdata;reg [31:0] saved_wstrb;",
              "integer cycles=0,j=0,k=0,rpending=0,rdelay=0,raddress=0;",
              "integer aw_seen=0,w_seen=0,applied=0,bdelay=0,waddress=0,reads=0,loads=0,stores=0,acks=0,writes=0;",
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
              f" if({reset}) begin cycles=0;rpending=0;aw_seen=0;w_seen=0;applied=0;reads=0;loads=0;stores=0;acks=0;writes=0;ar_stall=0;aw_stall=0;w_stall=0;end",
              " else begin cycles=cycles+1;",
              f"  if(!{calib} && ({ar('arvalid')} || {aw('awvalid')} || {aw('wvalid')})) $fatal(1,\"access before RAM ready\");",
              f"  if(ar_stall && (!{ar('arvalid')} || {ar('araddr')}!==previous_ar)) $fatal(1,\"unstable AR\");",
              f"  if(aw_stall && (!{aw('awvalid')} || {aw('awaddr')}!==previous_aw)) $fatal(1,\"unstable AW\");",
              f"  if(w_stall && (!{aw('wvalid')} || {aw('wdata')}!==previous_w || {aw('wstrb')}!==previous_strb)) $fatal(1,\"unstable W\");",
              f"  ar_stall={ar('arvalid')} && !{ar('arready')};previous_ar={ar('araddr')};",
              f"  aw_stall={aw('awvalid')} && !{aw('awready')};previous_aw={aw('awaddr')};",
              f"  w_stall={aw('wvalid')} && !{aw('wready')};previous_w={aw('wdata')};previous_strb={aw('wstrb')};",
              f"  if({ar('arvalid')} && {ar('arready')}) begin",
              f"   if({ar('arlen')}!==0 || {ar('arsize')}!==5 || {ar('arid')}!==0) $fatal(1,\"read transaction shape\");",
              f"   if({ar('arprot')}[2]) begin if({ar('araddr')}!==0) $fatal(1,\"instruction address\");end",
              f"   else begin if({ar('araddr')}!==64) $fatal(1,\"data read address\");loads=loads+1;end",
              f"   rpending=1;rdelay=2;raddress={ar('araddr')};reads=reads+1;end",
              f"  if({ar('rvalid')} && {ar('rready')}) begin rpending=0;{ar('rvalid')}<=0;end",
              f"  if({aw('awvalid')} && {aw('awready')}) begin",
              f"   if({aw('awaddr')}!==96 || {aw('awsize')}!==5 || {aw('awlen')}!==0 || {aw('awid')}!==0) $fatal(1,\"store address/shape\");",
              f"   aw_seen=1;waddress={aw('awaddr')};end",
              f"  if({aw('wvalid')} && {aw('wready')}) begin",
              f"   if({aw('wstrb')}!==32'h000000ff || !{aw('wlast')}) $fatal(1,\"store byte strobes\");",
              f"   w_seen=1;saved_wdata={aw('wdata')};saved_wstrb={aw('wstrb')};end",
              "  if(aw_seen && w_seen && !applied) begin",
              "   for(j=0;j<32;j=j+1) if(saved_wstrb[j]) memory[waddress+j]=saved_wdata[j*8+:8];",
              "   applied=1;bdelay=11;stores=stores+1;end",
              f"  if({aw('bvalid')} && {aw('bready')}) begin acks=acks+1;applied=2;{aw('bvalid')}<=0;end",
              f"  if({p('done')} && acks!=1) $fatal(1,\"HALT before write response\");",
              f"  if({reg}{p('general','w_en')}) begin",
              "   case(writes)",
              f"   0: if({reg}{p('general','w_sel')}!==8'd1 || {reg}{p('general','w_data')}!==first) $fatal(1,\"first load\");",
              f"   1: if({reg}{p('general','w_sel')}!==8'd2 || {reg}{p('general','w_data')}!==second) $fatal(1,\"second load\");",
              f"   2: if({reg}{p('general','w_sel')}!==8'd3 || {reg}{p('general','w_data')}!==total) $fatal(1,\"ADD result\");",
              '   default: $fatal(1,"unexpected register write");endcase writes=writes+1;end',
              " end end",
              "task put_instruction;input integer address;input [31:0] word;begin",
              "memory[address]=word[31:24];memory[address+1]=word[23:16];memory[address+2]=word[15:8];memory[address+3]=word[7:0];end endtask",
              "task run;input [63:0] a,b;integer limit;begin",
              f" @(negedge {clk});{reset}=1;{calib}=0;first=a;second=b;total=a+b;",
              " for(j=0;j<256;j=j+1) memory[j]=8'ha5;",
              *[f" put_instruction({i*4},32'h{word:08x});" for i, word in enumerate(program)],
              " for(j=0;j<8;j=j+1) begin memory[80+j]=a[(7-j)*8+:8];memory[88+j]=b[(7-j)*8+:8];end",
              " for(j=0;j<256;j=j+1) before_memory[j]=memory[j];",
              f" repeat(3) @(negedge {clk});{reset}=0;repeat(5) @(negedge {clk});{calib}=1;limit=0;",
              f" while(!{p('done')} && !{p('fault')} && limit<1000) begin @(negedge {clk});limit=limit+1;end",
              f" if(limit==1000 || {p('fault')} || !{p('done')}) $fatal(1,\"program failed, reason=%0d\",{p('fault_reason')});",
              " if(reads!=3 || loads!=1 || stores!=1 || acks!=1 || writes!=3) $fatal(1,\"transaction counts with I/D caches\");",
              " for(j=0;j<256;j=j+1) begin",
              "  if(j>=96 && j<104) begin if(memory[j]!==total[(103-j)*8+:8]) $fatal(1,\"word 12 data/byte order\");end",
              "  else if(memory[j]!==before_memory[j]) $fatal(1,\"neighbor/program/input memory corrupted at %0d\",j);end",
              f" repeat(15) begin @(negedge {clk});if({ar('arvalid')} || {aw('awvalid')} || {aw('wvalid')} || {reg}{p('general','w_en')}) $fatal(1,\"activity after HALT\");end",
              '$display("PASS RAM[10]=%0d RAM[11]=%0d -> RAM[12]=%0d; AXI data reads=1 stores=1 HALT",a,b,total);',
              "end endtask", "initial begin"]
    for port in module["ports"]:
        if port["direction"] == "in" and port["path"] != ["clock"]:
            lines.append(f"{port['name']}=0;")
    lines += [f"run(64'h{a:016x},64'h{b:016x});" for a, b in cases]
    lines += [f'$display("PASS memory program: {len(cases)} datasets");$finish;end',
              'initial begin #1000000;$fatal(1,"global timeout");end', "endmodule"]
    if args.trace:
        from cycle_trace import instrumentation
        lines[-1:-1] = instrumentation(output / "cycles.jsonl")
    checks.simulate("memory_program", "\n".join(lines))
    (output / "results.json").write_text(json.dumps(checks.results, indent=2), encoding="utf-8")
    if args.trace:
        from cycle_trace import render
        render(output / "cycles.jsonl", output / "cycles.html")


if __name__ == "__main__":
    main()
