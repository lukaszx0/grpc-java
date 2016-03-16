/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.SharedResourceHolder.Resource;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

/**
 * Common utilities for GRPC.
 */
// TODO(lukaszx0) replace all deprecated calls internally and remove deprecated fields in 1.0 (?)
public final class GrpcUtil {

  /**
   * {@link io.grpc.Metadata.Key} for the timeout header.
   */
  public static final Metadata.Key<Long> TIMEOUT_METADATA_KEY =
          Metadata.Key.of(GrpcUtil.TIMEOUT, new TimeoutMarshaller());

  /**
   * @deprecated replaced by {@link #TIMEOUT_METADATA_KEY}.
   */
  @Deprecated
  public static final Metadata.Key<Long> TIMEOUT_KEY = TIMEOUT_METADATA_KEY;

  /**
   * {@link io.grpc.Metadata.Key} for the message encoding header.
   */
  public static final Metadata.Key<String> MESSAGE_ENCODING_METADATA_KEY =
          Metadata.Key.of(GrpcUtil.MESSAGE_ENCODING, Metadata.ASCII_STRING_MARSHALLER);

  /**
   * @deprecated replaced by {@link #MESSAGE_ENCODING_METADATA_KEY}.
   */
  @Deprecated
  public static final Metadata.Key<String> MESSAGE_ENCODING_KEY = MESSAGE_ENCODING_METADATA_KEY;

  /**
   * {@link io.grpc.Metadata.Key} for the accepted message encodings header.
   */
  public static final Metadata.Key<String> MESSAGE_ACCEPT_ENCODING_METADATA_KEY =
          Metadata.Key.of(GrpcUtil.MESSAGE_ACCEPT_ENCODING, Metadata.ASCII_STRING_MARSHALLER);

  /**
   * @deprecated replaced by {@link #MESSAGE_ACCEPT_ENCODING_METADATA_KEY}.
   */
  @Deprecated
  public static final Metadata.Key<String> MESSAGE_ACCEPT_ENCODING_KEY =
          MESSAGE_ACCEPT_ENCODING_METADATA_KEY;

