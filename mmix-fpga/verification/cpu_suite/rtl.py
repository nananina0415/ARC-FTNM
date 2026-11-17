"""RTL-specific observation/fixture adapter; never implements instruction semantics."""
import json

from .catalog import SPECIAL, RAM_BYTES

# Flip-flop special registers; the others live in the special block RAM.
DIRECT = ('rA','rD','rH','rM','rR','rJ','rO','rS','rG','rL','rT')
# General registers are three identical block RAM copies (read ports r1/r2/x).
GENERAL_RAMS = ('ram_r1','ram_r2','ram_x')


def enc(*parts):
    return 's_' + '_'.join(f'{len(str(p))}_{p}' for p in parts)


def bench(rtl):
    mapping = json.loads((rtl / 'source-map.json').read_text(encoding='utf-8'))
    top = next(m for m in mapping['modules'] if m['module'] == mapping['top'])
    p = lambda *parts: enc('port', *parts)
    inst = lambda *parts: enc('instance', *parts)
    reg = 'dut.' + inst('reg') + '.'
    ctl = 'dut.' + inst('control') + '.'
    lines = ['`timescale 1ns/1ps', 'module tb;']
    for port in top['ports']:
        lines.append(f"{'reg' if port['direction']=='in' else 'wire'} [{port['width']-1}:0] {port['name']};")
    lines.append(top['module'] + ' dut (' + ','.join(f".{v['name']}({v['name']})" for v in top['ports']) + ');')
    lines += ['wire [63:0] g[0:255];wire [63:0] s[0:31];', 'task fixture;begin']
    for i in range(256):
        for ram in GENERAL_RAMS:
            lines.append(f'{reg}{inst(ram)}.mem[{i}]=initial_g[{i}];')
    for i, name in enumerate(SPECIAL):
        if name not in DIRECT:
            lines.append(f'{reg}{inst("special_ram")}.mem[{i}]=initial_s[{i}];')
            continue
        path = reg + inst('direct_'+name)
        width = 4 if name == 'rA' else 8 if name in ('rG','rL') else 64
        qs = ','.join(path + '.' + inst('buf', b) + '.q' for b in reversed(range(width)))
        if name == 'rA': rhs = '{initial_s[21][15:14],initial_s[21][7:6]}'
        elif width == 8: rhs = f'initial_s[{i}][7:0]'
        else: rhs = f'initial_s[{i}]'
        lines.append(f'{{{qs}}}={rhs};')
    lines += ['end endtask']
    for i in range(256):
        lines.append(f'assign g[{i}]={reg}{inst("ram_r1")}.mem[{i}];')
    for i, name in enumerate(SPECIAL):
        if name not in DIRECT:
            lines.append(f'assign s[{i}]={reg}{inst("special_ram")}.mem[{i}];')
            continue
        path = reg + inst('direct_'+name) + '.' + p('direct_out')
        if name == 'rA': path = "{48'b0," + path + "[3:2],6'b0," + path + "[1:0],6'b0}"
        elif name in ('rG','rL'): path = "{56'b0," + path + "}"
        lines.append(f'assign s[{i}]={path};')
    aliases = {'clk':p('clock'), 'rst':p('reset'), 'calib':p('init_calib_complete'),
               'done':p('done'), 'fault':p('fault'), 'reason':p('fault_reason'),
               'pc':ctl+inst('pc')+'.'+p('out'), 'state':ctl+inst('state')+'.'+p('out'),
               'wen':reg+p('general','w_en')}
    for side, fields in [('read','arvalid arready araddr arlen arsize arid arburst arprot rvalid rready rdata rresp rid rlast'),
                         ('write','awvalid awready awaddr awlen awsize awid awburst wvalid wready wdata wstrb wlast bvalid bready bid bresp')]:
        for field in fields.split(): aliases[field] = p('axi',side,field)
    template = r'''
reg [7:0] memory[0:4127];reg [63:0] initial_g[0:255],initial_s[0:31];reg [63:0] settings[0:3];
integer cycles=0,j,k,profile=0,rpending=0,rdelay=0,raddress=0,aw_seen=0,w_seen=0,applied=0,bdelay=0,waddress=0;
integer writes=0,stores=0,attempts=0,reads=0,acks=0,quiet=0,fd,queue,n,scan,case_no,limit;
integer arst=0,awst=0,wst=0;reg [29:0] oldar,oldaw;reg [255:0] oldw,saved_data;reg [31:0] oldstrb,saved_strb;
string queue_path,directory;reg running=0;
function integer index_of(input integer address);begin
 if(address>=0 && address<4096) index_of=address;
 else if(address>=32'h3fffffe0 && address<32'h40000000) index_of=4096+address-32'h3fffffe0;
 else index_of=-1;end endfunction
initial @clk@=0;always #5 @clk@=~@clk@;
always @(negedge @clk@) begin
 if(@rst@) begin @arready@=0;@rvalid@=0;@awready@=0;@wready@=0;@bvalid@=0;end
 else begin
 @arready@=(!rpending && !@rvalid@ && (profile==0 || cycles%4==1));
 @awready@=(!aw_seen && !applied && (profile==0 || cycles%5==2));
 @wready@=(!w_seen && !applied && (profile==0 || cycles%7==3));
 if(rpending && !@rvalid@) begin if(rdelay>0) rdelay=rdelay-1;else begin
 @rresp@=0;for(j=0;j<32;j=j+1) begin
 k=index_of(raddress+j);if(k<0) begin @rdata@[j*8+:8]=0;@rresp@=2;end else @rdata@[j*8+:8]=memory[k];end
 @rid@=0;@rlast@=1;@rvalid@=1;end end
 if(applied==1 && !@bvalid@) begin if(bdelay>0) bdelay=bdelay-1;else begin @bid@=0;@bresp@=0;@bvalid@=1;end end
 end end
always @(posedge @clk@) begin
 if(@rst@) begin cycles=0;rpending=0;aw_seen=0;w_seen=0;applied=0;writes=0;stores=0;attempts=0;reads=0;acks=0;arst=0;awst=0;wst=0;end
 else if(running) begin
 cycles=cycles+1;
 if(arst && (!@arvalid@ || @araddr@!==oldar)) $fatal(1,"unstable AR");
 if(awst && (!@awvalid@ || @awaddr@!==oldaw)) $fatal(1,"unstable AW");
 if(wst && (!@wvalid@ || @wdata@!==oldw || @wstrb@!==oldstrb)) $fatal(1,"unstable W");
 arst=@arvalid@ && !@arready@;oldar=@araddr@;awst=@awvalid@ && !@awready@;oldaw=@awaddr@;
 wst=@wvalid@ && !@wready@;oldw=@wdata@;oldstrb=@wstrb@;
 if(@state@==2 && @pc@==settings[2]) attempts=attempts+1;
 if(@wen@) writes=writes+1;
 if(quiet && (@wen@ || @arvalid@ || @awvalid@ || @wvalid@)) $fatal(1,"activity after stop");
 if(@arvalid@ && @arready@) begin
 if(@arlen@!==0 || @arsize@!==5 || @arid@!==0 || @araddr@[4:0]!==0 || @arburst@!==1) $fatal(1,"read shape");
 rpending=1;rdelay=(profile==0?0:3);raddress=@araddr@;reads=reads+1;end
 if(@rvalid@ && @rready@) begin rpending=0;@rvalid@<=0;end
 if(@awvalid@ && @awready@) begin
 if(@awlen@!==0 || @awsize@!==5 || @awid@!==0 || @awaddr@[4:0]!==0 || @awburst@!==1) $fatal(1,"write shape");
 aw_seen=1;waddress=@awaddr@;end
 if(@wvalid@ && @wready@) begin if(!@wlast@) $fatal(1,"missing WLAST");w_seen=1;saved_data=@wdata@;saved_strb=@wstrb@;end
 if(aw_seen && w_seen && !applied) begin
 for(j=0;j<32;j=j+1) if(saved_strb[j]) begin k=index_of(waddress+j);if(k<0) $fatal(1,"unmapped write");memory[k]=saved_data[j*8+:8];end
 applied=1;bdelay=(profile==0?0:11);stores=stores+1;end
 if(@bvalid@ && @bready@) begin acks=acks+1;applied=0;aw_seen=0;w_seen=0;@bvalid@<=0;end
 end end
task dump;begin
 fd=$fopen({directory,"/actual.txt"},"w");
 $fwrite(fd,"%0d %0d %0d %0d %0d %0d %0d %0d %0d %016h\n",@done@,@fault@,@reason@,cycles,attempts,writes,stores,reads,acks,@pc@);
 for(j=0;j<256;j=j+1) $fwrite(fd,"%016h\n",g[j]);
 for(j=0;j<32;j=j+1) $fwrite(fd,"%016h\n",s[j]);
 for(j=0;j<4128;j=j+1) $fwrite(fd,"%02h\n",memory[j]);
 $fclose(fd);end endtask
initial begin
@INITIAL@
 if(!$value$plusargs("queue=%s",queue_path)) $fatal(1,"missing queue");queue=$fopen(queue_path,"r");
 scan=$fscanf(queue,"%d\n",n);
 for(case_no=0;case_no<n;case_no=case_no+1) begin
 scan=$fscanf(queue,"%s\n",directory);if(scan!=1) $fatal(1,"invalid queue");
 @(negedge @clk@);#1;running=0;quiet=0;@rst@=1;@calib@=0;
 $readmemh({directory,"/image.hex"},memory);$readmemh({directory,"/registers.hex"},initial_g);
 $readmemh({directory,"/special.hex"},initial_s);$readmemh({directory,"/config.hex"},settings);profile=settings[0];
 repeat(3) @(negedge @clk@);#1;@rst@=0;fixture();
 repeat(2) @(negedge @clk@);#1;running=1;@calib@=1;limit=0;
 while(!@done@ && !@fault@ && limit<settings[1]) begin @(negedge @clk@);#1;limit=limit+1;end
 if(@done@ || @fault@) begin quiet=1;repeat(10) begin @(negedge @clk@);#1;end end
 dump();$display("CASE %s cycles=%0d",directory,cycles);
 end
 $fclose(queue);$finish;end
endmodule
'''
    init = '\n'.join(f"{v['name']}=0;" for v in top['ports'] if v['direction']=='in' and v['path']!=['clock'])
    template = template.replace('@INITIAL@',init)
    for key, value in aliases.items(): template=template.replace('@'+key+'@',value)
    if '@' in template.replace('@(', ''):  # Only timing event syntax should remain.
        import re
        if re.search(r'@[A-Za-z_]+@',template): raise ValueError('Unresolved TB placeholder')
    return '\n'.join(lines)+template
