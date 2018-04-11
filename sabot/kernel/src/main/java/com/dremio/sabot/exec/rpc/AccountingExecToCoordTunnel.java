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
package com.dremio.sabot.exec.rpc;

import com.dremio.exec.proto.CoordExecRPC.FragmentStatus;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.sabot.op.screen.QueryWritableBatch;
import com.dremio.sabot.threads.SendingMonitor;

/**
 * Wrapper around a {@link com.dremio.sabot.rpc.user.UserRPCServer.UserClientConnection} that tracks the status of batches
 * sent to User.
 */
public class AccountingExecToCoordTunnel {
  private final ExecToCoordTunnel tunnel;
  private final SendingMonitor sendMonitor;
  private final RpcOutcomeListener<Ack> statusHandler;

  public AccountingExecToCoordTunnel(ExecToCoordTunnel tunnel, SendingMonitor sendMonitor, RpcOutcomeListener<Ack> statusHandler) {
    this.tunnel = tunnel;
    this.sendMonitor = sendMonitor;
    this.statusHandler = statusHandler;
  }

  public void sendData(QueryWritableBatch data) {
    sendMonitor.increment();
    tunnel.sendData(statusHandler, data);
  }

  public void sendFragmentStatus(FragmentStatus status){
    sendMonitor.increment();
    tunnel.sendFragmentStatus(statusHandler, status);
  }

}
