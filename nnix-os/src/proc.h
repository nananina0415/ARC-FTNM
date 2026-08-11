#ifndef _PROC_H
#define _PROC_H

// DDR3 클리어 후 지정 경로의 앱을 BIOS를 통해 로드·실행. 성공 시 반환하지 않음.
// $255에 { cluster, argc, argv } 구조체 포인터를 담아 BIOS → 앱으로 전달.
long proc_execve(const char* path, int argc, char** argv);

#endif
