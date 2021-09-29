package zio.stream.experimental

import zio._
import zio.stream.compression.TestData._
import zio.test.Assertion._
import zio.test._

import java.util.zip.Deflater

object DeflateSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] =
    suite("DeflateSpec")(
      test("JDK inflates what was deflated")(
        check(Gen.listOfBounded(0, `1K`)(Gen.byte).zip(Gen.int(1, `1K`)).zip(Gen.int(1, `1K`))) {
          case (input, n, bufferSize) =>
            assertM(for {
              (deflated, _) <-
                (ZStream.fromIterable(input).rechunk(n).channel >>> Deflate.makeDeflater(bufferSize)).runCollect
              inflated <- jdkInflate(deflated.flatten, noWrap = false)
            } yield inflated)(equalTo(input))
        }
      ),
      test("deflate empty bytes, small buffer")(
        assertM(
          (ZStream.fromIterable(List.empty).rechunk(1).channel >>> Deflate
            .makeDeflater(100, false)).runCollect
            .map(_._1.flatten.toList)
        )(equalTo(jdkDeflate(Array.empty, new Deflater(-1, false)).toList))
      ),
      test("deflates same as JDK")(
        assertM(
          (ZStream.fromIterable(longText).rechunk(128).channel >>> Deflate.makeDeflater(256, false)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, false))))
        )
      ),
      test("deflates same as JDK, nowrap")(
        assertM(
          (ZStream.fromIterable(longText).rechunk(128).channel >>> Deflate.makeDeflater(256, true)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, true))))
        )
      ),
      test("deflates same as JDK, small buffer")(
        assertM(
          (ZStream.fromIterable(longText).rechunk(64).channel >>> Deflate.makeDeflater(1, false)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, false))))
        )
      ),
      test("deflates same as JDK, nowrap, small buffer ")(
        assertM(
          (ZStream.fromIterable(longText).rechunk(64).channel >>> Deflate.makeDeflater(1, true)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, true))))
        )
      )
    )

}
