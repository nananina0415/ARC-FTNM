"""BIOS ROM을 mmix-sim에서 돌려 보는 테스트.

SD 드라이버만 sd_sim.s(호스트 파일 disk.img를 TRAP으로 읽음)로 바꾸고 나머지 ROM 코드는
그대로 링크한다. 시나리오마다 FAT32 이미지와 진입 레지스터를 정하고, 실행파일(payload.s)이
출력한 값이나 ROM의 종료 코드($255)를 기대값과 비교한다.

사용법:
  python run_rom_test.py --bin <mmix-as/mmix-ld 디렉토리> --mmix <mmix 시뮬레이터> [--out <빌드 디렉토리>]
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROM_DIR = HERE.parent
NNIX_DIR = ROM_DIR.parent
LINK_LD = NNIX_DIR / "link.ld"

SECTOR = 512
APP_G = 128
ROM_RAM_SIZE = 0x800
CTX_SIZE = (4 + 1 + (256 - APP_G) + 12 + 1) * 8

ERR = {"SD": 1, "MOUNT": 2, "NO_OS": 3, "NO_EXE": 4, "READ": 5, "IMAGE": 6, "MEM": 7}

ROM_SOURCES = [ROM_DIR / "main.s", ROM_DIR / "boot.s", ROM_DIR / "loader.s", ROM_DIR / "fat.s", HERE / "sd_sim.s"]


def run(cmd, cwd=None):
    r = subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"실패: {' '.join(map(str, cmd))}\n{r.stdout}{r.stderr}")
    return r


# ── 빌드 ────────────────────────────────────────────────────────────

class Tools:
    def __init__(self, bin_dir, mmix):
        exe = ".exe" if os.name == "nt" else ""
        self.as_ = Path(bin_dir) / f"mmix-as{exe}"
        self.ld = Path(bin_dir) / f"mmix-ld{exe}"
        self.mmix = Path(mmix)


def build_payload(tools, out, pid, data):
    d = out / f"payload{pid}"
    d.mkdir(parents=True, exist_ok=True)
    (d / "data.bin").write_bytes(data)
    run([tools.as_, "-I", d, "--defsym", f"PAYLOAD_ID={pid}", "-o", d / "payload.o", HERE / "payload.s"])
    run([tools.ld, "-T", LINK_LD, "-o", d / "payload.bin", d / "payload.o"])
    image = (d / "payload.bin").read_bytes()
    fix_end = int.from_bytes(image[-8:], "big")
    return {"id": pid, "image": image, "fix_end": fix_end, "sum": sum(data)}


def build_rom_objects(tools, out):
    d = out / "rom"
    d.mkdir(parents=True, exist_ok=True)
    objs = []
    for src in ROM_SOURCES:
        o = d / (src.stem + ".o")
        run([tools.as_, "-I", ROM_DIR, "-o", o, src])
        objs.append(o)
    return objs


def link_sim(tools, d, objs, mem_size, exe, link, crash):
    run([tools.as_, "--defsym", f"SIM_EXE={exe}", "--defsym", f"SIM_LINK={link}",
         "--defsym", f"SIM_CRASH={crash}", "-o", d / "sim_entry.o", HERE / "sim_entry.s"])
    run([tools.ld, "-T", HERE / "rom_sim.ld", "--defsym", f"MEM_SIZE={mem_size:#x}",
         "-o", d / "rom.mmo", *objs, d / "sim_entry.o"])


# ── FAT32 이미지 ────────────────────────────────────────────────────

def le(v, n):
    return v.to_bytes(n, "little")


def short_entry(name11, attr, cluster, size, first=None):
    e = bytearray(32)
    e[0:11] = name11.encode("ascii")
    if first is not None:
        e[0] = first
    e[11] = attr
    e[20:22] = le(cluster >> 16, 2)
    e[26:28] = le(cluster & 0xFFFF, 2)
    e[28:32] = le(size, 4)
    return bytes(e)


def lfn_entries(long_name, name11):
    csum = 0
    for b in name11.encode("ascii"):
        csum = (((csum & 1) << 7) + (csum >> 1) + b) & 0xFF
    chars = [ord(c) for c in long_name] + [0]
    while len(chars) % 13:
        chars.append(0xFFFF)
    parts = [chars[i:i + 13] for i in range(0, len(chars), 13)]
    out = []
    for i, part in enumerate(parts):
        e = bytearray(32)
        e[0] = (i + 1) | (0x40 if i == len(parts) - 1 else 0)
        for j, c in enumerate(part):
            off = [1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30][j]
            e[off:off + 2] = le(c, 2)
        e[11] = 0x0F
        e[13] = csum
        out.append(bytes(e))
    return list(reversed(out))


class Fat32:
    def __init__(self, spc, n_clusters, mbr):
        self.spc = spc
        self.csize = spc * SECTOR
        self.n = n_clusters
        self.rsvd = 32
        self.nfats = 2
        self.fatsz = -(-(n_clusters + 2) * 4 // SECTOR)
        self.vol = 2048 if mbr else 0
        self.mbr = mbr
        self.fat_lba = self.vol + self.rsvd
        self.data_lba = self.fat_lba + self.nfats * self.fatsz
        self.total = self.data_lba + n_clusters * spc
        self.fat = [0] * (n_clusters + 2)
        self.fat[0] = 0x0FFFFFF8
        self.fat[1] = 0x0FFFFFFF
        self.clusters = {}
        self.root = [2]
        self.dir = []

    def put(self, clusters, data):
        need = max(1, -(-len(data) // self.csize))
        assert len(clusters) >= need, (len(clusters), need)
        clusters = clusters[:need]
        for i, c in enumerate(clusters):
            assert self.fat[c] == 0, c
            self.fat[c] = clusters[i + 1] if i + 1 < len(clusters) else 0x0FFFFFFF
            self.clusters[c] = data[i * self.csize:(i + 1) * self.csize]
        return clusters

    def add_file(self, name11, clusters, data):
        cl = self.put(clusters, data)
        self.dir.append(short_entry(name11, 0x20, cl[0], len(data)))
        return cl[0]

    def save(self, path, root_clusters):
        raw = b"".join(self.dir)
        cap = len(root_clusters) * self.csize
        assert len(raw) < cap
        self.put(root_clusters, raw.ljust(cap, b"\0"))
        img = bytearray(self.total * SECTOR)
        if self.mbr:
            p = 0x1BE
            img[p + 4] = 0x0C
            img[p + 8:p + 12] = le(self.vol, 4)
            img[p + 12:p + 16] = le(self.total - self.vol, 4)
            img[510:512] = b"\x55\xAA"
        b = self.vol * SECTOR
        img[b:b + 3] = b"\xEB\x58\x90"
        img[b + 3:b + 11] = b"MSWIN4.1"
        img[b + 0x0B:b + 0x0D] = le(SECTOR, 2)
        img[b + 0x0D] = self.spc
        img[b + 0x0E:b + 0x10] = le(self.rsvd, 2)
        img[b + 0x10] = self.nfats
        img[b + 0x15] = 0xF8
        img[b + 0x20:b + 0x24] = le(self.total - self.vol, 4)
        img[b + 0x24:b + 0x28] = le(self.fatsz, 4)
        img[b + 0x2C:b + 0x30] = le(root_clusters[0], 4)
        img[b + 0x30:b + 0x32] = le(1, 2)
        img[b + 0x32:b + 0x34] = le(6, 2)
        img[b + 0x42] = 0x29
        img[b + 0x47:b + 0x52] = b"NNIXTEST   "
        img[b + 0x52:b + 0x5A] = b"FAT32   "
        img[b + 510:b + 512] = b"\x55\xAA"
        fs = b + SECTOR
        img[fs:fs + 4] = le(0x41615252, 4)
        img[fs + 484:fs + 488] = le(0x61417272, 4)
        img[fs + 488:fs + 492] = le(0xFFFFFFFF, 4)
        img[fs + 492:fs + 496] = le(0xFFFFFFFF, 4)
        img[fs + 510:fs + 512] = b"\x55\xAA"
        fat = b"".join(le(v, 4) for v in self.fat)
        for i in range(self.nfats):
            o = (self.fat_lba + i * self.fatsz) * SECTOR
            img[o:o + len(fat)] = fat
        for c, data in self.clusters.items():
            o = (self.data_lba + (c - 2) * self.spc) * SECTOR
            img[o:o + len(data)] = data
        Path(path).write_bytes(img)


def image_a(path, os_img, app_img):
    """512B 클러스터, MBR, FAT32 최소 클러스터 수 이상. 루트 디렉토리와 파일이 조각나 있고
    FAT 섹터 경계(128 클러스터)를 넘나든다."""
    f = Fat32(spc=1, n_clusters=66000, mbr=True)
    os_clusters = [10, 11, 12, 200, 201, 13, 300, 14, 15, 400]
    app_clusters = [60, 16, 61, 500, 17, 18]
    f.dir.append(short_entry("NNIXTEST   ", 0x08, 0, 0))
    # 삭제된 항목: 시작 클러스터는 APP.EXE와 같고 크기가 다르다. 건너뛰지 않으면 크기가 틀린다.
    f.dir.append(short_entry("NNIX    OS ", 0x20, app_clusters[0], 999999, first=0xE5))
    f.dir.extend(lfn_entries("a_long_name.txt", "A_LONG~1TXT"))
    f.add_file("A_LONG~1TXT", [20], b"long name file\n")
    # 이름이 같은 디렉토리 항목은 건너뛴다.
    f.dir.append(short_entry("NNIX    OS ", 0x10, 40, 0))
    f.put([40], b"\0" * 32)
    for i in range(10):
        f.add_file(f"FILE{i:02d}  TXT", [100 + i], f"filler {i}\n".encode())
    assert len(f.dir) == 16  # 루트 첫 클러스터가 꽉 찬다
    os_c = f.add_file("NNIX    OS ", os_clusters, os_img)
    app_c = f.add_file("APP     EXE", app_clusters, app_img)
    link_c = f.add_file("LINK    TXT", [700], b"app arg1 arg2\n")
    f.save(path, [2, 129])
    return {"os": os_c, "app": app_c, "link": link_c}


def image_b(path, os_img):
    """4KB 클러스터(8섹터), MBR 없음(0번 섹터가 볼륨). 클러스터 수는 FAT32 최소치보다 적다."""
    f = Fat32(spc=8, n_clusters=1000, mbr=False)
    os_c = f.add_file("NNIX    OS ", [5, 3], os_img)
    f.save(path, [2])
    return {"os": os_c}


def image_bad_size(path, os_img):
    """NNIX.OS 크기가 8의 배수가 아니다."""
    f = Fat32(spc=1, n_clusters=66000, mbr=True)
    os_c = f.add_file("NNIX    OS ", list(range(10, 30)), os_img[:-4])
    f.save(path, [2])
    return {"os": os_c}


def image_no_os(path, app_img):
    f = Fat32(spc=1, n_clusters=66000, mbr=True)
    app_c = f.add_file("APP     EXE", list(range(10, 30)), app_img)
    f.save(path, [2])
    return {"app": app_c}


# ── 실행과 비교 ─────────────────────────────────────────────────────

def layout(fix_end, mem_size):
    gap = (mem_size - fix_end) % 128
    unfix_start = fix_end + gap
    unfix_size = mem_size - unfix_start
    return unfix_start + unfix_size * 3 // 4


def check_payload(out, payload, mem_size, regs):
    vals = [int(t, 16) for t in out.split()]
    names = ["ID", "$0", "$1", "$2", "$3", "rL", "rS", "rO", "rG", "_fix_end", "sum"]
    if len(vals) != len(names):
        return [f"출력 형식이 다름: {out!r}"]
    rs = layout(payload["fix_end"], mem_size)
    want = [payload["id"], regs[0], regs[1], mem_size, regs[2], 4, rs, rs, APP_G,
            payload["fix_end"], payload["sum"]]
    return [f"{n}: {g:#x} (기대 {w:#x})" for n, g, w in zip(names, vals, want) if g != w]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--mmix", required=True)
    ap.add_argument("--out", default=str(HERE / "build"))
    args = ap.parse_args()
    tools = Tools(args.bin, args.mmix)
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)

    os_pl = build_payload(tools, out, 1, bytes((i * 7 + 3) & 0xFF for i in range(4500)))
    app_pl = build_payload(tools, out, 2, bytes((i * 13 + 5) & 0xFF for i in range(2200)))
    objs = build_rom_objects(tools, out)

    imgs = out / "images"
    imgs.mkdir(exist_ok=True)
    a = image_a(imgs / "a.img", os_pl["image"], app_pl["image"])
    b = image_b(imgs / "b.img", os_pl["image"])
    c = image_bad_size(imgs / "bad_size.img", os_pl["image"])
    d = image_no_os(imgs / "no_os.img", app_pl["image"])

    big = 0x20000000
    odd = 0x3FFEDC8
    tiny = (os_pl["fix_end"] + 1024 + 7) & ~7
    # (이름, 이미지, MEM_SIZE, 진입 $255/$254/$253, 기대: payload 또는 오류 코드, payload가 받을 $0/$1/$3)
    cases = [
        ("reset_a", "a.img", big, (0, 0, 0), os_pl, (a["os"], 0, 0)),
        ("execve_app_a", "a.img", big, (a["app"], a["link"], 1), app_pl, (a["app"], a["link"], 1)),
        ("execve_os_a", "a.img", big, (a["os"], 0, 0), os_pl, (a["os"], 0, 0)),
        ("reset_a_odd_mem", "a.img", odd, (0, 0, 0), os_pl, (a["os"], 0, 0)),
        ("reset_b_spc8_no_mbr", "b.img", big, (0, 0, 0), os_pl, (b["os"], 0, 0)),
        ("execve_missing_cluster", "a.img", big, (5000, 0, 0), ERR["NO_EXE"], None),
        ("reset_bad_size", "bad_size.img", big, (0, 0, 0), ERR["IMAGE"], None),
        ("reset_no_os", "no_os.img", big, (0, 0, 0), ERR["NO_OS"], None),
        ("reset_mem_too_small", "a.img", tiny, (0, 0, 0), ERR["MEM"], None),
    ]

    failed = 0
    for name, img, mem, entry, expect, regs in cases:
        d_ = out / "cases" / name
        d_.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(imgs / img, d_ / "disk.img")
        link_sim(tools, d_, objs, mem, *entry)
        r = subprocess.run([str(tools.mmix), "rom.mmo"], cwd=d_, capture_output=True, text=True, timeout=600)
        stdout = r.stdout.strip()
        if isinstance(expect, dict):
            errs = check_payload(stdout, expect, mem, regs) if r.returncode == 0 else [f"종료 코드 {r.returncode}"]
        else:
            errs = [] if (r.returncode == expect and not stdout) else [f"종료 코드 {r.returncode} (기대 {expect}), 출력 {stdout!r}"]
        if r.stderr.strip():
            errs.append(f"stderr: {r.stderr.strip()}")
        print(f"{'PASS' if not errs else 'FAIL'} {name}  exit={r.returncode}  {stdout}")
        for e in errs:
            print(f"     {e}")
        failed += bool(errs)
        (d_ / "disk.img").unlink()

    print(f"\n{len(cases) - failed}/{len(cases)} 통과")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
