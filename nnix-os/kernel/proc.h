#ifndef _PROC_H
#define _PROC_H

// BIOS를 통해 앱을 로드·실행. 성공 시 반환하지 않음.
// $255 = 실행파일 FAT 클러스터 번호
// $254 = 인자 링크파일 FAT 클러스터 번호 (없으면 0)
// $253 = RAM 크기 (BIOS가 부팅 시 측정, proc_execve는 건드리지 않음)
long proc_execve(long exe_cluster, long link_cluster);

#endif
