import java.io.{DataInputStream, DataOutputStream}

case class Frame(path: String, mtime: Long, bytes: Array[Byte])

object Protocol:
  def write(out: DataOutputStream, f: Frame): Unit = {
    out.writeUTF(f.path)
    out.writeLong(f.mtime)
    out.writeInt(f.bytes.length)
    out.write(f.bytes)
    out.flush()
  }

  def read(in: DataInputStream): Frame = {
    val path = in.readUTF()
    val mtime = in.readLong()
    val length = in.readInt()
    val bytes = new Array[Byte](length)
    in.readFully(bytes)
    Frame(path, mtime, bytes)
  }

