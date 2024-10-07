package ox.flow

import ox.CancellableFork
import ox.Fork
import ox.Ox
import ox.OxUnsupervised
import ox.channels.Channel
import ox.channels.ChannelClosed
import ox.channels.Default
import ox.channels.Sink
import ox.channels.Source
import ox.channels.BufferCapacity
import ox.channels.forkPropagate
import ox.channels.selectOrClosed
import ox.discard
import ox.forkCancellable
import ox.forkUnsupervised
import ox.repeatWhile
import ox.sleep
import ox.supervised
import ox.tapException
import ox.unsupervised

import java.util.concurrent.Semaphore
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration
import ox.forkUser

class FlowOps[+T]:
  outer: Flow[T] =>

  /** When run, the current pipeline is run asynchornously in the background, emitting elements to a buffer. The elements of the buffer are
    * then emitted by the returned flow.
    *
    * The size of the buffer is determined by the [[BufferCapacity]] that is in scope.
    *
    * Any exceptions are propagated by the returned flow.
    */
  def async()(using BufferCapacity): Flow[T] = Flow.usingEmitInline: emit =>
    val ch = BufferCapacity.newChannel[T]
    unsupervised:
      runLastToChannelAsync(ch)
      FlowEmit.channelToEmit(ch, emit)

  //

  /** Applies the given mapping function `f` to each element emitted by this flow. The returned flow then emits the results.
    *
    * @param f
    *   The mapping function.
    */
  def map[U](f: T => U): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => emit(f(t))))

  /** Applies the given mapping function `f` to each element emitted by this flow, in sequence. The given [[FlowSink]] can be used to emit
    * an arbirary number of elements.
    *
    * @param f
    *   The mapping function.
    */
  def mapUsingEmit[U](f: T => FlowEmit[U] => Unit): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => f(t)(emit)))

  /** Emits only those elements emitted by this flow, for which `f` returns `true`.
    *
    * @param f
    *   The filtering function.
    */
  def filter(f: T => Boolean): Flow[T] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => if f(t) then emit.apply(t)))

  /** Applies the given mapping function `f` to each element emitted by this flow, for which the function is defined, and emits the result.
    * If `f` is not defined at an element, the element will be skipped.
    *
    * @param f
    *   The mapping function.
    */
  def collect[U](f: PartialFunction[T, U]): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => if f.isDefinedAt(t) then emit.apply(f(t))))

  /** Applies the given effectful function `f` to each element emitted by this flow. The returned flow emits the elements unchanged. If `f`
    * throws an exceptions, the flow fails and propagates the exception.
    */
  def tap(f: T => Unit): Flow[T] = map(t =>
    f(t); t
  )

  /** Intersperses elements emitted by this flow with `inject` elements. The `inject` element is emitted between each pair of elements. */
  def intersperse[U >: T](inject: U): Flow[U] = intersperse(None, inject, None)

  /** Intersperses elements emitted by this flow with `inject` elements. The `start` element is emitted at the beginning; `end` is emitted
    * after the current flow emits the last element.
    *
    * @param start
    *   An element to be emitted at the beginning.
    * @param inject
    *   An element to be injected between the flow elements.
    * @param end
    *   An element to be emitted at the end.
    */
  def intersperse[U >: T](start: U, inject: U, end: U): Flow[U] = intersperse(Some(start), inject, Some(end))

  private def intersperse[U >: T](start: Option[U], inject: U, end: Option[U]): Flow[U] = Flow.usingEmitInline: emit =>
    start.foreach(emit.apply)
    var firstEmitted = false
    last.run(
      FlowEmit.fromInline: u =>
        if firstEmitted then emit(inject)
        emit(u)
        firstEmitted = true
    )
    end.foreach(emit.apply)

  /** Applies the given mapping function `f` to each element emitted by this flow. At most `parallelism` invocations of `f` are run in
    * parallel.
    *
    * The mapped results are emitted in the same order, in which inputs are received. In other words, ordering is preserved.
    *
    * @param parallelism
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element from the flow.
    * @param f
    *   The mapping function.
    */
  def mapPar[U](parallelism: Int)(f: T => U)(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    val s = new Semaphore(parallelism)
    val inProgress = Channel.withCapacity[Fork[Option[U]]](parallelism)
    val results = BufferCapacity.newChannel[U]

    def forkMapping(t: T)(using OxUnsupervised): Fork[Option[U]] =
      forkUnsupervised:
        try
          val u = f(t)
          s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
          Some(u)
        catch
          case t: Throwable => // same as in `forkPropagate`, catching all exceptions
            results.errorOrClosed(t)
            None

    // creating a nested scope, so that in case of errors, we can clean up any mapping forks in a "local" fashion,
    // that is without closing the main scope; any error management must be done in the forks, as the scope is
    // unsupervised
    unsupervised:
      // a fork which runs the `last` pipeline, and for each emitted element creates a fork
      // notifying only the `results` channels, as it will cause the scope to end, and any other forks to be
      // interrupted, including the inProgress-fork, which might be waiting on a join()
      forkPropagate(results):
        last.run(
          FlowEmit.fromInline: t =>
            s.acquire()
            inProgress.sendOrClosed(forkMapping(t)).discard
        )
        inProgress.doneOrClosed().discard

      // a fork in which we wait for the created forks to finish (in sequence), and forward the mapped values to `results`
      // this extra step is needed so that if there's an error in any of the mapping forks, it's discovered as quickly as
      // possible in the main body
      forkUnsupervised:
        repeatWhile:
          inProgress.receiveOrClosed() match
            // in the fork's result is a `None`, the error is already propagated to the `results` channel
            case f: Fork[Option[U]] @unchecked => f.join().map(results.sendOrClosed).isDefined
            case ChannelClosed.Done            => results.done(); false
            case ChannelClosed.Error(e)        => throw new IllegalStateException("inProgress should never be closed with an error", e)

      // in the main body, we call the `emit` methods using the (sequentially received) results; when an error occurs,
      // the scope ends, interrupting any forks that are still running
      FlowEmit.channelToEmit(results, emit)
  end mapPar

  /** Applies the given mapping function `f` to each element emitted by this flow. At most `parallelism` invocations of `f` are run in
    * parallel.
    *
    * The mapped results **might** be emitted out-of-order, depending on the order in which the mapping function completes.
    *
    * @param parallelism
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element from the flow.
    * @param f
    *   The mapping function.
    */
  def mapParUnordered[U](parallelism: Int)(f: T => U)(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    val results = BufferCapacity.newChannel[U]
    val s = new Semaphore(parallelism)
    unsupervised: // the outer scope, used for the fork which runs the `last` pipeline
      forkPropagate(results):
        supervised: // the inner scope, in which user forks are created, and which is used to wait for all to complete when done
          last
            .run(
              FlowEmit.fromInline: t =>
                s.acquire()
                forkUser:
                  try
                    results.sendOrClosed(f(t))
                    s.release()
                  catch case t: Throwable => results.errorOrClosed(t).discard
                .discard
            )
            // if run() throws, we want this to become the "main" error, instead of an InterruptedException
            .tapException(results.errorOrClosed(_).discard)
        results.doneOrClosed().discard

      FlowEmit.channelToEmit(results, emit)

  private val abortTake = new Exception("abort take")

  /** Takes the first `n` elements from this flow and emits them. If the flow completes before emitting `n` elements, the returned flow
    * completes as well.
    */
  def take(n: Int): Flow[T] = Flow.usingEmitInline: emit =>
    var taken = 0
    try
      last.run(
        FlowEmit.fromInline: t =>
          if taken < n then
            emit(t)
            taken += 1

          if taken == n then throw abortTake
      )
    catch case `abortTake` => () // done

  /** Transform the flow so that it emits elements as long as predicate `f` is satisfied (returns `true`). If `includeFirstFailing` is
    * `true`, the flow will additionally emit the first element that failed the predicate. After that, the flow will complete as done.
    *
    * @param f
    *   A predicate function called on incoming elements.
    * @param includeFirstFailing
    *   Whether the flow should also emit the first element that failed the predicate (`false` by default).
    */
  def takeWhile(f: T => Boolean, includeFirstFailing: Boolean = false): Flow[T] = Flow.usingEmitInline: emit =>
    try
      last.run(
        FlowEmit.fromInline: t =>
          if f(t) then emit(t)
          else
            if includeFirstFailing then emit.apply(t)
            throw abortTake
      )
    catch case `abortTake` => () // done

  /** Drops `n` elements from this flow and emits subsequent elements.
    *
    * @param n
    *   Number of elements to be dropped.
    */
  def drop(n: Int): Flow[T] = Flow.usingEmitInline: emit =>
    var dropped = 0
    last.run(
      FlowEmit.fromInline: t =>
        if dropped < n then dropped += 1
        else emit(t)
    )

  /** Merges two flows into a single flow. The resulting flow emits elements from both flows in the order they are emitted. If one of the
    * flows completes before the other, the remaining elements from the other flow are emitted by the returned flow.
    *
    * Both flows are run concurrently in the background. The size of the buffers is determined by the [[BufferCapacity]] that is in scope.
    *
    * @param other
    *   The flow to be merged with this flow.
    */
  def merge[U >: T](other: Flow[U])(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    unsupervised:
      val c1 = outer.runToChannel()
      val c2 = other.runToChannel()

      repeatWhile:
        selectOrClosed(c1, c2) match
          case ChannelClosed.Done =>
            if c1.isClosedForReceive then FlowEmit.channelToEmit(c2, emit) else FlowEmit.channelToEmit(c1, emit)
            false
          case ChannelClosed.Error(r) => throw r
          case r: U @unchecked        => emit(r); true

  /** Pipes the elements of child flows into the output source. If the parent source or any of the child sources emit an error, the pulling
    * stops and the output source emits the error.
    *
    * Runs all flows concurrently in the background. The size of the buffers is determined by the [[BufferCapacity]] that is in scope.
    */
  def flatten[U](using T <:< Flow[U])(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    case class Nested(child: Flow[U])

    unsupervised:
      val childStream = outer.map(Nested(_)).runToChannel()
      var pool = List[Source[Nested] | Source[U]](childStream)

      repeatWhile:
        selectOrClosed(pool) match
          case ChannelClosed.Done =>
            // TODO: optimization idea: find a way to remove the specific channel that signalled to be Done
            pool = pool.filterNot(_.isClosedForReceiveDetail.contains(ChannelClosed.Done))
            if pool.isEmpty then false
            else true
          case ChannelClosed.Error(e) => throw e
          case Nested(t) =>
            pool = t.runToChannel() :: pool
            true
          case r: U @unchecked => emit(r); true

  /** Concatenates this flow with the `other` flow. The resulting flow will emit elements from this flow first, and then from the `other`
    * flow.
    *
    * @param other
    *   The flow to be appended to this flow.
    */
  def concat[U >: T](other: Flow[U]): Flow[U] = Flow.concat(List(this, other))

  /** Prepends `other` flow to this source. The resulting flow will emit elements from `other` flow first, and then from the this flow.
    *
    * @param other
    *   The flow to be prepended to this flow.
    */
  def prepend[U >: T](other: Flow[U]): Flow[U] = Flow.concat(List(other, this))

  /** Combines elements from this and the other flow into tuples. Completion of either flow completes the returned flow as well. The flows
    * are run concurrently.
    * @see
    *   zipAll
    */
  def zip[U](other: Flow[U]): Flow[(T, U)] = Flow.usingEmitInline: emit =>
    unsupervised:
      val s1 = outer.runToChannel()
      val s2 = other.runToChannel()

      repeatWhile:
        s1.receiveOrClosed() match
          case ChannelClosed.Done     => false
          case ChannelClosed.Error(r) => throw r
          case t: T @unchecked =>
            s2.receiveOrClosed() match
              case ChannelClosed.Done     => false
              case ChannelClosed.Error(r) => throw r
              case u: U @unchecked        => emit((t, u)); true

  /** Combines elements from this and the other flow into tuples, handling early completion of either flow with defaults. The flows are run
    * concurrently.
    *
    * @param other
    *   A flow of elements to be combined with.
    * @param thisDefault
    *   A default element to be used in the result tuple when the other flow is longer.
    * @param otherDefault
    *   A default element to be used in the result tuple when the current flow is longer.
    */
  def zipAll[U >: T, V](other: Flow[V], thisDefault: U, otherDefault: V): Flow[(U, V)] = Flow.usingEmitInline: emit =>
    unsupervised:
      val s1 = outer.runToChannel()
      val s2 = other.runToChannel()

      def receiveFromOther(thisElement: U, otherDoneHandler: () => Boolean): Boolean =
        s2.receiveOrClosed() match
          case ChannelClosed.Done     => otherDoneHandler()
          case ChannelClosed.Error(r) => throw r
          case v: V @unchecked        => emit((thisElement, v)); true

      repeatWhile:
        s1.receiveOrClosed() match
          case ChannelClosed.Done =>
            receiveFromOther(
              thisDefault,
              () => false
            )
          case ChannelClosed.Error(r) => throw r
          case t: T @unchecked =>
            receiveFromOther(
              t,
              () =>
                emit((t, otherDefault)); true
            )

  /** Emits a given number of elements (determined byc `segmentSize`) from this flow to the returned flow, then emits the same number of
    * elements from the `other` flow and repeats. The order of elements in both flows is preserved.
    *
    * If one of the flows is done before the other, the behavior depends on the `eagerCancel` flag. When set to `true`, the returned flow is
    * completed immediately, otherwise the remaining elements from the other flow are emitted by the returned flow.
    *
    * Both flows are run concurrently and asynchronously.
    *
    * @param other
    *   The flow whose elements will be interleaved with the elements of this flow.
    * @param segmentSize
    *   The number of elements sent from each flow before switching to the other one. Default is 1.
    * @param eagerComplete
    *   If `true`, the returned flow is completed as soon as either of the flow completes. If `false`, the remaining elements of the
    *   non-completed flow are sent downstream.
    */
  def interleave[U >: T](other: Flow[U], segmentSize: Int = 1, eagerComplete: Boolean = false)(using BufferCapacity): Flow[U] =
    Flow.interleaveAll(List(this, other), segmentSize, eagerComplete)

  /** Applies the given mapping function `f`, using additional state, to each element emitted by this flow. The results are emitted by the
    * returned flow. Optionally the returned flow emits an additional element, possibly based on the final state, once this flow is done.
    *
    * The `initializeState` function is called once when `statefulMap` is called.
    *
    * The `onComplete` function is called once when this flow is done. If it returns a non-empty value, the value will be emitted by the
    * flow, while an empty value will be ignored.
    *
    * @param initializeState
    *   A function that initializes the state.
    * @param f
    *   A function that transforms the element from this flow and the state into a pair of the next state and the result which is emitted by
    *   the returned flow.
    * @param onComplete
    *   A function that transforms the final state into an optional element emitted by the returned flow. By default the final state is
    *   ignored.
    */
  def mapStateful[S, U](initializeState: () => S)(f: (S, T) => (S, U), onComplete: S => Option[U] = (_: S) => None): Flow[U] =
    def resultToSome(s: S, t: T) =
      val (newState, result) = f(s, t)
      (newState, Some(result))

    mapStatefulConcat(initializeState)(resultToSome, onComplete)
  end mapStateful

  /** Applies the given mapping function `f`, using additional state, to each element emitted by this flow. The returned flow emits the
    * results one by one. Optionally the returned flow emits an additional element, possibly based on the final state, once this flow is
    * done.
    *
    * The `initializeState` function is called once when `statefulMap` is called.
    *
    * The `onComplete` function is called once when this flow is done. If it returns a non-empty value, the value will be emitted by the
    * returned flow, while an empty value will be ignored.
    *
    * @param initializeState
    *   A function that initializes the state.
    * @param f
    *   A function that transforms the element from this flow and the state into a pair of the next state and a
    *   [[scala.collection.IterableOnce]] of results which are emitted one by one by the returned flow. If the result of `f` is empty,
    *   nothing is emitted by the returned flow.
    * @param onComplete
    *   A function that transforms the final state into an optional element emitted by the returned flow. By default the final state is
    *   ignored.
    */
  def mapStatefulConcat[S, U](
      initializeState: () => S
  )(f: (S, T) => (S, IterableOnce[U]), onComplete: S => Option[U] = (_: S) => None): Flow[U] = Flow.usingEmitInline: emit =>
    var state = initializeState()
    last.run(
      FlowEmit.fromInline: t =>
        val (nextState, result) = f(state, t)
        state = nextState
        result.iterator.foreach(emit.apply)
    )
    onComplete(state).foreach(emit.apply)
  end mapStatefulConcat

  /** Applies the given mapping function `f`, to each element emitted by this source, transforming it into an [[IterableOnce]] of results,
    * then the returned flow emits the results one by one. Can be used to unfold incoming sequences of elements into single elements.
    *
    * @param f
    *   A function that transforms the element from this flow into an [[IterableOnce]] of results which are emitted one by one by the
    *   returned flow. If the result of `f` is empty, nothing is emitted by the returned channel.
    */
  def mapConcat[U](f: T => IterableOnce[U]): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(
      FlowEmit.fromInline: t =>
        f(t).iterator.foreach(emit.apply)
    )

  /** Emits elements limiting the throughput to specific number of elements (evenly spaced) per time unit. Note that the element's
    * emission-time time is included in the resulting throughput. For instance having `throttle(1, 1.second)` and emission of the next
    * element taking `Xms` means that resulting flow will emit elements every `1s + Xms` time. Throttling is not applied to the empty
    * source.
    *
    * @param elements
    *   Number of elements to be emitted. Must be greater than 0.
    * @param per
    *   Per time unit. Must be greater or equal to 1 ms.
    * @return
    *   A flow that emits at most `elements` `per` time unit.
    */
  def throttle(elements: Int, per: FiniteDuration): Flow[T] =
    require(elements > 0, "elements must be > 0")
    require(per.toMillis > 0, "per time must be >= 1 ms")
    val emitEveryMillis = (per.toMillis / elements).millis
    tap(t => sleep(emitEveryMillis))
  end throttle

  /** If this flow has no elements then elements from an `alternative` flow are emitted by the returned flow. If this flow is failed then
    * the returned flow is failed as well.
    *
    * @param alternative
    *   An alternative flow to be used when this flow is empty.
    */
  def orElse[U >: T](alternative: Flow[U]): Flow[U] = Flow.usingEmitInline: emit =>
    var receivedAtLeastOneElement = false
    last.run(
      FlowEmit.fromInline: t =>
        emit(t)
        receivedAtLeastOneElement = true
    )
    if !receivedAtLeastOneElement then alternative.runToEmit(emit)
  end orElse

  /** Chunks up the elements into groups of the specified size. The last group may be smaller due to the flow being complete.
    *
    * @param n
    *   The number of elements in a group.
    */
  def grouped(n: Int): Flow[Seq[T]] = groupedWeighted(n)(_ => 1)

  /** Chunks up the elements into groups that have a cumulative weight greater or equal to the `minWeight`. The last group may be smaller
    * due to the flow being complete.
    *
    * @param minWeight
    *   The minimum cumulative weight of elements in a group.
    * @param costFn
    *   The function that calculates the weight of an element.
    */
  def groupedWeighted(minWeight: Long)(costFn: T => Long): Flow[Seq[T]] =
    require(minWeight > 0, "minWeight must be > 0")

    Flow.usingEmitInline: emit =>
      var buffer = Vector.empty[T]
      var accumulatedCost = 0L
      last.run(
        FlowEmit.fromInline: t =>
          buffer = buffer :+ t

          accumulatedCost += costFn(t)

          if accumulatedCost >= minWeight then
            emit.apply(buffer)
            buffer = Vector.empty
            accumulatedCost = 0
      )
      if buffer.nonEmpty then emit.apply(buffer)
  end groupedWeighted

  /** Chunks up the emitted elements into groups, within a time window, or limited by the specified number of elements, whatever happens
    * first. The timeout is reset after a group is emitted. If timeout expires and the buffer is empty, nothing is emitted. As soon as a new
    * element is emitted, the flow will emit it as a single-element group and reset the timer.
    *
    * @param n
    *   The maximum number of elements in a group.
    * @param duration
    *   The time window in which the elements are grouped.
    */
  def groupedWithin(n: Int, duration: FiniteDuration)(using BufferCapacity): Flow[Seq[T]] = groupedWeightedWithin(n, duration)(_ => 1)

  private case object GroupingTimeout

  /** Chunks up the emitted elements into groups, within a time window, or limited by the cumulative weight being greater or equal to the
    * `minWeight`, whatever happens first. The timeout is reset after a group is emitted. If timeout expires and the buffer is empty,
    * nothing is emitted. As soon as a new element is received, the flow will emit it as a single-element group and reset the timer.
    *
    * @param minWeight
    *   The minimum cumulative weight of elements in a group if no timeout happens.
    * @param duration
    *   The time window in which the elements are grouped.
    * @param costFn
    *   The function that calculates the weight of an element.
    */
  def groupedWeightedWithin(minWeight: Long, duration: FiniteDuration)(costFn: T => Long)(using BufferCapacity): Flow[Seq[T]] =
    require(minWeight > 0, "minWeight must be > 0")
    require(duration > 0.seconds, "duration must be > 0")

    Flow.usingEmitInline: emit =>
      unsupervised:
        val c = outer.runToChannel()
        val c2 = BufferCapacity.newChannel[Seq[T]]
        val timerChannel = BufferCapacity.newChannel[GroupingTimeout.type]
        forkPropagate(c2):
          var buffer = Vector.empty[T]
          var accumulatedCost: Long = 0

          def forkTimeout() = forkCancellable:
            sleep(duration)
            timerChannel.sendOrClosed(GroupingTimeout).discard

          var timeoutFork: Option[CancellableFork[Unit]] = Some(forkTimeout())

          def sendBufferAndForkNewTimeout(): Unit =
            c2.send(buffer)
            buffer = Vector.empty
            accumulatedCost = 0
            timeoutFork.foreach(_.cancelNow())
            timeoutFork = Some(forkTimeout())

          repeatWhile:
            selectOrClosed(c.receiveClause, timerChannel.receiveClause) match
              case ChannelClosed.Done =>
                timeoutFork.foreach(_.cancelNow())
                if buffer.nonEmpty then c2.send(buffer)
                c2.done()
                false
              case ChannelClosed.Error(r) =>
                timeoutFork.foreach(_.cancelNow())
                c2.error(r)
                false
              case timerChannel.Received(GroupingTimeout) =>
                timeoutFork = None // enter 'timed out state', may stay in this state if buffer is empty
                if buffer.nonEmpty then sendBufferAndForkNewTimeout()
                true
              case c.Received(t) =>
                buffer = buffer :+ t

                accumulatedCost += costFn(t).tapException(_ => timeoutFork.foreach(_.cancelNow()))

                if timeoutFork.isEmpty || accumulatedCost >= minWeight then
                  // timeout passed when buffer was empty or buffer full
                  sendBufferAndForkNewTimeout()

                true

        FlowEmit.channelToEmit(c2, emit)
  end groupedWeightedWithin

  /** Creates sliding windows of elements from this flow. The window slides by `step` elements. The last window may be smaller due to flow
    * being completed.
    *
    * @param n
    *   The number of elements in a window.
    * @param step
    *   The number of elements the window slides by.
    */
  def sliding(n: Int, step: Int = 1): Flow[Seq[T]] =
    require(n > 0, "n must be > 0")
    require(step > 0, "step must be > 0")

    Flow.usingEmitInline: emit =>
      var buffer = Vector.empty[T]
      last.run(
        FlowEmit.fromInline: t =>
          buffer = buffer :+ t
          if buffer.size < n then () // do nothing
          else if buffer.size == n then emit(buffer)
          else if step <= n then
            // if step is <= n we simply drop `step` elements and continue appending until buffer size is n
            buffer = buffer.drop(step)
            // in special case when step == 1, we have to send the buffer immediately
            if buffer.size == n then emit(buffer)
          else
          // step > n -  we drop `step` elements and continue appending until buffer size is n
          if buffer.size == step then buffer = buffer.drop(step)
          end if
      )
      // send the remaining elements, only if these elements were not yet sent
      if buffer.nonEmpty && buffer.size < n then emit(buffer)
  end sliding

  /** Attaches the given [[ox.channels.Sink]] to this flow, meaning elements that pass through will also be sent to the sink. If emitting an
    * element, or sending to the `other` sink blocks, no elements will be processed until both are done. The elements are first emitted by
    * the flow and then, only if that was successful, to the `other` sink.
    *
    * If this flow fails, then failure is passed to the `other` sink as well. If the `other` sink is failed or complete, this becomes a
    * failure of the returned flow (contrary to [[alsoToTap]] where it's ignored).
    *
    * @param other
    *   The sink to which elements from this flow will be sent.
    * @see
    *   [[alsoToTap]] for a version that drops elements when the `other` sink is not available for receive.
    */
  def alsoTo[U >: T](other: Sink[U]): Flow[U] = Flow.usingEmitInline: emit =>
    {
      last.run(
        FlowEmit.fromInline: t =>
          emit(t).tapException(e => other.errorOrClosed(e).discard)
          other.send(t)
      )
      other.done()
    }.tapException(other.error)
  end alsoTo

  private case object NotSent

  /** Attaches the given [[ox.channels.Sink]] to this flow, meaning elements that pass through will also be sent to the sink. If the `other`
    * sink is not available for receive, the elements are still emitted by the returned flow, but not sent to the `other` sink.
    *
    * If this flow fails, then failure is passed to the `other` sink as well. If the `other` sink fails or closes, then failure or closure
    * is ignored and it doesn't affect the resulting flow (contrary to [[alsoTo]] where it's propagated).
    *
    * @param other
    *   The sink to which elements from this source will be sent.
    * @see
    *   [[alsoTo]] for a version that ensures that elements are emitted both by the returned flow and sent to the `other` sink.
    */
  def alsoToTap[U >: T](other: Sink[U]): Flow[U] = Flow.usingEmitInline: emit =>
    {
      last.run(
        FlowEmit.fromInline: t =>
          emit(t).tapException(e => other.errorOrClosed(e).discard)
          selectOrClosed(other.sendClause(t), Default(NotSent)).discard
      )
      other.doneOrClosed().discard
    }.tapException(other.errorOrClosed(_).discard)
  end alsoToTap

  //

  protected def runLastToChannelAsync(ch: Sink[T])(using OxUnsupervised): Unit =
    forkPropagate(ch) {
      last.run(FlowEmit.fromInline(t => ch.send(t)))
      ch.done()
    }.discard
end FlowOps