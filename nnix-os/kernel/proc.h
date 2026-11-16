#ifndef _PROC_H
#define _PROC_H

// BIOS를 통해 앱을 로드·실행. 성공 시 반환하지 않음.
// OS·앱 → BIOS 전달:
//   $255 = 실행파일 FAT 클러스터 번호
//   $254 = 인자 링크파일 FAT 클러스터 번호 (없으면 0)
//   $253 = 연속 크래시 횟수 (정상 실행·종료는 0, kernel_crash는 이전 값 + 1)
//
// BIOS(로더) → 앱 전달 (UNSAVE로 설정, crt0.s가 받음):
//   $0 = 실행파일 클러스터, $1 = 링크파일 클러스터, $2 = RAM 크기(mem_size)
//   $3 = 연속 크래시 횟수 (BIOS가 받은 $253 그대로)
//   rS = 두 스택의 기준점. 실행파일 헤더의 fix_end와 mem_size로 계산한다.
//     gap         = (mem_size - fix_end) mod 128
//     unfix_start = fix_end + gap
//     unfix_size  = mem_size - unfix_start
//     rS          = unfix_start + unfix_size * 3/4
//   배치 전체는 mem.h 참고.
long proc_execve(long exe_cluster, long link_cluster, long crash_count);

#endif
