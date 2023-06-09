/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.okhttp;

import com.google.common.base.Preconditions;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.TransportFrameUtil;
import io.grpc.okhttp.internal.framed.Header;
import java.util.ArrayList;
import java.util.List;
import okio.ByteString;

/**
 * Constants for request/response headers.
 */
class Headers {

  public static final Header HTTPS_SCHEME_HEADER = new Header(Header.TARGET_SCHEME, "https");
  public static final Header HTTP_SCHEME_HEADER = new Header(Header.TARGET_SCHEME, "http");
  public static final Header METHOD_HEADER = new Header(Header.TARGET_METHOD, GrpcUtil.HTTP_METHOD);
  public static final Header METHOD_GET_HEADER = new Header(Header.TARGET_METHOD, "GET");
  public static final Header CONTENT_TYPE_HEADER =
      new Header(GrpcUtil.CONTENT_TYPE_KEY.name(), GrpcUtil.CONTENT_TYPE_GRPC);
  public static final Header TE_HEADER = new Header("te", GrpcUtil.TE_TRAILERS);

  /**
   * Serializes the given headers and creates a list of OkHttp {@link Header}s to be used when
   * creating a stream. Since this serializes the headers, this method should be called in the
   * application thread context.
   */
  public static List<Header> createRequestHeaders(
      Metadata headers,
      String defaultPath,
      String authority,
      String userAgent,
      boolean useGet,
      boolean usePlaintext) {
    Preconditions.checkNotNull(headers, "headers");
    Preconditions.checkNotNull(defaultPath, "defaultPath");
    Preconditions.checkNotNull(authority, "authority");

    stripNonApplicationHeaders(headers);

    // 7 is the number of explicit add calls below.
    List<Header> okhttpHeaders = new ArrayList<>(7 + InternalMetadata.headerCount(headers));

    // Set GRPC-specific headers.
    if (usePlaintext) {
      okhttpHeaders.add(HTTP_SCHEME_HEADER);
    } else {
      okhttpHeaders.add(HTTPS_SCHEME_HEADER);
    }
    if (useGet) {
      okhttpHeaders.add(METHOD_GET_HEADER);
    } else {
      okhttpHeaders.add(METHOD_HEADER);
    }

    okhttpHeaders.add(new Header(Header.TARGET_AUTHORITY, authority));
    String path = defaultPath;
    okhttpHeaders.add(new Header(Header.TARGET_PATH, path));

    okhttpHeaders.add(new Header(GrpcUtil.USER_AGENT_KEY.name(), userAgent));

    // All non-pseudo headers must come after pseudo headers.
    okhttpHeaders.add(CONTENT_TYPE_HEADER);
    okhttpHeaders.add(TE_HEADER);

    // Now add any application-provided headers.
    return addMetadata(okhttpHeaders, headers);
  }

  /**
   * Serializes the given headers and creates a list of OkHttp {@link Header}s to be used when
   * starting a response. Since this serializes the headers, this method should be called in the
   * application thread context.
   */
  public static List<Header> createResponseHeaders(Metadata headers) {
    stripNonApplicationHeaders(headers);

    // 2 is the number of explicit add calls below.
    List<Header> okhttpHeaders = new ArrayList<>(2 + InternalMetadata.headerCount(headers));
    okhttpHeaders.add(new Header(Header.RESPONSE_STATUS, "200"));
    // All non-pseudo headers must come after pseudo headers.
    okhttpHeaders.add(CONTENT_TYPE_HEADER);
    return addMetadata(okhttpHeaders, headers);
  }

  /**
   * Serializes the given headers and creates a list of OkHttp {@link Header}s to be used when
   * finishing a response. Since this serializes the headers, this method should be called in the
   * application thread context.
   */
  public static List<Header> createResponseTrailers(Metadata trailers, boolean headersSent) {
    if (!headersSent) {
      return createResponseHeaders(trailers);
    }
    stripNonApplicationHeaders(trailers);

    List<Header> okhttpTrailers = new ArrayList<>(InternalMetadata.headerCount(trailers));
    return addMetadata(okhttpTrailers, trailers);
  }

  /**
   * Serializes the given headers and creates a list of OkHttp {@link Header}s to be used when
   * failing with an HTTP response.
   */
  public static List<Header> createHttpResponseHeaders(
      int httpCode, String contentType, Metadata headers) {
    // 2 is the number of explicit add calls below.
    List<Header> okhttpHeaders = new ArrayList<>(2 + InternalMetadata.headerCount(headers));
    okhttpHeaders.add(new Header(Header.RESPONSE_STATUS, "" + httpCode));
    // All non-pseudo headers must come after pseudo headers.
    okhttpHeaders.add(new Header(GrpcUtil.CONTENT_TYPE_KEY.name(), contentType));
    return addMetadata(okhttpHeaders, headers);
  }

  private static List<Header> addMetadata(List<Header> okhttpHeaders, Metadata toAdd) {
    byte[][] serializedHeaders = TransportFrameUtil.toHttp2Headers(toAdd);
    for (int i = 0; i < serializedHeaders.length; i += 2) {
      ByteString key = ByteString.of(serializedHeaders[i]);
      // Don't allow HTTP/2 pseudo headers to be added by the application.
      if (key.size() == 0 || key.getByte(0) == ':') {
        continue;
      }
      ByteString value = ByteString.of(serializedHeaders[i + 1]);
      okhttpHeaders.add(new Header(key, value));
    }
    return okhttpHeaders;
  }

  /** Strips all non-pseudo headers reserved by gRPC, to avoid duplicates and misinterpretation. */
  private static void stripNonApplicationHeaders(Metadata headers) {
    headers.discardAll(GrpcUtil.CONTENT_TYPE_KEY);
    headers.discardAll(GrpcUtil.TE_HEADER);
    headers.discardAll(GrpcUtil.USER_AGENT_KEY);
  }
}
