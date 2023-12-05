package xiangshan.vector.viwaitqueue

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.ExceptionNO.illegalInstr
import xiangshan.backend.rob.RobPtr
import xiangshan.{FuType, MicroOp, Redirect, XSBundle, XSModule}
import xiangshan.vector._
import xiangshan.vector.writeback.VmbPtr
import xiangshan.backend.execute.fu.csr.vcsr._
import xiangshan.vector.EewVal
object WqState {
  def s_updating:UInt = "b0".U
  def s_waiting:UInt = "b1".U
  def apply() = UInt(1.W)
}
class VIWakeQueueEntry(implicit p: Parameters) extends XSBundle{
  val uop = new MicroOp
  val vtypeRdy = Bool()
  val robEnqueued = Bool()
  val mergeIdAlloc = Bool()
  val state = WqState()
}
class ViwqWritePort(implicit p: Parameters) extends XSBundle{
  val wen = Input(Bool())
  val data = Input(new VIWakeQueueEntry)
  val addr = Input(UInt(log2Ceil(VIWaitQueueWidth).W))
}

class ViwqReadPort(implicit p: Parameters) extends XSBundle{
  val addr = Input(UInt(log2Ceil(VIWaitQueueWidth).W))
  val data = new VIWakeQueueEntry
}

class VmsIdAlloc(implicit p: Parameters) extends XSBundle{
  val en = Input(Bool())
  val data = Input(new VmbPtr)
  val addr = Input(UInt(log2Ceil(VIWaitQueueWidth).W))
}

class VIWakeQueueEntryUpdateNetwork(implicit p: Parameters) extends XSModule with HasVectorParameters{
  val io = IO(new Bundle{
    val enq = Input(Valid(new VIWakeQueueEntry))
    val entry = Input(new VIWakeQueueEntry)
    val robEnq = Input(Vec(RenameWidth, Valid(new RobPtr)))
    val vmsResp = Input(Valid(new VmbPtr))
    val vtypeWb = Flipped(ValidIO(new VtypeWbIO))
    val entryNext = Output(new VIWakeQueueEntry)
    val updateEnable = Output(Bool())
  })
  private val entryNext = WireInit(io.entry)
  private val entryEnqNext = io.enq.bits
  entryEnqNext.uop.mergeIdx := DontCare

  private val robEnqHit = io.robEnq.map(r => r.valid && r.bits === io.entry.uop.robIdx).reduce(_|_)
  when(robEnqHit){
    entryNext.robEnqueued := true.B
  }

  when(io.vmsResp.valid){
    entryNext.mergeIdAlloc := true.B
    entryNext.uop.mergeIdx := io.vmsResp.bits
  }

