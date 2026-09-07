package orphera.common

import io.grpc.Metadata

object Auth:
  val TokenKey: Metadata.Key[String] =
    Metadata.Key.of("orphera-token", Metadata.ASCII_STRING_MARSHALLER)

  // Replace with a real secret from an env var / secrets manager before using this for anything real.
  val SharedToken: String =
    sys.env.getOrElse("ORPHERA_TOKEN", "dev-only-insecure-token")
