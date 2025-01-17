/*
 * Copyright 2019-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio

import scala.collection.immutable.SortedSet
import scala.util.Try

import zio.console.Console
import zio.duration.Duration
import zio.stream.{ ZSink, ZStream }
import zio.test.environment.{ testEnvironment, TestClock, TestConsole, TestEnvironment, TestRandom, TestSystem }

/**
 * _ZIO Test_ is a featherweight testing library for effectful programs.
 *
 * The library imagines every spec as an ordinary immutable value, providing
 * tremendous potential for composition. Thanks to tight integration with ZIO,
 * specs can use resources (including those requiring disposal), have well-
 * defined linear and parallel semantics, and can benefit from a host of ZIO
 * combinators.
 *
 * {{{
 *  import zio.test._
 *  import zio.clock.nanoTime
 *  import Assertion.isGreaterThan
 *
 *  object MyTest extends DefaultRunnableSpec {
 *    def spec = suite("clock")(
 *      testM("time is non-zero") {
 *        assertM(nanoTime)(isGreaterThan(0))
 *      }
 *    )
 *  }
 * }}}
 */
package object test extends CompileVariants {
  type Annotations = Has[Annotations.Service]
  type Sized       = Has[Sized.Service]
  type TestLogger  = Has[TestLogger.Service]

  type AssertResultM = BoolAlgebraM[Any, Nothing, AssertionValue]
  type AssertResult  = BoolAlgebra[AssertionValue]

  /**
   * A `TestAspectAtLeast[R]` is a `TestAspect` that requires at least an `R` in its environment.
   */
  type TestAspectAtLeastR[R] = TestAspect[Nothing, R, Nothing, Any]

  /**
   * A `TestAspectPoly` is a `TestAspect` that is completely polymorphic,
   * having no requirements on error or environment.
   */
  type TestAspectPoly = TestAspect[Nothing, Any, Nothing, Any]

  type TestResult = BoolAlgebra[FailureDetails]

  /**
   * A `TestReporter[E]` is capable of reporting test results with error type
   * `E`.
   */
  type TestReporter[-E] = (Duration, ExecutedSpec[E]) => URIO[TestLogger, Unit]

  object TestReporter {

    /**
     * TestReporter that does nothing
     */
    val silent: TestReporter[Any] = (_, _) => ZIO.unit
  }

  /**
   * A `ZRTestEnv` is an alias for all ZIO provided [[zio.test.environment.Restorable Restorable]]
   * [[zio.test.environment.TestEnvironment TestEnvironment]] objects
   */
  type ZTestEnv = TestClock with TestConsole with TestRandom with TestSystem

  /**
   * A `ZTest[R, E]` is an effectfully produced test that requires an `R` and
   * may fail with an `E`.
   */
  type ZTest[-R, +E] = ZIO[R, TestFailure[E], TestSuccess]

  object ZTest {

    /**
     * Builds a test with an effectual assertion.
     */
    def apply[R, E](assertion: => ZIO[R, E, TestResult]): ZIO[R, TestFailure[E], TestSuccess] =
      ZIO
        .effectSuspendTotal(assertion)
        .foldCauseM(
          cause => ZIO.fail(TestFailure.Runtime(cause)),
          result =>
            result.failures match {
              case None           => ZIO.succeedNow(TestSuccess.Succeeded(BoolAlgebra.unit))
              case Some(failures) => ZIO.fail(TestFailure.Assertion(failures))
            }
        )
  }

  /**
   * A `ZSpec[R, E]` is the canonical spec for testing ZIO programs. The spec's
   * test type is a ZIO effect that requires an `R` and might fail with an `E`.
   */
  type ZSpec[-R, +E] = Spec[R, TestFailure[E], TestSuccess]

  /**
   * An `ExecutedResult[E] is either a `TestSuccess` or a `TestFailure[E]`.
   */
  type ExecutedResult[+E] = Either[TestFailure[E], TestSuccess]

  /**
   * An `ExecutedSpec` is a spec that has been run to produce test results.
   */
  type ExecutedSpec[+E] = Spec[Any, Nothing, ExecutedResult[E]]

  /**
   * An `Annotated[A]` contains a value of type `A` along with zero or more
   * test annotations.
   */
  type Annotated[+A] = (A, TestAnnotationMap)

  private def traverseResult[A](value: => A, assertResult: AssertResult, assertion: AssertionM[A]): TestResult =
    assertResult.flatMap { fragment =>
      def loop(whole: AssertionValue, failureDetails: FailureDetails): TestResult =
        if (whole.sameAssertion(failureDetails.assertion.head))
          BoolAlgebra.success(failureDetails)
        else {
          val fragment = whole.result
          val result   = if (fragment.isSuccess) fragment else !fragment
          result.flatMap { fragment =>
            loop(fragment, FailureDetails(::(whole, failureDetails.assertion), failureDetails.gen))
          }
        }

      loop(fragment, FailureDetails(::(AssertionValue(assertion, value, assertResult), Nil)))
    }

  /**
   * Checks the assertion holds for the given value.
   */
  def assert[A](value: => A)(assertion: Assertion[A]): TestResult = {
    lazy val tryValue = Try(value)
    traverseResult(tryValue.get, assertion.run(tryValue.get), assertion)
  }

  /**
   * Asserts that the given test was completed.
   */
  val assertCompletes: TestResult =
    assert(true)(Assertion.isTrue)

  /**
   * Checks the assertion holds for the given effectfully-computed value.
   */
  def assertM[R, E, A](effect: ZIO[R, E, A])(assertion: AssertionM[A]): ZIO[R, E, TestResult] =
    for {
      value        <- effect
      assertResult <- assertion.runM(value).run
    } yield traverseResult(value, assertResult, assertion)

  /**
   * Checks the test passes for "sufficient" numbers of samples from the
   * given random variable.
   */
  def check[R, A](rv: Gen[R, A])(test: A => TestResult): URIO[R, TestResult] =
    checkN(200)(rv)(test)

  /**
   * A version of `check` that accepts two random variables.
   */
  def check[R, A, B](rv1: Gen[R, A], rv2: Gen[R, B])(test: (A, B) => TestResult): URIO[R, TestResult] =
    check(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `check` that accepts three random variables.
   */
  def check[R, A, B, C](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => TestResult
  ): URIO[R, TestResult] =
    check(rv1 <*> rv2 <*> rv3)(reassociate(test))

  /**
   * A version of `check` that accepts four random variables.
   */
  def check[R, A, B, C, D](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => TestResult
  ): URIO[R, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4)(reassociate(test))

  /**
   * A version of `check` that accepts five random variables.
   */
  def check[R, A, B, C, D, F](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D], rv5: Gen[R, F])(
    test: (A, B, C, D, F) => TestResult
  ): URIO[R, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(reassociate(test))

  /**
   * A version of `check` that accepts six random variables.
   */
  def check[R, A, B, C, D, F, G](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G]
  )(
    test: (A, B, C, D, F, G) => TestResult
  ): URIO[R, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(reassociate(test))

  /**
   * Checks the effectual test passes for "sufficient" numbers of samples from
   * the given random variable.
   */
  def checkM[R, R1 <: R, E, A](rv: Gen[R, A])(test: A => ZIO[R1, E, TestResult]): ZIO[R1, E, TestResult] =
    checkNM(200)(rv)(test)

  /**
   * A version of `checkM` that accepts two random variables.
   */
  def checkM[R, R1 <: R, E, A, B](rv1: Gen[R, A], rv2: Gen[R, B])(
    test: (A, B) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkM(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `checkM` that accepts three random variables.
   */
  def checkM[R, R1 <: R, E, A, B, C](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkM(rv1 <*> rv2 <*> rv3)(reassociate(test))

  /**
   * A version of `checkM` that accepts four random variables.
   */
  def checkM[R, R1 <: R, E, A, B, C, D](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkM(rv1 <*> rv2 <*> rv3 <*> rv4)(reassociate(test))

  /**
   * A version of `checkM` that accepts five random variables.
   */
  def checkM[R, R1 <: R, E, A, B, C, D, F](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F]
  )(
    test: (A, B, C, D, F) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkM(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(reassociate(test))

  /**
   * A version of `checkM` that accepts six random variables.
   */
  def checkM[R, R1 <: R, E, A, B, C, D, F, G](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G]
  )(
    test: (A, B, C, D, F, G) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkM(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(reassociate(test))

  /**
   * Checks the test passes for all values from the given random variable. This
   * is useful for deterministic `Gen` that comprehensively explore all
   * possibilities in a given domain.
   */
  def checkAll[R, A](rv: Gen[R, A])(test: A => TestResult): URIO[R, TestResult] =
    checkAllM(rv)(test andThen ZIO.succeedNow)

  /**
   * A version of `checkAll` that accepts two random variables.
   */
  def checkAll[R, A, B](rv1: Gen[R, A], rv2: Gen[R, B])(test: (A, B) => TestResult): URIO[R, TestResult] =
    checkAll(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `checkAll` that accepts three random variables.
   */
  def checkAll[R, A, B, C](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => TestResult
  ): URIO[R, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3)(reassociate(test))

  /**
   * A version of `checkAll` that accepts four random variables.
   */
  def checkAll[R, A, B, C, D](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => TestResult
  ): URIO[R, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4)(reassociate(test))

  /**
   * A version of `checkAll` that accepts five random variables.
   */
  def checkAll[R, A, B, C, D, F](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D], rv5: Gen[R, F])(
    test: (A, B, C, D, F) => TestResult
  ): URIO[R, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(reassociate(test))

  /**
   * A version of `checkAll` that accepts six random variables.
   */
  def checkAll[R, A, B, C, D, F, G](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G]
  )(
    test: (A, B, C, D, F, G) => TestResult
  ): URIO[R, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(reassociate(test))

  /**
   * Checks the effectual test passes for all values from the given random
   * variable. This is useful for deterministic `Gen` that comprehensively
   * explore all possibilities in a given domain.
   */
  def checkAllM[R, R1 <: R, E, A](rv: Gen[R, A])(test: A => ZIO[R1, E, TestResult]): ZIO[R1, E, TestResult] =
    checkStream(rv.sample)(test)

  /**
   * A version of `checkAllM` that accepts two random variables.
   */
  def checkAllM[R, R1 <: R, E, A, B](rv1: Gen[R, A], rv2: Gen[R, B])(
    test: (A, B) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkAllM(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `checkAllM` that accepts three random variables.
   */
  def checkAllM[R, R1 <: R, E, A, B, C](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkAllM(rv1 <*> rv2 <*> rv3)(reassociate(test))

  /**
   * A version of `checkAllM` that accepts four random variables.
   */
  def checkAllM[R, R1 <: R, E, A, B, C, D](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkAllM(rv1 <*> rv2 <*> rv3 <*> rv4)(reassociate(test))

  /**
   * A version of `checkAllM` that accepts five random variables.
   */
  def checkAllM[R, R1 <: R, E, A, B, C, D, F](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F]
  )(
    test: (A, B, C, D, F) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkAllM(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(reassociate(test))

  /**
   * A version of `checkAllM` that accepts six random variables.
   */
  def checkAllM[R, R1 <: R, E, A, B, C, D, F, G](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G]
  )(
    test: (A, B, C, D, F, G) => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    checkAllM(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(reassociate(test))

  /**
   * Checks the test passes for the specified number of samples from the given
   * random variable.
   */
  def checkN(n: Int): CheckVariants.CheckN =
    new CheckVariants.CheckN(n)

  /**
   * Checks the effectual test passes for the specified number of samples from
   * the given random variable.
   */
  def checkNM(n: Int): CheckVariants.CheckNM =
    new CheckVariants.CheckNM(n)

  /**
   * A `Runner` that provides a default testable environment.
   */
  val defaultTestRunner: TestRunner[TestEnvironment, Any] =
    TestRunner(TestExecutor.default(testEnvironment))

  /**
   * Creates a failed test result with the specified runtime cause.
   */
  def failed[E](cause: Cause[E]): ZIO[Any, TestFailure[E], Nothing] =
    ZIO.fail(TestFailure.Runtime(cause))

  /**
   * Creates an ignored test result.
   */
  val ignored: UIO[TestSuccess] =
    ZIO.succeedNow(TestSuccess.Ignored)

  /**
   * Passes platform specific information to the specified function, which will
   * use that information to create a test. If the platform is neither ScalaJS
   * nor the JVM, an ignored test result will be returned.
   */
  def platformSpecific[R, E, A](js: => A, jvm: => A)(f: A => ZTest[R, E]): ZTest[R, E] =
    if (TestPlatform.isJS) f(js)
    else if (TestPlatform.isJVM) f(jvm)
    else ignored

  /**
   * Builds a suite containing a number of other specs.
   */
  def suite[R, E, T](label: String)(specs: Spec[R, E, T]*): Spec[R, E, T] =
    Spec.suite(label, ZManaged.succeedNow(specs.toVector), None)

  /**
   * Builds a spec with a single pure test.
   */
  def test(label: String)(assertion: => TestResult): ZSpec[Any, Nothing] =
    testM(label)(ZIO.effectTotal(assertion))

  /**
   * Builds a spec with a single effectful test.
   */
  def testM[R, E](label: String)(assertion: => ZIO[R, E, TestResult]): ZSpec[R, E] =
    Spec.test(label, ZTest(assertion), TestAnnotationMap.empty)

  /**
   * Passes version specific information to the specified function, which will
   * use that information to create a test. If the version is neither Dotty nor
   * Scala 2, an ignored test result will be returned.
   */
  def versionSpecific[R, E, A](dotty: => A, scala2: => A)(f: A => ZTest[R, E]): ZTest[R, E] =
    if (TestVersion.isDotty) f(dotty)
    else if (TestVersion.isScala2) f(scala2)
    else ignored

  /**
   * The `Annotations` trait provides access to an annotation map that tests
   * can add arbitrary annotations to. Each annotation consists of a string
   * identifier, an initial value, and a function for combining two values.
   * Annotations form monoids and you can think of `Annotations` as a more
   * structured logging service or as a super polymorphic version of the writer
   * monad effect.
   */
  object Annotations {

    trait Service extends Serializable {
      def annotate[V](key: TestAnnotation[V], value: V): UIO[Unit]
      def get[V](key: TestAnnotation[V]): UIO[V]
      def withAnnotation[R, E, A](zio: ZIO[R, E, A]): ZIO[R, Annotated[E], Annotated[A]]
      def supervisedFibers: UIO[SortedSet[Fiber.Runtime[Any, Any]]]
    }

    /**
     * Accesses an `Annotations` instance in the environment and appends the
     * specified annotation to the annotation map.
     */
    def annotate[V](key: TestAnnotation[V], value: V): URIO[Annotations, Unit] =
      ZIO.accessM(_.get.annotate(key, value))

    /**
     * Accesses an `Annotations` instance in the environment and retrieves the
     * annotation of the specified type, or its default value if there is none.
     */
    def get[V](key: TestAnnotation[V]): URIO[Annotations, V] =
      ZIO.accessM(_.get.get(key))

    /**
     * Returns a set of all fibers in this test.
     */
    def supervisedFibers: ZIO[Annotations, Nothing, SortedSet[Fiber.Runtime[Any, Any]]] =
      ZIO.accessM(_.get.supervisedFibers)

    /**
     * Constructs a new `Annotations` service.
     */
    val live: Layer[Nothing, Annotations] =
      ZLayer.fromEffect(FiberRef.make(TestAnnotationMap.empty).map { fiberRef =>
        new Annotations.Service {
          def annotate[V](key: TestAnnotation[V], value: V): UIO[Unit] =
            fiberRef.update(_.annotate(key, value))
          def get[V](key: TestAnnotation[V]): UIO[V] =
            fiberRef.get.map(_.get(key))
          def withAnnotation[R, E, A](zio: ZIO[R, E, A]): ZIO[R, Annotated[E], Annotated[A]] =
            fiberRef.locally(TestAnnotationMap.empty) {
              zio.foldM(e => fiberRef.get.map((e, _)).flip, a => fiberRef.get.map((a, _)))
            }
          def supervisedFibers: UIO[SortedSet[Fiber.Runtime[Any, Any]]] =
            ZIO.descriptorWith { descriptor =>
              get(TestAnnotation.fibers).flatMap {
                case Left(_) => ZIO.succeedNow(SortedSet.empty[Fiber.Runtime[Any, Any]])
                case Right(refs) =>
                  ZIO
                    .foreach(refs)(_.get)
                    .map(_.foldLeft(SortedSet.empty[Fiber.Runtime[Any, Any]])(_ ++ _))
                    .map(_.filter(_.id != descriptor.id))
              }
            }
        }
      })

    /**
     * Accesses an `Annotations` instance in the environment and executes the
     * specified effect with an empty annotation map, returning the annotation
     * map along with the result of execution.
     */
    def withAnnotation[R <: Annotations, E, A](zio: ZIO[R, E, A]): ZIO[R, Annotated[E], Annotated[A]] =
      ZIO.accessM(_.get.withAnnotation(zio))
  }

  object Sized {
    trait Service extends Serializable {
      def size: UIO[Int]
      def withSize[R, E, A](size: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A]
    }

    def live(size: Int): Layer[Nothing, Sized] =
      ZLayer.fromEffect(FiberRef.make(size).map { fiberRef =>
        new Sized.Service {
          val size: UIO[Int] =
            fiberRef.get
          def withSize[R, E, A](size: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            fiberRef.locally(size)(zio)
        }
      })

    def size: URIO[Sized, Int] =
      ZIO.accessM[Sized](_.get.size)

    def withSize[R <: Sized, E, A](size: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.accessM[R](_.get.withSize(size)(zio))
  }

  object TestLogger {
    trait Service extends Serializable {
      def logLine(line: String): UIO[Unit]
    }

    def fromConsole: ZLayer[Console, Nothing, TestLogger] =
      ZLayer.fromService { (console: Console.Service) =>
        new Service {
          def logLine(line: String): UIO[Unit] = console.putStrLn(line)
        }
      }

    def logLine(line: String): URIO[TestLogger, Unit] =
      ZIO.accessM(_.get.logLine(line))
  }

  object CheckVariants {

    final class CheckN(private val n: Int) extends AnyVal {
      def apply[R, A](rv: Gen[R, A])(test: A => TestResult): URIO[R, TestResult] =
        checkNM(n)(rv)(test andThen ZIO.succeedNow)
      def apply[R, A, B](rv1: Gen[R, A], rv2: Gen[R, B])(test: (A, B) => TestResult): URIO[R, TestResult] =
        checkN(n)(rv1 <*> rv2)(test.tupled)
      def apply[R, A, B, C](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
        test: (A, B, C) => TestResult
      ): URIO[R, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3)(reassociate(test))
      def apply[R, A, B, C, D](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
        test: (A, B, C, D) => TestResult
      ): URIO[R, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4)(reassociate(test))
      def apply[R, A, B, C, D, F](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D], rv5: Gen[R, F])(
        test: (A, B, C, D, F) => TestResult
      ): URIO[R, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(reassociate(test))
      def apply[R, A, B, C, D, F, G](
        rv1: Gen[R, A],
        rv2: Gen[R, B],
        rv3: Gen[R, C],
        rv4: Gen[R, D],
        rv5: Gen[R, F],
        rv6: Gen[R, G]
      )(
        test: (A, B, C, D, F, G) => TestResult
      ): URIO[R, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(reassociate(test))
    }

    final class CheckNM(private val n: Int) extends AnyVal {
      def apply[R, R1 <: R, E, A](rv: Gen[R, A])(test: A => ZIO[R1, E, TestResult]): ZIO[R1, E, TestResult] =
        checkStream(rv.sample.forever.take(n.toLong), maxShrinks = n * 5)(test)
      def apply[R, R1 <: R, E, A, B](rv1: Gen[R, A], rv2: Gen[R, B])(
        test: (A, B) => ZIO[R1, E, TestResult]
      ): ZIO[R1, E, TestResult] =
        checkNM(n)(rv1 <*> rv2)(test.tupled)
      def apply[R, R1 <: R, E, A, B, C](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
        test: (A, B, C) => ZIO[R1, E, TestResult]
      ): ZIO[R1, E, TestResult] =
        checkNM(n)(rv1 <*> rv2 <*> rv3)(reassociate(test))
      def apply[R, R1 <: R, E, A, B, C, D](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
        test: (A, B, C, D) => ZIO[R1, E, TestResult]
      ): ZIO[R1, E, TestResult] =
        checkNM(n)(rv1 <*> rv2 <*> rv3 <*> rv4)(reassociate(test))
      def apply[R, R1 <: R, E, A, B, C, D, F](
        rv1: Gen[R, A],
        rv2: Gen[R, B],
        rv3: Gen[R, C],
        rv4: Gen[R, D],
        rv5: Gen[R, F]
      )(
        test: (A, B, C, D, F) => ZIO[R1, E, TestResult]
      ): ZIO[R1, E, TestResult] =
        checkNM(n)(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(reassociate(test))
      def apply[R, R1 <: R, E, A, B, C, D, F, G](
        rv1: Gen[R, A],
        rv2: Gen[R, B],
        rv3: Gen[R, C],
        rv4: Gen[R, D],
        rv5: Gen[R, F],
        rv6: Gen[R, G]
      )(
        test: (A, B, C, D, F, G) => ZIO[R1, E, TestResult]
      ): ZIO[R1, E, TestResult] =
        checkNM(n)(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(reassociate(test))
    }
  }

  private def checkStream[R, R1 <: R, E, A](stream: ZStream[R, Nothing, Sample[R, A]], maxShrinks: Int = 1000)(
    test: A => ZIO[R1, E, TestResult]
  ): ZIO[R1, E, TestResult] =
    stream.zipWithIndex.mapM {
      case (initial, index) =>
        initial.foreach(input =>
          test(input).traced
            .map(_.map(_.copy(gen = Some(GenFailureDetails(initial.value, input, index)))))
            .either
        )
    }.dropWhile(!_.value.fold(_ => true, _.isFailure)) // Drop until we get to a failure
      .take(1)                                          // Get the first failure
      .flatMap(_.shrinkSearch(_.fold(_ => true, _.isFailure)).take(maxShrinks.toLong))
      .run(ZSink.collectAll[Either[E, TestResult]]) // Collect all the shrunken values
      .flatMap { shrinks =>
        // Get the "last" failure, the smallest according to the shrinker:
        shrinks
          .filter(_.fold(_ => true, _.isFailure))
          .lastOption
          .fold[ZIO[R, E, TestResult]](
            ZIO.succeedNow {
              BoolAlgebra.success {
                FailureDetails(
                  ::(AssertionValue(Assertion.anything, (), Assertion.anything.run(())), Nil)
                )
              }
            }
          )(ZIO.fromEither(_))
      }
      .untraced

  private def reassociate[A, B, C, D](fn: (A, B, C) => D): (((A, B), C)) => D = {
    case ((a, b), c) => fn(a, b, c)
  }

  private def reassociate[A, B, C, D, E](fn: (A, B, C, D) => E): ((((A, B), C), D)) => E = {
    case (((a, b), c), d) => fn(a, b, c, d)
  }

  private def reassociate[A, B, C, D, E, F](fn: (A, B, C, D, E) => F): (((((A, B), C), D), E)) => F = {
    case ((((a, b), c), d), e) => fn(a, b, c, d, e)
  }

  private def reassociate[A, B, C, D, E, F, G](fn: (A, B, C, D, E, F) => G): ((((((A, B), C), D), E), F)) => G = {
    case (((((a, b), c), d), e), f) => fn(a, b, c, d, e, f)
  }
}
