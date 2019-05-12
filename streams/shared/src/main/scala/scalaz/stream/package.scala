package scalaz.zio

package object stream {

  type Stream[+EC, +EP, +A] = ZStream[Any, EC, EP, A]

  type StreamChunk[+E, +A] = ZStreamChunk[Any, E, A]
  val StreamChunk = ZStreamChunk

  type Sink[+E, +A0, -A, +B] = ZSink[Any, E, A0, A, B]
  val Sink = ZSink

}
