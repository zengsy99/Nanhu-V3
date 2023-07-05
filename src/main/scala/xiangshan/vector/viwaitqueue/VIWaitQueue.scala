package xiangshan.vector.viwaitqueue

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.vector._
import xiangshan.vector.vtyperename.VtypeInfo
import xs.utils._
import xiangshan.backend.rob._


class vimop(implicit p: Parameters) extends VectorBaseBundle {
  val victrl = new VICtrl
  val state = 2.U(1.W)
}

class WqPtr(implicit p: Parameters) extends CircularQueuePtr[WqPtr](
  p => p(XSCoreParamsKey).RobSize
) with HasCircularQueuePtrHelper {

  def needFlush(redirect: Valid[Redirect]): Bool = {
    val flushItself = redirect.bits.flushItself() && this === redirect.bits.robIdx
    redirect.valid && (flushItself || isAfter(this, redirect.bits.robIdx))
  }

  def needFlush(redirect: Seq[Valid[Redirect]]): Bool = VecInit(redirect.map(needFlush)).asUInt.orR
}

object WqPtr {
  def apply(f: Bool, v: UInt)(implicit p: Parameters): WqPtr = {
    val ptr = Wire(new WqPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class WqEnqIO(implicit p: Parameters) extends VectorBaseBundle  {
  val canAccept = Output(Bool())
  val isEmpty = Output(Bool())
  // valid vector, for robIdx gen and walk
  val needAlloc = Vec(VIDecodeWidth, Input(Bool()))
  val req = Vec(VIDecodeWidth, Flipped(DecoupledIO(new VICtrl)))
  val resp = Vec(VIDecodeWidth, Output(new WqPtr))
}

class VIWaitQueue(implicit p: Parameters) extends VectorBaseModule with HasCircularQueuePtrHelper {

  val io = IO(new Bundle() {
    val hartId = Input(UInt(8.W))
    val redirect = Input(Valid(new Redirect))
    val enq = new WqEnqIO
    val vtype = Vec(VIDecodeWidth, Flipped(ValidIO(new VtypeInfo)))
    val WqFull = Output(Bool())
  })

  // pointers
  // For enqueue ptr, we don't duplicate it since only enqueue needs it.
  val enqPtrVec = Wire(Vec(VIDecodeWidth, new WqPtr))
  val deqPtr = Wire(new WqPtr)

  val allowEnqueue = RegInit(true.B)

  val enqPtr = enqPtrVec.head
  val vtypePtr = RegInit(enqPtr)

  val isEmpty = enqPtr === deqPtr
  val isReplaying = io.redirect.valid && RedirectLevel.flushItself(io.redirect.bits.level)

  /**
    * states of Wq
    */
  val s_valid :: s_busy :: s_invalid :: Nil = Enum(3)


  val WqData = Module(new SyncDataModuleTemplate(new vimop, 192, 1, VIDecodeWidth, "VIWaitqueue", concatData = true))

  /**
    * pointers and counters
    */
  // dequeue pointers
  val isComplete = RegInit(true.B)
  val deqPtr_temp = deqPtr + 1
  val deqPtr_next = Mux(!io.redirect.valid && isComplete, deqPtr, deqPtr_temp)
  deqPtr := deqPtr_next

  // enqueue pointers
  val enqPtrVec_temp = RegInit(VecInit.tabulate(VIDecodeWidth)(_.U.asTypeOf(new WqPtr)))
  val canAccept = allowEnqueue
  val enqNum = Mux(canAccept, PopCount(VecInit(io.enq.req.map(_.valid))), 0.U)
  for ((ptr, i) <- enqPtrVec_temp.zipWithIndex) {
    when(io.redirect.valid) {
      ptr := ptr
    }.otherwise {
      ptr := ptr + enqNum
    }
  }
  enqPtrVec := enqPtrVec_temp


  /**
    * Enqueue
    */
  val numValidEntries = distanceBetween(enqPtr, deqPtr)
  allowEnqueue := numValidEntries + enqNum <= (192 - VIDecodeWidth).U
  val allocatePtrVec = VecInit((0 until VIDecodeWidth).map(i => enqPtrVec(PopCount(io.enq.needAlloc.take(i)))))
  io.enq.canAccept := allowEnqueue && !io.redirect.valid
  io.enq.resp := allocatePtrVec
  val canEnqueue = VecInit(io.enq.req.map(_.valid && io.enq.canAccept))


  /**
    * read and write of data modules
    */
  val ReadAddr_next = VecInit(deqPtr_next.value)

  WqData.io.wen := canEnqueue
  WqData.io.waddr := allocatePtrVec.map(_.value)
  WqData.io.wdata.zip(io.enq.req.map(_.bits)).foreach { case (wdata, req) =>
    wdata.victrl := req
    wdata.state := s_busy
  }
  WqData.io.raddr := ReadAddr_next
  val WqDataRead = WqData.io.rdata

  /**
    * instruction split (Dequeue)
    */




  /**
    * vtype update
    */
  val vtypenum = PopCount(io.vtype.map(_.valid))
  for (i <- 0 until VIDecodeWidth) {
    val ftq = io.vtype(i).bits.cf.ftqPtr
    val offset = io.vtype(i).bits.cf.ftqOffset
    WqData.io.raddr := vtypePtr.value
    val tempdata = WqData.io.rdata
    if (tempdata(0).victrl.vicf.cf.ftqPtr == ftq && tempdata(0).victrl.vicf.cf.ftqOffset == offset) {
      WqData.io.waddr := vtypePtr.value
      WqData.io.wdata.zip(io.vtype.map(_.bits)).foreach { case (wdata, req) =>
        wdata.victrl.viinfo.vsew := req.ESEW
        wdata.victrl.viinfo.vlmul := req.ELMUL
        wdata.victrl.robIdx := req.robIdx
        wdata.state := req.state - 1.U
      }
      vtypePtr := vtypePtr + 1
    }
  }

}