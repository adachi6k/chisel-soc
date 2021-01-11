package core_complex

import chisel3._
import cpu.{CpuDebugMonitor, RVConfig}
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._


class core_complex[Conf <: RVConfig] (conf: Conf, numCores: Int, ramBeatBytes: Int, txns: Int)(implicit p: Parameters) extends LazyModule {
  val loader = LazyModule(new loader("loader"))
  // val ifu    = LazyModule(new ifu("ifu"))
  val core   = Seq.tabulate(numCores) { case i => LazyModule(new CoreTop(conf, i, "core" + i.toString)) }
  val xbar   = LazyModule(new TLXbar)
  val memory = LazyModule(new TLRAM(AddressSet(0x80000000L, 0x0ffff), beatBytes = ramBeatBytes))
//  val axiram = LazyModule(new AXI4RAM(AddressSet(0x80010000L, 0xffff), beatBytes = ramBeatBytes))
  val tl2axi = LazyModule(new TLToAXI4)

//  val m_tl_node = LazyModule(new TLIdentityNode)  
//  val m_tl_node = TLIdentityNode()

  val m_node = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
          name    = "Ext Master",
          id      = IdRange(0, 1))))))
//          id      = IdRange(0, 256))))))

//  (xbar.node
//    := TLBuffer()
//    := TLWidthWidget(4)
//    := AXI4ToTL()
//    := AXI4UserYanker(capMaxFlight=Some(0))
//    := AXI4Fragmenter()
//    := AXI4IdIndexer(idBits=0)
//    := AXI4Buffer()
//    := m_node)
  
  val device = new MemoryDevice
  val s_node = AXI4SlaveNode(
    //  val node = AXI4BlindOutputNode(
    Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address     = Seq(AddressSet(0x80010000L, 0xffff)),
        resources   = device.reg,
        regionType  = RegionType.UNCACHED,
        executable  = true,
        supportsWrite = TransferSizes(1,4),
        supportsRead  = TransferSizes(1,4),
        interleavedId = Some(0)
      )),
//      beatBytes = ramBeatBytes)
      beatBytes = 4)
    ))


  xbar.node := loader.node
  core.foreach { case (core) => {
    xbar.node := TLDelayer(0.1) := core.inst_node
    xbar.node := TLDelayer(0.1) := core.data_node
  }
  }
  memory.node := xbar.node
  tl2axi.node := xbar.node
  //axiram.node := tl2axi.node
  this.s_node := tl2axi.node

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val req   = Input(Bool())
      val addr  = Input(UInt(32.W))
      val data  = Input(UInt(32.W))
      val ready = Output(Bool())

      val cpu_dbg = Output(Vec(numCores, new CpuDebugMonitor(conf)))

      val arvalid = Output(Bool())
    //  val arready = Input(Bool())
      val araddr = Output(UInt(32.W))
    //  val ardata = Input(UInt(32.W))
      val awvalid = Output(Bool())
    //  val awready = Input(Bool())
      val awaddr = Output(UInt(32.W))

      val wvalid  = Output(Bool())
      val wdata   = Output(UInt(32.W))



    })

//    val (out, edge) = node.out(0)
    val (out, _) = s_node.in(0)

    loader.module.io.req  := io.req && (io.addr(31,16) =/= 0x2000.U)
    loader.module.io.addr := io.addr
    loader.module.io.data := io.data
    io.ready := loader.module.io.ready

    // CPU Core Contorl
    val cpu_run = Seq.fill(numCores) { RegInit(false.B) }
    cpu_run.foreach { case(cpu_run) => cpu_run := Mux(io.req && io.req && (io.addr === 0x20000000.U), io.data(0), cpu_run) }

    core.zip(cpu_run).foreach { case (core, cpu_run) => core.module.io.run := cpu_run }

    io.cpu_dbg := core.map { case(core) => core.module.io.dbg_monitor }


    io.arvalid := out.ar.valid
    io.araddr  := out.ar.bits.addr

    io.awvalid := out.aw.valid
    io.awaddr  := out.aw.bits.addr

    io.wvalid  := out.w.valid
    io.wdata   := out.w.bits.data

  }
}