  /**
   * {@link io.grpc.Metadata.Key} for the Content-Type request/response header.
   */
  public static final Metadata.Key<String> CONTENT_TYPE_METADATA_KEY =
          Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * @deprecated replaced by {@link #CONTENT_TYPE_METADATA_KEY}.
   */
  @Deprecated
  public static final Metadata.Key<String> CONTENT_TYPE_KEY = CONTENT_TYPE_METADATA_KEY;

  /**
   * {@link io.grpc.Metadata.Key} for the Content-Type request/response header.
   */
  public static final Metadata.Key<String> USER_AGENT_METADATA_KEY =
          Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * @deprecated replaced by {@link #USER_AGENT_METADATA_KEY}.
   */
  @Deprecated
  public static final Metadata.Key<String> USER_AGENT_KEY = USER_AGENT_METADATA_KEY;

  /**
   * {@link io.grpc.Attributes.Key} for the remote address of stream call.
   */
  public static final Attributes.Key<SocketAddress> REMOTE_ADDR_STREAM_ATTR_KEY =
          Attributes.Key.of("io.grpc.RemoteAddr");

  /**
   * {@link io.grpc.Attributes.Key} for the SSL session of stream call.
   */
  public static final Attributes.Key<Optional<SSLSession>> SSL_SESSION_STREAM_ATTR_KEY =
          Attributes.Key.of("io.grpc.SslSession");

  /**
   * The default port for plain-text connections.
   */
  public static final int DEFAULT_PORT_PLAINTEXT = 80;

  /**
   * The default port for SSL connections.
   */
  public static final int DEFAULT_PORT_SSL = 443;

  /**
   * Content-Type used for GRPC-over-HTTP/2.
   */
  public static final String CONTENT_TYPE_GRPC = "application/grpc";

  /**
   * The HTTP method used for GRPC requests.
   */
  public static final String HTTP_METHOD = "POST";

  /**
   * The TE (transport encoding) header for requests over HTTP/2.
   */
  public static final String TE_TRAILERS = "trailers";

  /**
   * The Timeout header name.
   */
  public static final String TIMEOUT = "grpc-timeout";

  /**
   * The message encoding (i.e. compression) that can be used in the stream.
   */
  public static final String MESSAGE_ENCODING = "grpc-encoding";

  /**
   * The accepted message encodings (i.e. compression) that can be used in the stream.
   */
  public static final String MESSAGE_ACCEPT_ENCODING = "grpc-accept-encoding";

  /**
   * The default maximum uncompressed size (in bytes) for inbound messages. Defaults to 100 MiB.
   */
  public static final int DEFAULT_MAX_MESSAGE_SIZE = 100 * 1024 * 1024;

  /**
   * The default maximum size (in bytes) for inbound header/trailer.
   */
  public static final int DEFAULT_MAX_HEADER_LIST_SIZE = 8192;

  public static final Splitter ACCEPT_ENCODING_SPLITER = Splitter.on(',').trimResults();

  public static final Joiner ACCEPT_ENCODING_JOINER = Joiner.on(',');

  /**
   * Maps HTTP error response status codes to transport codes.
   */
  public static Status httpStatusToGrpcStatus(int httpStatusCode) {
    // Specific HTTP code handling.
    switch (httpStatusCode) {
      case HttpURLConnection.HTTP_UNAUTHORIZED:  // 401
        return Status.UNAUTHENTICATED;
      case HttpURLConnection.HTTP_FORBIDDEN:  // 403
        return Status.PERMISSION_DENIED;
      default:
    }
    // Generic HTTP code handling.
    if (httpStatusCode < 100) {
      // 0xx. These don't exist.
      return Status.UNKNOWN;
    }
    if (httpStatusCode < 200) {
      // 1xx. These headers should have been ignored.
      return Status.INTERNAL;
    }
    if (httpStatusCode < 300) {
      // 2xx
      return Status.OK;
    }
    return Status.UNKNOWN;
  }

  /**
   * All error codes identified by the HTTP/2 spec. Used in GOAWAY and RST_STREAM frames.
   */
  public enum Http2Error {
    /**
     * Servers implementing a graceful shutdown of the connection will send {@code GOAWAY} with
     * {@code NO_ERROR}. In this case it is important to indicate to the application that the
     * request should be retried (i.e. {@link Status#UNAVAILABLE}).
     */
    NO_ERROR(0x0, Status.UNAVAILABLE),
    PROTOCOL_ERROR(0x1, Status.INTERNAL),
    INTERNAL_ERROR(0x2, Status.INTERNAL),
    FLOW_CONTROL_ERROR(0x3, Status.INTERNAL),
    SETTINGS_TIMEOUT(0x4, Status.INTERNAL),
    STREAM_CLOSED(0x5, Status.INTERNAL),
    FRAME_SIZE_ERROR(0x6, Status.INTERNAL),
    REFUSED_STREAM(0x7, Status.UNAVAILABLE),
    CANCEL(0x8, Status.CANCELLED),
    COMPRESSION_ERROR(0x9, Status.INTERNAL),
    CONNECT_ERROR(0xA, Status.INTERNAL),
    ENHANCE_YOUR_CALM(0xB, Status.RESOURCE_EXHAUSTED.withDescription("Bandwidth exhausted")),
    INADEQUATE_SECURITY(0xC, Status.PERMISSION_DENIED.withDescription("Permission denied as "
        + "protocol is not secure enough to call")),
    HTTP_1_1_REQUIRED(0xD, Status.UNKNOWN);

    // Populate a mapping of code to enum value for quick look-up.
    private static final Http2Error[] codeMap = buildHttp2CodeMap();

    private static Http2Error[] buildHttp2CodeMap() {
      Http2Error[] errors = Http2Error.values();
      int size = (int) errors[errors.length - 1].code() + 1;
      Http2Error[] http2CodeMap = new Http2Error[size];
      for (Http2Error error : errors) {
        int index = (int) error.code();
        http2CodeMap[index] = error;
      }
      return http2CodeMap;
    }

    private final int code;
    private final Status status;

    Http2Error(int code, Status status) {
      this.code = code;
      this.status = status.augmentDescription("HTTP/2 error code: " + this.name());
    }

    /**
     * Gets the code for this error used on the wire.
     */
    public long code() {
      return code;
    }

    /**
     * Gets the {@link Status} associated with this HTTP/2 code.
     */
    public Status status() {
      return status;
    }

    /**
     * Looks up the HTTP/2 error code enum value for the specified code.
     *
     * @param code an HTTP/2 error code value.
     * @return the HTTP/2 error code enum or {@code null} if not found.
     */
    public static Http2Error forCode(long code) {
      if (code >= codeMap.length || code < 0) {
        return null;
      }
      return codeMap[(int) code];
    }

    /**
     * Looks up the {@link Status} from the given HTTP/2 error code. This is preferred over {@code
     * forCode(code).status()}, to more easily conform to HTTP/2:
     *
     * <blockquote>Unknown or unsupported error codes MUST NOT trigger any special behavior.
     * These MAY be treated by an implementation as being equivalent to INTERNAL_ERROR.</blockquote>
     *
     * @param code the HTTP/2 error code.
     * @return a {@link Status} representing the given error.
     */
    public static Status statusForCode(long code) {
      Http2Error error = forCode(code);
      if (error == null) {
        // This "forgets" the message of INTERNAL_ERROR while keeping the same status code.
        Status.Code statusCode = INTERNAL_ERROR.status().getCode();
        return Status.fromCodeValue(statusCode.value())
            .withDescription("Unrecognized HTTP/2 error code: " + code);
      }

      return error.status();
    }
  }

  /**
   * Indicates whether or not the given value is a valid gRPC content-type.
   */
  public static boolean isGrpcContentType(String contentType) {
    if (contentType == null) {
      return false;
    }

    if (CONTENT_TYPE_GRPC.length() > contentType.length()) {
      return false;
    }

    contentType = contentType.toLowerCase();
    if (!contentType.startsWith(CONTENT_TYPE_GRPC)) {
      // Not a gRPC content-type.
      return false;
    }

    if (contentType.length() == CONTENT_TYPE_GRPC.length()) {
      // The strings match exactly.
      return true;
    }

    // The contentType matches, but is longer than the expected string.
    // We need to support variations on the content-type (e.g. +proto, +json) as defined by the
    // gRPC wire spec.
    char nextChar = contentType.charAt(CONTENT_TYPE_GRPC.length());
    return nextChar == '+' || nextChar == ';';
  }

  /**
   * Gets the User-Agent string for the gRPC transport.
   */
  public static String getGrpcUserAgent(String transportName,
                                        @Nullable String applicationUserAgent) {
    StringBuilder builder = new StringBuilder();
    if (applicationUserAgent != null) {
      builder.append(applicationUserAgent);
      builder.append(' ');
    }
    builder.append("grpc-java-");
    builder.append(transportName);
    String version = GrpcUtil.class.getPackage().getImplementationVersion();
    if (version != null) {
      builder.append("/");
      builder.append(version);
    }
    return builder.toString();
  }

  /**
   * Parse an authority into a URI for retrieving the host and port.
   */
  public static URI authorityToUri(String authority) {
    Preconditions.checkNotNull(authority, "authority");
    URI uri;
    try {
      uri = new URI(null, authority, null, null, null);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid authority: " + authority, ex);
    }
    return uri;
  }

  /**
   * Verify {@code authority} is valid for use with gRPC. The syntax must be valid and it must not
   * include userinfo.
   *
   * @return the {@code authority} provided
   */
  public static String checkAuthority(String authority) {
    URI uri = authorityToUri(authority);
    checkArgument(uri.getHost() != null, "No host in authority '%s'", authority);
    checkArgument(uri.getUserInfo() == null,
        "Userinfo must not be present on authority: '%s'", authority);
    return authority;
  }

  /**
   * Combine a host and port into an authority string.
   */
  public static String authorityFromHostAndPort(String host, int port) {
    try {
      return new URI(null, null, host, port, null, null, null).getAuthority();
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid host or port: " + host + " " + port, ex);
    }
  }

  /**
   * Shared executor for channels.
   */
  public static final Resource<ExecutorService> SHARED_CHANNEL_EXECUTOR =
      new Resource<ExecutorService>() {
        private static final String name = "grpc-default-executor";
        @Override
        public ExecutorService create() {
          return Executors.newCachedThreadPool(new ThreadFactoryBuilder()
              .setDaemon(true)
              .setNameFormat(name + "-%d")
              .build());
        }

        @Override
        public void close(ExecutorService instance) {
          instance.shutdown();
        }

        @Override
        public String toString() {
          return name;
        }
      };

  /**
   * Shared executor for managing channel timers.
   */
  public static final Resource<ScheduledExecutorService> TIMER_SERVICE =
      new Resource<ScheduledExecutorService>() {
        @Override
        public ScheduledExecutorService create() {
          ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(
              new ThreadFactoryBuilder()
                  .setDaemon(true)
                  .setNameFormat("grpc-timer-%d")
                  .build());
          // If there are long timeouts that are cancelled, they will not actually be removed from
          // the executors queue.  This forces immediate removal upon cancellation to avoid a
          // memory leak.  Reflection is used because we cannot use methods added in Java 1.7.  If
          // the method does not exist, we give up.  Note that the method is not present in 1.6, but
          // _is_ present in the android standard library.
          try {
            Method method = service.getClass().getMethod("setRemoveOnCancelPolicy", boolean.class);
            method.invoke(service, true);
          } catch (NoSuchMethodException e) {
            // no op
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }

          return service;
        }

        @Override
        public void close(ScheduledExecutorService instance) {
          instance.shutdown();
        }
      };

  /**
   * Marshals a nanoseconds representation of the timeout to and from a string representation,
   * consisting of an ASCII decimal representation of a number with at most 8 digits, followed by a
   * unit:
   * n = nanoseconds
   * u = microseconds
   * m = milliseconds
   * S = seconds
   * M = minutes
   * H = hours
   *
   * <p>The representation is greedy with respect to precision. That is, 2 seconds will be
   * represented as `2000000u`.</p>
   *
   * <p>See <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests">the
   * request header definition</a></p>
   */
  @VisibleForTesting
  static class TimeoutMarshaller implements Metadata.AsciiMarshaller<Long> {

    // ImmutableMap's have consistent iteration order.
    private static final ImmutableMap<Character, TimeUnit> UNITS =
        ImmutableMap.<Character, TimeUnit>builder()
            .put('n', TimeUnit.NANOSECONDS)
            .put('u', TimeUnit.MICROSECONDS)
            .put('m', TimeUnit.MILLISECONDS)
            .put('S', TimeUnit.SECONDS)
            .put('M', TimeUnit.MINUTES)
            .put('H', TimeUnit.HOURS)
            .build();

    @Override
    public String toAsciiString(Long timeoutNanos) {
      checkArgument(timeoutNanos >= 0, "Negative timeout");
      // the smallest integer with 9 digits
      int cutoff = 100000000;
      for (Entry<Character, TimeUnit> unit : UNITS.entrySet()) {
        long timeout = unit.getValue().convert(timeoutNanos, TimeUnit.NANOSECONDS);
        if (timeout < cutoff) {
          return Long.toString(timeout) + unit.getKey();
        }
      }
      throw new IllegalArgumentException("Timeout too large");
    }

    @Override
    public Long parseAsciiString(String serialized) {
      checkArgument(serialized.length() > 0, "empty timeout");
      checkArgument(serialized.length() <= 9, "bad timeout format");
      String valuePart = serialized.substring(0, serialized.length() - 1);
      char unit = serialized.charAt(serialized.length() - 1);
      TimeUnit timeUnit = UNITS.get(unit);
      if (timeUnit != null) {
        return timeUnit.toNanos(Long.parseLong(valuePart));
      }
      throw new IllegalArgumentException(String.format("Invalid timeout unit: %s", unit));
    }
  }

  /**
   * The canonical implementation of {@link WithLogId#getLogId}.
   */
  public static String getLogId(WithLogId subject) {
    return subject.getClass().getSimpleName() + "@" + Integer.toHexString(subject.hashCode());
  }

  private GrpcUtil() {}
}
