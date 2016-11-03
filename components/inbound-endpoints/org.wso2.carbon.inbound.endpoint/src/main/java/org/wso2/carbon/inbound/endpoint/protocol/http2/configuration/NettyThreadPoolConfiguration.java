/*
 *   Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.inbound.endpoint.protocol.http2.configuration;

import org.apache.log4j.Logger;

public class NettyThreadPoolConfiguration {

    private static final Logger log = Logger.getLogger(
            org.wso2.carbon.inbound.endpoint.protocol.http2.configuration.NettyThreadPoolConfiguration.class);

    private int bossThreadPoolSize;
    private int workerThreadPoolSize;

    public NettyThreadPoolConfiguration(String bossThreadPoolSize, String workerThreadPoolSize) {

        try {
            if (bossThreadPoolSize != null && bossThreadPoolSize.trim() != "") {
                this.bossThreadPoolSize = Integer.parseInt(bossThreadPoolSize);
            } else {
                this.bossThreadPoolSize = Runtime.getRuntime().availableProcessors();
            }
            if (workerThreadPoolSize != null && workerThreadPoolSize.trim() != "") {
                this.workerThreadPoolSize = Integer.parseInt(workerThreadPoolSize);
            } else {
                this.workerThreadPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            }
        } catch (Exception e) {
            log.error("failed to validate the Netty thread pool configuration", e);
        }
    }

    public int getBossThreadPoolSize() {
        return bossThreadPoolSize;
    }

    public int getWorkerThreadPoolSize() {
        return workerThreadPoolSize;
    }

}
