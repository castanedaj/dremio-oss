/*
 * Copyright (C) 2017-2018 Dremio Corporation
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
package com.dremio.services.fabric;

import org.apache.arrow.memory.BufferAllocator;

import com.dremio.exec.rpc.BasicClient;
import com.dremio.exec.rpc.ReconnectingConnection;
import com.dremio.exec.rpc.RpcConfig;
import com.dremio.services.fabric.proto.FabricProto.FabricHandshake;
import com.dremio.services.fabric.proto.FabricProto.FabricIdentity;

import io.netty.channel.EventLoopGroup;

/**
 * Maintains connection between two particular daemons/sockets.
 */
final class FabricConnectionManager extends ReconnectingConnection<FabricConnection, FabricHandshake> {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FabricConnectionManager.class);

  private final FabricIdentity remoteIdentity;
  private final FabricIdentity localIdentity;
  private final BufferAllocator allocator;
  private final EventLoopGroup eventLoop;
  private final FabricMessageHandler handler;
  private RpcConfig rpcConfig;

  public FabricConnectionManager(
      final RpcConfig rpcConfig,
      final BufferAllocator allocator,
      final FabricIdentity remoteIdentity,
      final FabricIdentity localIdentity,
      final EventLoopGroup eventLoop,
      final FabricMessageHandler handler) {
    super(
        rpcConfig.getName(),
        FabricHandshake.newBuilder()
          .setRpcVersion(FabricRpcConfig.RPC_VERSION)
          .setIdentity(localIdentity)
          .build(),
        remoteIdentity.getAddress(),
        remoteIdentity.getPort());
    assert remoteIdentity != null : "Identity cannot be null.";
    assert remoteIdentity.getAddress() != null && !remoteIdentity.getAddress().isEmpty(): "RemoteIdentity's server address cannot be null.";
    assert remoteIdentity.getPort() > 0 : String.format("Fabric Port must be set to a port between 1 and 65k.  Was set to %d.", remoteIdentity.getPort());

    this.rpcConfig = rpcConfig;
    this.eventLoop = eventLoop;
    this.allocator = allocator;
    this.remoteIdentity = remoteIdentity;
    this.localIdentity = localIdentity;
    this.handler = handler;
  }

  @Override
  protected BasicClient<?, FabricConnection, FabricHandshake, ?> getNewClient() {
    return new FabricClient(rpcConfig, eventLoop, allocator, remoteIdentity, localIdentity, handler, new CloseHandlerCreator());
  }


}
