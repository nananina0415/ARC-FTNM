#!/usr/bin/env python3
"""
ARC-FTNM NOTICE 생성기.

Usage: gen_license_notice.py <notice-map.json> <output-NOTICE>

notice-map.json 형식:
  {
    "unit": "nnix-os",
    "components": [
      {
        "name"         : "표시 이름",
        "copyright"    : "Copyright (C) ...",
        "license"      : "SIL-OFL-1.1",   ← 아래 TEMPLATES 키
        "source"       : "https://...",
        "license_file" : "상대경로/LICENSE" ← 전문이 필요한 라이선스만
        "notes"        : "추가 설명"        ← 선택
      }
    ]
  }

라이선스별 전략:
  SIL-OFL-1.1  → 고지문 + URL
  BSD-1        → 조건 1개 인용
  MIT          → license_file 전문 포함  (MIT 필수 요건)
  Apache-2.0   → 고지문 + URL            (Apache는 URL 참조 허용)
  LGPL-3.0     → license_file 전문 포함  (LGPL 필수 요건)
  GPL-2.0      → 고지문 + 소스 참조
"""

import json, pathlib, sys

ROOT = pathlib.Path(__file__).parent.parent   # ARC-FTNM 루트
SEP  = '─' * 72


def _section(title: str, body: str) -> str:
    return f'{SEP}\n{title}\n{SEP}\n{body.strip()}\n'


def _read_license(c: dict) -> str:
    path = ROOT / c['license_file']
    return path.read_text(encoding='utf-8').strip()


# ── 라이선스 템플릿 ────────────────────────────────────────────────────────────
# 각 함수는 component dict를 받아 섹션 문자열을 반환한다.

def tpl_sil_ofl_1_1(c: dict) -> str:
    notes = f'\n{c["notes"]}\n' if c.get('notes') else ''
    body = (
        f'{c["name"]}\n'
        f'{c["copyright"]}\n\n'
        f'License : SIL Open Font License v1.1\n'
        f'Source  : {c.get("source", "")}\n'
        f'Info    : https://scripts.sil.org/OFL\n\n'
        f'The SIL OFL permits embedding font data in software without\n'
        f'requiring that software to be released under the OFL.'
        f'{notes}'
    )
    return _section(f'{c["name"]} — SIL Open Font License v1.1', body)


def tpl_bsd_1(c: dict) -> str:
    body = (
        f'{c["name"]}\n'
        f'{c["copyright"]}\n\n'
        f'License : BSD 1-Clause\n'
        f'Source  : {c.get("source", "")}\n\n'
        f'Redistribution and use in source and binary forms, with or without\n'
        f'modification, are permitted provided that the following condition is met:\n\n'
        f'  Redistributions of source code must retain the above copyright\n'
        f'  notice, this condition and the following disclaimer.\n\n'
        f'THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND.'
    )
    return _section(f'{c["name"]} — BSD 1-Clause', body)


def tpl_mit(c: dict) -> str:
    full = _read_license(c)
    body = (
        f'{c["name"]}\n'
        f'{c["copyright"]}\n\n'
        f'License : MIT\n'
        f'Source  : {c.get("source", "")}\n\n'
        f'{full}'
    )
    return _section(f'{c["name"]} — MIT', body)


def tpl_apache_2_0(c: dict) -> str:
    body = (
        f'{c["name"]}\n'
        f'{c["copyright"]}\n\n'
        f'License : Apache License 2.0\n'
        f'Source  : {c.get("source", "")}\n\n'
        f'Licensed under the Apache License, Version 2.0 (the "License");\n'
        f'you may not use this software except in compliance with the License.\n'
        f'You may obtain a copy of the License at:\n'
        f'  https://www.apache.org/licenses/LICENSE-2.0\n\n'
        f'Unless required by applicable law or agreed to in writing, software\n'
        f'distributed under the License is distributed on an "AS IS" BASIS,\n'
        f'WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.'
    )
    return _section(f'{c["name"]} — Apache License 2.0', body)


def tpl_lgpl_3_0(c: dict) -> str:
    full = _read_license(c)
    notes = f'\n{c["notes"]}\n' if c.get('notes') else ''
    body = (
        f'{c["name"]}\n'
        f'{c["copyright"]}\n\n'
        f'License : GNU Lesser General Public License v3\n'
        f'Source  : {c.get("source", "")}\n\n'
        f'LGPL v3 requires the complete license text to accompany the software.\n'
        f'The full text follows:\n\n'
        f'{full}'
        f'{notes}'
    )
    return _section(f'{c["name"]} — LGPL v3', body)


def tpl_gpl_2_0(c: dict) -> str:
    notes = f'\n{c["notes"]}\n' if c.get('notes') else ''
    body = (
        f'{c["name"]}\n'
        f'{c["copyright"]}\n\n'
        f'License : GNU General Public License v2\n'
        f'Source  : {c.get("source", "")}\n\n'
        f'This software is free software; you can redistribute it and/or modify\n'
        f'it under the terms of the GNU General Public License as published by\n'
        f'the Free Software Foundation; either version 2 of the License, or\n'
        f'(at your option) any later version.\n\n'
        f'Full license text: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html\n'
        f'Source code is available at the repository URL above.'
        f'{notes}'
    )
    return _section(f'{c["name"]} — GPL v2', body)


TEMPLATES = {
    'SIL-OFL-1.1': tpl_sil_ofl_1_1,
    'BSD-1':        tpl_bsd_1,
    'MIT':          tpl_mit,
    'Apache-2.0':   tpl_apache_2_0,
    'LGPL-3.0':     tpl_lgpl_3_0,
    'GPL-2.0':      tpl_gpl_2_0,
}


def generate(map_path: pathlib.Path, out_path: pathlib.Path) -> None:
    data = json.loads(map_path.read_text(encoding='utf-8'))
    unit = data.get('unit', map_path.parent.name)

    header = (
        f'NOTICE — {unit}\n'
        f'{"=" * (9 + len(unit))}\n'
        f'License notices for third-party components used in this build unit.\n'
        f'Generated by tools/gen_license_notice.py from {map_path.name}.\n'
    )

    parts = [header]
    for comp in data['components']:
        lic = comp.get('license', '')
        tpl = TEMPLATES.get(lic)
        if tpl is None:
            print(f'Warning: unknown license "{lic}" for {comp.get("name")}',
                  file=sys.stderr)
            continue
        parts.append(tpl(comp))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text('\n'.join(parts), encoding='utf-8')
    print(f'Generated {out_path}  ({len(data["components"])} components)')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f'Usage: {sys.argv[0]} <notice-map.json> <output-NOTICE>',
              file=sys.stderr)
        sys.exit(1)
    generate(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
