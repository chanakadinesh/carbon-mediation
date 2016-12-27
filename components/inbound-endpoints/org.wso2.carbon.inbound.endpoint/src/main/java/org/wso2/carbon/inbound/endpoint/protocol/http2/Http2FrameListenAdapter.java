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

package org.wso2.carbon.inbound.endpoint.protocol.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Listen to Http2 Events and Wrap the with appropriate frame
 */
public class Http2FrameListenAdapter extends Http2EventAdapter {

	private final Log log = LogFactory.getLog(Http2FrameListenAdapter.class);

	@Override
	public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
	                      boolean endOfStream) throws Http2Exception {
		if (log.isDebugEnabled()) {
			log.debug("Http2FrameListenAdapter.onDataRead()");
		}
		DefaultHttp2DataFrame frame = new DefaultHttp2DataFrame(data, endOfStream, padding);
		frame.setStreamId(streamId);
		ctx.fireChannelRead(frame);
		return super.onDataRead(ctx, streamId, data, padding, endOfStream);
	}

	@Override
	public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
	                          int streamDependency, short weight, boolean exclusive, int padding,
	                          boolean endStream) throws Http2Exception {
		if (log.isDebugEnabled())
			log.debug("Http2FrameListenAdapter.onHeadersRead()");

		DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(headers, endStream, padding);
		frame.setStreamId(streamId);
		ctx.fireChannelRead(frame);
		super.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding,
		                    endStream);
	}

	@Override
	public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
	                          int padding, boolean endStream) throws Http2Exception {

		if (log.isDebugEnabled())
			log.debug("Http2FrameListenAdapter.onHeadersRead()");

		DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(headers, endStream, padding);
		frame.setStreamId(streamId);
		ctx.fireChannelRead(frame);
		super.onHeadersRead(ctx, streamId, headers, padding, endStream);
	}

	@Override
	public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
			throws Http2Exception {
		if (log.isDebugEnabled())
			log.debug("Http2FrameListenAdapter.onSettingRead()");
		ctx.fireChannelRead(settings);
	}

	@Override
	public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
			throws Http2Exception {
		ctx.fireChannelRead(new DefaultHttp2ResetFrame(errorCode).setStreamId(streamId));
	}

	@Override
	public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode,
	                         ByteBuf debugData) throws Http2Exception {
		ctx.fireChannelRead(new DefaultHttp2GoAwayFrame(errorCode).replace(debugData)
		                                                          .setExtraStreamIds(lastStreamId));
	}
}
