// Copyright (c) 2018 Norwegian University of Science and Technology (NTNU)
//
// BSD v3 License
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// * Redistributions of source code must retain the above copyright notice, this
//   list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright notice,
//   this list of conditions and the following disclaimer in the documentation
//   and/or other materials provided with the distribution.
//
// * Neither the name of [project] nor the names of its
//   contributors may be used to endorse or promote products derived from
//   this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

import Chisel._
import bismo._
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import RosettaTestHelpers._

class TestDotProductUnit extends JUnitSuite {
  @Test def BasicDotProdTest {
    // Tester-derived class to give stimulus and observe the outputs for the
    // Module to be tested
    class DotProductUnitTester(c: DotProductUnit) extends Tester(c) {
      val r = scala.util.Random
      // number of re-runs for each test
      val num_seqs = 100
      // number of bits in each operand
      val pc_len = c.p.pcParams.numInputBits
      // max shift steps for random input
      val max_shift = c.p.maxShiftSteps
      // latency from inputs changed to accumulate update
      val latency = c.p.getLatency()

      // helper fuctions for more concise tests
      // wait up to <latency> cycles with valid=0 to create pipeline bubbles
      def randomNoValidWait(max: Int = latency) = {
        val numNoValidCycles = r.nextInt(max+1)
        poke(c.io.in.valid, 0)
        step(numNoValidCycles)
      }

      def clearAcc(waitUntilCleared: Boolean = false) = {
        // note that clearing happens right before the regular binary dot
        // product result is added -- so also set inputs explicitly to zero.
        // normally the testbench would just set the clear_acc of the very
        // first test vector to 1 alongside the regular data, and to 0 after,
        // but this also works.
        poke(c.io.in.bits.a, 0)
        poke(c.io.in.bits.b, 0)
        poke(c.io.in.bits.clear_acc, 1)
        poke(c.io.in.valid, 1)
        step(1)
        poke(c.io.in.bits.clear_acc, 0)
        poke(c.io.in.valid, 0)
        if(waitUntilCleared) {
          step(latency)
        }
      }
      // clear accumulators, wait until clear is visible
      clearAcc(waitUntilCleared = true)
      expect(c.io.out, 0)
      step(10)
      // accumulator should retain value without any valid input
      expect(c.io.out, 0)

      // TODO combine all into single test with controllable param. ranges
      // test 1: binary, unsigned vectors that fit into popCountWidth
      for(i <- 1 to num_seqs) {
        // clear accumulator between runs
        clearAcc()
        // produce random binary test vectors and golden result
        val seqA = RosettaTestHelpers.randomIntVector(pc_len, 1, false)
        val seqB = RosettaTestHelpers.randomIntVector(pc_len, 1, false)
        val golden = RosettaTestHelpers.dotProduct(seqA, seqB)
        poke(c.io.in.bits.a, scala.math.BigInt.apply(seqA.mkString, 2))
        poke(c.io.in.bits.b, scala.math.BigInt.apply(seqB.mkString, 2))
        poke(c.io.in.bits.shiftAmount, 0)
        poke(c.io.in.bits.negate, 0)
        poke(c.io.in.valid, 1)
        step(1)
        // remove valid input in next cycle
        poke(c.io.in.valid, 0)
        step(latency-1)
        expect(c.io.out, golden)
      }

      // test 2: binary, unsigned vectors that do not fit into popCountWidth
      for(i <- 1 to num_seqs) {
        // produce seq_len different popcount vectors, each pc_len bits
        // min length of seq_len is 1, no max len (barring accumulator overflow)
        // but set to 16 here for quicker testing
        val seq_len = 1 + r.nextInt(17)
        val bit_len = pc_len * seq_len
        // produce random binary test vectors and golden result
        val seqA = RosettaTestHelpers.randomIntVector(bit_len, 1, false)
        val seqB = RosettaTestHelpers.randomIntVector(bit_len, 1, false)
        val golden = RosettaTestHelpers.dotProduct(seqA, seqB)
        // clear accumulator between runs
        clearAcc()
        for(j <- 0 to seq_len-1) {
          // push in next slice of bit vector
          val curA = seqA.slice(j*pc_len, (j+1)*pc_len)
          val curB = seqB.slice(j*pc_len, (j+1)*pc_len)
          poke(c.io.in.bits.a, scala.math.BigInt.apply(curA.mkString, 2))
          poke(c.io.in.bits.b, scala.math.BigInt.apply(curB.mkString, 2))
          poke(c.io.in.bits.shiftAmount, 0)
          poke(c.io.in.bits.negate, 0)
          poke(c.io.in.valid, 1)
          step(1)
          // emulate random pipeline bubbles
          randomNoValidWait()
        }
        // remove valid input in next cycle
        poke(c.io.in.valid, 0)
        // wait until all inputs are processed
        step(latency-1)
        expect(c.io.out, golden)
      }

      // test 3: multibit unsigned and signed integers
      for(i <- 1 to num_seqs) {
        // produce seq_len different popcount vectors, each pc_len bits
        // min length of seq_len is 1, no max len (barring accumulator overflow)
        // but set to 16 here for quicker testing
        val seq_len = 1 + r.nextInt(17)
        val bit_len = pc_len * seq_len
        // precision in bits, each between 1 and max_shift/2 bits
        // such that their sum won't be greater than max_shift
        val precA = 1 + r.nextInt(max_shift/2)
        val precB = 1 + r.nextInt(max_shift/2)
        assert(precA + precB <= max_shift)
        // produce random binary test vectors and golden result
        val negA = r.nextBoolean
        val negB = r.nextBoolean
        val seqA = RosettaTestHelpers.randomIntVector(bit_len, precA, negA)
        val seqB = RosettaTestHelpers.randomIntVector(bit_len, precB, negB)
        val golden = RosettaTestHelpers.dotProduct(seqA, seqB)
        // convert test vectors to bit serial form
        val seqA_bs = RosettaTestHelpers.intVectorToBitSerial(seqA, precA)
        val seqB_bs = RosettaTestHelpers.intVectorToBitSerial(seqB, precB)
        // clear accumulator between runs
        clearAcc()
        var golden_acc: Int = 0
        // iterate over each combination of bit positions for bit serial
        for(bitA <- 0 to precA-1) {
          val negbitA = negA & (bitA == precA-1)
          for(bitB <- 0 to precB-1) {
            val negbitB = negB & (bitB == precB-1)
            val doNeg = if(negbitA ^ negbitB) 1 else 0
            // shift is equal to sum of current bit positions
            poke(c.io.in.bits.shiftAmount, bitA+bitB)
            for(j <- 0 to seq_len-1) {
              // push in next slice of bit vector from correct bit position
              val curA = seqA_bs(bitA).slice(j*pc_len, (j+1)*pc_len)
              val curB = seqB_bs(bitB).slice(j*pc_len, (j+1)*pc_len)
              golden_acc += RosettaTestHelpers.dotProduct(curA, curB) << (bitA+bitB)
              poke(c.io.in.bits.a, scala.math.BigInt.apply(curA.mkString, 2))
              poke(c.io.in.bits.b, scala.math.BigInt.apply(curB.mkString, 2))
              poke(c.io.in.bits.negate, doNeg)
              poke(c.io.in.valid, 1)
              step(1)
              // emulate random pipeline bubbles
              randomNoValidWait()
            }
          }
        }
        // remove valid input in next cycle
        poke(c.io.in.valid, 0)
        // wait until all inputs are processed
        step(latency-1)
        expect(c.io.out, golden)
      }
    }

    // Chisel arguments to pass to chiselMainTest
    def testArgs = RosettaTestHelpers.stdArgs

    for{
      popc_extra_regs <- 0 to 1
      dpu_extra_regs <- 0 to 1
      popc_width <- for(b <- 4 to 10) yield 1 << b
    } {
      // function that instantiates the Module to be tested
      val pcp = new PopCountUnitParams(popc_width, extraPipelineRegs = popc_extra_regs)
      val p = new DotProductUnitParams(pcp, 32, 16)
      def testModuleInstFxn = () => { Module(new DotProductUnit(p)) }
      // function that instantiates the Tester to test the Module
      def testTesterInstFxn = (c: DotProductUnit) => new DotProductUnitTester(c)

      // actually run the test
      chiselMainTest(
        testArgs,
        testModuleInstFxn
      ) {
        testTesterInstFxn
      }
    }
  }
}
