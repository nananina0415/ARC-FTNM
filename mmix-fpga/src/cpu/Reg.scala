// Reg는 레지스터 접근을 담당한다.
// 쓰기는 커미터에서만 수행하고 읽기는 각 파이프에서 요청한다.
// 항상 쓰기가 우선된다.
// 읽기는 reorder 버퍼의 앞에있는 파이프의 요청부터 처리한다.

package cpu

import chisel3._

/** 파이프가 레지스터 파일에서 하나의 피연산자를 읽을 때 사용하는 포트. */
case class RegReadPort() extends Bundle {
  val addr = Output(UInt(8.W))  // 레지스터 번호
  val data = Input(UInt(64.W))  // 읽은 값
}