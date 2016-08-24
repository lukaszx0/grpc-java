/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.util;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.AbstractLoadBalancer;
import io.grpc.ResolvedServerInfo;
import io.grpc.TransportManager;
import io.grpc.internal.RoundRobinServerList;

/**
 * A {@link LoadBalancer} that provides round-robin load balancing mechanism over the
 * addresses from the {@link NameResolver}.  The sub-lists received from the name resolver
 * are considered to be an {@link EquivalentAddressGroup} and each of these sub-lists is
 * what is then balanced across.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public final class RoundRobinLoadBalancerFactory extends LoadBalancer.Factory {

  private static final RoundRobinLoadBalancerFactory instance = new RoundRobinLoadBalancerFactory();

  private RoundRobinLoadBalancerFactory() {
  }

  public static RoundRobinLoadBalancerFactory getInstance() {
    return instance;
  }

  @Override
  public <T> LoadBalancer<T> newLoadBalancer(String serviceName, TransportManager<T> tm) {
    return new RoundRobinLoadBalancer<T>(tm);
  }

  private static class RoundRobinLoadBalancer<T> extends AbstractLoadBalancer<T> {
    private RoundRobinLoadBalancer(TransportManager<T> tm) {
      super(tm);
    }

    @Override
    protected T doPickTransport(List<? extends List<ResolvedServerInfo>> updatedServers,
        Attributes attributes) {
      RoundRobinServerList.Builder<T> listBuilder = new RoundRobinServerList.Builder<T>(tm);
      for (List<ResolvedServerInfo> servers : updatedServers) {
        if (servers.isEmpty()) {
          continue;
        }

        final List<SocketAddress> socketAddresses = new ArrayList<SocketAddress>(servers.size());
        for (ResolvedServerInfo server : servers) {
          socketAddresses.add(server.getAddress());
        }
        listBuilder.addList(socketAddresses);
      }
      RoundRobinServerList<T> roundRobinList = listBuilder.build();
      return roundRobinList.getTransportForNextServer();
    }
  }
}
