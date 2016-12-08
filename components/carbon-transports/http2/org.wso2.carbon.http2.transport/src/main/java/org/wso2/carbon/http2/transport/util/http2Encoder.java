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

package org.wso2.carbon.http2.transport.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http2.DecoratingHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2DataWriter;
import org.apache.http.nio.ContentEncoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by chanakabalasooriya on 11/25/16.
 */
public class http2Encoder implements ContentEncoder {
    ChannelHandlerContext chContext;
    int streamId;
    Http2ConnectionEncoder encoder;
    ChannelPromise promise;
    boolean isComplete=false;
    public http2Encoder(ChannelHandlerContext chContext,int streamId,Http2ConnectionEncoder encoder,ChannelPromise promise) {
        this.chContext=chContext;
        this.streamId=streamId;
        this.encoder=encoder;
        this.promise=promise;

    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int l=0;
        //channel.newPromise();


        while (src.hasRemaining()){
            byte [] b;//= new byte[chContext.channel().alloc().buffer().capacity()];
          //  if(src.remaining()<b.length){
                b=new byte[src.remaining()];
                src.get(b);
               // request.replace(Unpooled.wrappedBuffer(b));
                if(src.hasRemaining())
                    encoder.writeData(chContext,streamId,Unpooled.wrappedBuffer(b),0,false,promise);
                else {
                    encoder.writeData(chContext,streamId, Unpooled.wrappedBuffer(b),0,true,promise);
                    isComplete = true;
                }

            /*}else{
                src.get(b,0,b.length);
                encoder.writeData(chContext,streamId,Unpooled.wrappedBuffer(b),0,false,promise);
            }*/
        }

        return src.position();
    }
    @Override
    public void complete() throws IOException {

    }

    @Override
    public boolean isCompleted() {
        return isComplete;
    }
}