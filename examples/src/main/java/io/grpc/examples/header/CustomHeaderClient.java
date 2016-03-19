/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.examples.header;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that like {@link io.grpc.examples.helloworld.HelloWorldClient}.
 * This client can help you create custom headers.
 */
public class CustomHeaderClient {
  private static final Logger logger = Logger.getLogger(CustomHeaderClient.class.getName());

  private final ManagedChannel originChannel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /**
   * A custom client.
   */
  private CustomHeaderClient(String host, int port) {
    originChannel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    ClientInterceptor interceptor = new RetryInterceptor();
    Channel channel = ClientInterceptors.intercept(originChannel, interceptor);
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  private void shutdown() throws InterruptedException {
    originChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * A simple client method that like {@link io.grpc.examples.helloworld.HelloWorldClient}.
   */
  private void greet(String name) {
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Greeting: " + response.getMessage());
  }

  /**
   * Main start the client from the command line.
   */
  public static void main(String[] args) throws Exception {
    CustomHeaderClient client = new CustomHeaderClient("localhost", 50051);
    try {
      /* Access a service running on the local machine on port 50051 */
      String user = "world";
      if (args.length > 0) {
        user = args[0]; /* Use the arg as the name to greet if provided */
      }
      client.greet(user);
    } finally {
      client.shutdown();
    }
  }

  public static final class RetryInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new RetrybleClientCall<ReqT, RespT>(next.newCall(method, callOptions), next, method, callOptions);;
    }
  }

  public static class RetrybleClientCall<ReqT, RespT> extends ForwardingClientCall<ReqT, RespT> {
    private final ClientCall<ReqT, RespT> orignalCall;
    private final Channel channel;
    private final MethodDescriptor<ReqT, RespT> method;
    private final CallOptions callOptions;
    public ReqT orignalRequest;

    protected RetrybleClientCall(ClientCall<ReqT, RespT> orignalCall, Channel next, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      this.orignalCall = orignalCall;
      this.channel = next;
      this.method = method;
      this.callOptions = callOptions;
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      super.start(new RetryableClientCallListener(responseListener), headers);
    }

    @Override
    public void sendMessage(ReqT message) {
      if (orignalRequest == null) {
        orignalRequest = message;
      }
      super.sendMessage(orignalRequest);
    }

    @Override
    protected ClientCall<ReqT, RespT> delegate() {
      return orignalCall;
    }

    public class RetryableClientCallListener extends ForwardingClientCallListener<RespT> {
      private final Listener<RespT> delegateListener;

      public RetryableClientCallListener(Listener<RespT> delegateListener) {
        this.delegateListener = delegateListener;
      }

      @Override
      protected Listener<RespT> delegate() {
        return delegateListener;
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        if (!status.isOk()) {
          logger.info("failed, retrying");
          // Failed, start new request
          ClientCalls.blockingUnaryCall(channel.newCall(method, callOptions), orignalRequest);
        } else {
          super.onClose(status, trailers);
        }
      }
    }
  }
}