  private val vtypeWbHit = io.vtypeWb.valid && io.vtypeWb.bits.vtypeRegIdx === io.entry.uop.vtypeRegIdx
  when(vtypeWbHit) {
    entryNext.vtypeRdy := true.B
    entryNext.state := WqState.s_updating
    entryNext.uop.vCsrInfo := DontCare
    entryNext.uop.vCsrInfo.vma := io.vtypeWb.bits.vtype(7)
    entryNext.uop.vCsrInfo.vta := io.vtypeWb.bits.vtype(6)
    entryNext.uop.vCsrInfo.vsew := io.vtypeWb.bits.vtype(5, 3)
    entryNext.uop.vCsrInfo.vlmul := io.vtypeWb.bits.vtype(2, 0)
    entryNext.uop.vCsrInfo.vl := io.vtypeWb.bits.vl
    entryNext.uop.vCsrInfo.vlmax := entryNext.uop.vCsrInfo.VLMAXGen()
    entryNext.uop.cf.exceptionVec(illegalInstr) := io.vtypeWb.bits.vtype(8)
  }
  private val vctrlNext = entryNext.uop.vctrl
  private val vctrl = io.entry.uop.vctrl
  private val vcsr = io.entry.uop.vCsrInfo
  private val vlenBytes  = VLEN / 8
  private val isWiden = WireInit(vctrl.isWidden)
  private val isNarrow = vctrl.isNarrow && !vctrl.maskOp
  private val isVgei16 = io.entry.uop.ctrl.fuType === FuType.vpermu && vctrl.eewType(0) === EewType.const && vctrl.eew(0) === EewVal.hword
  when(io.entry.state === WqState.s_updating) {
    for (((vn, v), et) <- vctrlNext.eew.zip(vctrl.eew).zip(vctrl.eewType)) {
      vn := MuxCase(v, Seq(
        (et === EewType.sew) -> vcsr.vsew,
        (et === EewType.sewm2) -> (vcsr.vsew + 1.U),
        (et === EewType.sewd2) -> (vcsr.vsew - 1.U),
        (et === EewType.sewd4) -> (vcsr.vsew - 2.U),
        (et === EewType.sewd8) -> (vcsr.vsew - 3.U),
      ))
    }
    when(isVgei16) {
      vctrlNext.eewType(0) := MuxCase(EewType.sew, Seq(
        (vcsr.vsew === 0.U) -> EewType.sewm2,
        (vcsr.vsew === 1.U) -> EewType.sew,
        (vcsr.vsew === 2.U) -> EewType.sewd2,
        (vcsr.vsew === 3.U) -> EewType.sewd4,
      ))
      when(vcsr.vsew === 0.U) {
        isWiden := true.B
        vctrlNext.isWidden := true.B
      }
    }

    when(vctrl.emulType === EmulType.const) {
      vctrlNext.emul := vctrl.emul
    }.otherwise{
      when(vctrl.isLs && vctrlNext.eewType(0) === EewType.const){
        vctrlNext.emul := (vcsr.vlmul + vctrlNext.eew(0)) - vcsr.vsew
      }.otherwise {
        vctrlNext.emul := vcsr.vlmul
      }
    }

    when(vctrl.isLs) {
      entryNext.uop.uopNum := MuxCase(0.U, Seq(
        (vctrlNext.emul === 0.U(3.W)) -> ((vlenBytes * 1).U >> vctrlNext.eew(0)),
        (vctrlNext.emul === 1.U(3.W)) -> ((vlenBytes * 2).U >> vctrlNext.eew(0)),
        (vctrlNext.emul === 2.U(3.W)) -> ((vlenBytes * 4).U >> vctrlNext.eew(0)),
        (vctrlNext.emul === 3.U(3.W)) -> ((vlenBytes * 8).U >> vctrlNext.eew(0)),
        (vctrlNext.emul === 5.U(3.W)) -> ((vlenBytes / 8).U >> vctrlNext.eew(0)),
        (vctrlNext.emul === 6.U(3.W)) -> ((vlenBytes / 4).U >> vctrlNext.eew(0)),
        (vctrlNext.emul === 7.U(3.W)) -> ((vlenBytes / 2).U >> vctrlNext.eew(0)),
      ))
    }.elsewhen(isNarrow || isWiden) {
      entryNext.uop.uopNum := MuxCase(0.U, Seq(
        (vctrlNext.emul === 0.U(3.W)) -> 2.U,
        (vctrlNext.emul === 1.U(3.W)) -> 4.U,
        (vctrlNext.emul === 2.U(3.W)) -> 8.U,
        (vctrlNext.emul === 5.U(3.W)) -> 1.U,
        (vctrlNext.emul === 6.U(3.W)) -> 1.U,
        (vctrlNext.emul === 7.U(3.W)) -> 1.U,
      ))
    }.otherwise {
      entryNext.uop.uopNum := MuxCase(0.U, Seq(
        (vctrlNext.emul === 0.U(3.W)) -> 1.U,
        (vctrlNext.emul === 1.U(3.W)) -> 2.U,
        (vctrlNext.emul === 2.U(3.W)) -> 4.U,
        (vctrlNext.emul === 3.U(3.W)) -> 8.U,
        (vctrlNext.emul === 5.U(3.W)) -> 1.U,
        (vctrlNext.emul === 6.U(3.W)) -> 1.U,
        (vctrlNext.emul === 7.U(3.W)) -> 1.U,
      ))
    }
    when(vctrl.isLs && vctrl.maskOp){
      entryNext.uop.vCsrInfo.vta := 1.U
      entryNext.uop.vCsrInfo.vl := (io.entry.uop.vCsrInfo.vl +& 7.U) >> 3.U
    }

    entryNext.state := WqState.s_waiting
    entryNext.uop.cf.exceptionVec(illegalInstr) := (isWiden || isNarrow) && vcsr.vlmul === 3.U || vcsr.vill
  }

  io.entryNext := Mux(io.enq.valid, entryEnqNext, entryNext)
  io.updateEnable := io.enq.valid || vtypeWbHit || robEnqHit || io.vmsResp.valid || io.entry.state === WqState.s_updating
}

class VIWaitQueueArray(implicit p: Parameters) extends XSModule with HasVectorParameters{
  private val size = VIWaitQueueWidth
  val io = IO(new Bundle{
    val enq = Vec(VIDecodeWidth, new ViwqWritePort)
    val deq = new ViwqReadPort
    val read = Vec(VIDecodeWidth, new ViwqReadPort)
    val robEnq = Input(Vec(RenameWidth, Valid(new RobPtr)))
    val vmsIdAllocte = Vec(VIDecodeWidth, new VmsIdAlloc)
    val vtypeWb = Flipped(ValidIO(new VtypeWbIO))
    val redirect = Input(Valid(new Redirect))
    val flushMask = Output(UInt(size.W))
  })
  private val array = Reg(Vec(size, new VIWakeQueueEntry))

  io.flushMask := Cat(array.map(_.uop.robIdx.needFlush(io.redirect)).reverse)

  private val updateNetworkSeq = Seq.fill(size)(Module(new VIWakeQueueEntryUpdateNetwork))

  array.zip(updateNetworkSeq).zipWithIndex.foreach({case((a, un), idx) =>
    val enqSel = io.enq.map(e => e.wen && idx.U === e.addr)
    un.io.enq.valid := enqSel.reduce(_|_)
    un.io.enq.bits := Mux1H(enqSel, io.enq.map(_.data))
    un.io.enq.bits.uop.ctrl.fpu := DontCare
    un.io.enq.bits.uop.cf.waitForRobIdx := DontCare
    un.io.enq.bits.uop.cf.trigger := DontCare
    un.io.enq.bits.uop.cf.exceptionVec.foreach(_ := false.B)
    un.io.entry := a
    un.io.robEnq := io.robEnq
    val vmsSel = io.vmsIdAllocte.map(e => e.en && idx.U === e.addr)
    un.io.vmsResp.valid := vmsSel.reduce(_|_)
    un.io.vmsResp.bits := Mux1H(vmsSel, io.vmsIdAllocte.map(_.data))
    un.io.vtypeWb := io.vtypeWb
    when(un.io.updateEnable){
      a := un.io.entryNext
    }
  })

  io.deq.data := array(io.deq.addr)

  io.read.foreach(r => {
    r.data := array(r.addr)
  })
}
