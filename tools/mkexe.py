"""ELF(elf64-mmix)를 nnix 실행파일로 변환한다.

파일 형식 (값은 모두 빅엔디언 octa):
  [헤더 512B]
    0x00  매직 "NNIXEXE\\0"
    0x08  헤더 크기 (512)
    0x10  BSS 포함 길이 = fix_end (이미지는 0번지에 올라간다)
    0x18  BSS 뺀 이미지 길이 (512의 배수, 남는 부분은 0)
    0x20  레지스터 영역 길이
  [이미지]        0번지부터 적재한다. 진입점은 0번지.
  [레지스터 영역]  로더가 계산한 rS부터 적재하고 UNSAVE 한다. UNSAVE 컨텍스트 형식:
    로컬 $0~$3 (로더가 채움), hole = 4, 전역 $G~$255, 특수 12개(rB rD rE rH rJ rM rR rP rW rX rY rZ) = 0,
    (G << 56) | rA (rA = 0)

전역 레지스터 초기값은 링커가 .MMIX.reg_contents에 둔다. 이 섹션의 주소는 G*8이고 $G~$254를 담는다.
$255는 0으로 둔다(로더가 진입 GO에 쓴다).

사용법: python mkexe.py <입력.elf> <출력>
"""

import struct
import sys

MAGIC = b"NNIXEXE\0"
HEADER_SIZE = 512
SECTOR = 512
APP_LOCALS = 4
SPECIALS = 12
EM_MMIX = 80
PT_LOAD = 1


def fail(message):
    sys.exit(f"mkexe: {message}")


def read_elf(data):
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 2:
        fail("ELF64 빅엔디언 파일이 아니다")
    (e_type, e_machine, _, e_entry, e_phoff, e_shoff, _, _, e_phentsize, e_phnum,
     e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from(">HHIQQQIHHHHHH", data, 16)
    if e_machine != EM_MMIX:
        fail(f"MMIX ELF가 아니다 (e_machine={e_machine})")

    segments = []
    for i in range(e_phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = struct.unpack_from(
            ">IIQQQQQQ", data, e_phoff + i * e_phentsize)
        if p_type == PT_LOAD and p_memsz:
            segments.append((p_offset, p_vaddr, p_filesz, p_memsz))

    sections = []
    for i in range(e_shnum):
        sh_name, _, _, sh_addr, sh_offset, sh_size = struct.unpack_from(
            ">IIQQQQ", data, e_shoff + i * e_shentsize)
        sections.append((sh_name, sh_addr, sh_offset, sh_size))
    strtab_offset = sections[e_shstrndx][2]

    def name(index):
        end = data.index(b"\0", strtab_offset + index)
        return data[strtab_offset + index:end].decode()

    named = {name(s[0]): s for s in sections}
    return segments, named


def build(data):
    segments, sections = read_elf(data)
    if not segments:
        fail("적재할 세그먼트가 없다")
    if min(vaddr for _, vaddr, _, _ in segments) != 0:
        fail("이미지가 0번지에서 시작하지 않는다")

    image_end = max(vaddr + filesz for _, vaddr, filesz, _ in segments)
    fix_end = max(vaddr + memsz for _, vaddr, _, memsz in segments)
    if fix_end % 8:
        fail(f"BSS 끝이 8바이트 정렬이 아니다 ({fix_end:#x})")

    image_len = (image_end + SECTOR - 1) // SECTOR * SECTOR
    image = bytearray(image_len)
    for offset, vaddr, filesz, _ in segments:
        image[vaddr:vaddr + filesz] = data[offset:offset + filesz]

    regs = sections.get(".MMIX.reg_contents")
    if regs is None:
        g, values = 255, []
    else:
        _, addr, offset, size = regs
        g = addr // 8
        if addr % 8 or size != (255 - g) * 8 or not 32 <= g <= 255:
            fail(f".MMIX.reg_contents가 $G~$254 형식이 아니다 (주소 {addr:#x}, 크기 {size})")
        values = list(struct.unpack_from(f">{255 - g}Q", data, offset))

    context = [0] * APP_LOCALS + [APP_LOCALS] + values + [0] + [0] * SPECIALS + [g << 56]
    regs_area = struct.pack(f">{len(context)}Q", *context)

    header = bytearray(HEADER_SIZE)
    header[0:8] = MAGIC
    struct.pack_into(">QQQQ", header, 8, HEADER_SIZE, fix_end, image_len, len(regs_area))
    return bytes(header) + bytes(image) + regs_area, fix_end, image_len, g


def main():
    if len(sys.argv) != 3:
        fail("사용법: mkexe.py <입력.elf> <출력>")
    with open(sys.argv[1], "rb") as f:
        data = f.read()
    out, fix_end, image_len, g = build(data)
    with open(sys.argv[2], "wb") as f:
        f.write(out)
    print(f"mkexe: {sys.argv[2]} fix_end={fix_end:#x} image={image_len:#x} G={g}")


if __name__ == "__main__":
    main()
