package fs2.serial.errors

/** The operating-system errors this library tells apart, by their symbolic
  * name. A closed set, so a misspelt name does not compile; anything else is
  * `Unknown`, with its number kept in [[Errno]].
  */
enum ErrnoName {
  case EPERM, ENOENT, EINTR, EIO, ENXIO, EBADF, EACCES, EFAULT, EBUSY, ENODEV,
    ENOTDIR, EINVAL,
    ENFILE, EMFILE, ENOTTY, EPIPE, EAGAIN, Unknown
}
