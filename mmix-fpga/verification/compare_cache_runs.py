"""Compare saved clock traces; this does not simulate or infer CPU results."""
from pathlib import Path
import argparse
import json


def metrics(path):
    frames = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    first = next(f for f in frames if f["calibration"] == "1" and f["reset"] == "0")
    halted = next(f for f in frames if f["done"] == "1")
    edges = {key: sum(f["edge"][key] for f in frames) for key in ("AR", "R", "AW", "W", "B")}
    read_addresses = [int(f["axi"]["araddr"], 16) for f in frames if f["edge"]["AR"]]
    # This example has instructions in block 0 and operands in block 64.
    instruction_reads = read_addresses.count(0)
    data_reads = read_addresses.count(64)
    if instruction_reads + data_reads != len(read_addresses):
        raise ValueError("Unexpected memory map; classify additional address blocks explicitly")
    return {"source": path.as_posix(), "snapshots": len(frames),
            "start_cycle": first["cycle"], "halt_cycle": halted["cycle"],
            "execution_cycles": halted["cycle"] - first["cycle"],
            "execution_ns": halted["time_ns"] - first["time_ns"],
            "axi": edges, "instruction_reads": instruction_reads, "data_reads": data_reads,
            "read_bytes": edges["AR"] * 32,
            "result": int(''.join(halted["memory"][96:104]), 16),
            "writebacks": [{"cycle": f["cycle"], "register": int(f["edge"]["dest"], 16),
                            "value": int(f["edge"]["value"], 16)} for f in frames if f["edge"]["write"]]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--before", type=Path, default=Path("build/cache-comparison/before/cycles.jsonl"))
    parser.add_argument("--after", type=Path, default=Path("build/memory-program/cycles.jsonl"))
    parser.add_argument("--out", type=Path, default=Path("build/cache-comparison/comparison.json"))
    args = parser.parse_args()
    before, after = metrics(args.before), metrics(args.after)
    if before["result"] != after["result"] or after["result"] != 579:
        raise ValueError("Program result changed")
    result = {"before": before, "after": after,
              "cycles_saved": before["execution_cycles"] - after["execution_cycles"],
              "execution_time_reduction_percent": 100 * (1 - after["execution_ns"] / before["execution_ns"]),
              "speedup": before["execution_ns"] / after["execution_ns"],
              "axi_read_reduction_percent": 100 * (1 - after["axi"]["AR"] / before["axi"]["AR"])}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
