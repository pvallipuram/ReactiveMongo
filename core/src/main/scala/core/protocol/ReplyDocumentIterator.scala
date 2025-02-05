package reactivemongo.core.protocol

import scala.util.control.NonFatal

import reactivemongo.io.netty.buffer.ByteBuf

import reactivemongo.api.SerializationPack

import reactivemongo.api.bson.collection.BSONSerializationPack

import reactivemongo.core.errors.GenericDriverException

private[reactivemongo] object ReplyDocumentIterator
  extends ReplyDocumentIteratorLowPriority {

  // BSON optimized parse alternative
  def parse[A](pack: BSONSerializationPack.type)(response: Response)(implicit reader: pack.Reader[A]): Iterator[A] = response match {
    case Response.CommandError(_, _, _, cause) =>
      new FailingIterator[A](cause)

    case Response.WithCursor(_, _, _, _, _, _, preloaded) => {
      val buf = response.documents

      if (buf.readableBytes == 0) {
        Iterator.empty
      } else {
        try {
          buf.skipBytes(buf.getIntLE(buf.readerIndex))

          def docs = parseDocuments[BSONSerializationPack.type, A](pack)(buf)

          val firstBatch = preloaded.iterator.map { bson =>
            pack.deserialize(bson, reader)
          }

          firstBatch ++ docs
        } catch {
          case NonFatal(cause) => new FailingIterator[A](cause)
        }
      }
    }

    case _ => parseDocuments[BSONSerializationPack.type, A](
      pack)(response.documents)
  }

  private[core] def parseDocuments[P <: SerializationPack, A](pack: P)(buffer: ByteBuf)(implicit reader: pack.Reader[A]): Iterator[A] = new Iterator[A] {
    override val isTraversableAgain = false
    override def hasNext = buffer.isReadable()

    override def next() = try {
      val sz = buffer.getIntLE(buffer.readerIndex)
      val cbrb = reactivemongo.api.bson.buffer.
        ReadableBuffer(buffer readSlice sz)

      pack.readAndDeserialize(cbrb, reader)
    } catch {
      case e: IndexOutOfBoundsException =>
        /*
         * If this happens, the buffer is exhausted,
         * and there is probably a bug.
         *
         * It may happen if an enumerator relying on
         * it is concurrently applied to many iteratees
         * – which should not be done!
         */
        throw new ReplyDocumentIteratorExhaustedException(e)
    }
  }
}

private[reactivemongo] sealed trait ReplyDocumentIteratorLowPriority {
  _self: ReplyDocumentIterator.type =>

  def parse[P <: SerializationPack, A](pack: P)(response: Response)(implicit reader: pack.Reader[A]): Iterator[A] = response match {
    case Response.CommandError(_, _, _, cause) =>
      new FailingIterator[A](cause)

    case Response.WithCursor(_, _, _, _, _, _, preloaded) => {
      val buf = response.documents

      if (buf.readableBytes == 0) {
        Iterator.empty
      } else {
        try {
          buf.skipBytes(buf.getIntLE(buf.readerIndex))

          def docs = parseDocuments[P, A](pack)(buf)

          val firstBatch: Iterator[A] = {
            // Wrap iterator so that deserialization is only called on next,
            // and possibly error only raised there (not on hasNext).
            val underlying = preloaded.iterator

            new Iterator[A] {
              @inline def hasNext = underlying.hasNext

              def next(): A = underlying.next() match {
                case pack.IsDocument(bson) =>
                  pack.deserialize(bson, reader)

                case v =>
                  throw new GenericDriverException(s"Invalid document: $v")
              }
            }
          }

          firstBatch ++ docs
        } catch {
          case NonFatal(cause) => new FailingIterator[A](cause)
        }
      }
    }

    case _ => parseDocuments[P, A](pack)(response.documents)
  }

  protected final class FailingIterator[A](
    cause: Throwable) extends Iterator[A] {
    val hasNext = false
    @inline def next(): A = throw cause
  }
}

private[reactivemongo] final class ReplyDocumentIteratorExhaustedException(
  val cause: Exception) extends Exception(cause) {

  override def equals(that: Any): Boolean = that match {
    case other: ReplyDocumentIteratorExhaustedException =>
      (this.cause == null && other.cause == null) || (
        this.cause != null && this.cause.equals(other.cause))

    case _ =>
      false
  }

  @SuppressWarnings(Array("NullParameter"))
  override def hashCode: Int = if (cause == null) -1 else cause.hashCode
}
