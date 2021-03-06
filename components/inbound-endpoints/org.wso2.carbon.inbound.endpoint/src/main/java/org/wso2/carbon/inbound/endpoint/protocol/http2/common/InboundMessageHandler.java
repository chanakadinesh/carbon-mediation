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

package org.wso2.carbon.inbound.endpoint.protocol.http2.common;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.axis2.dispatchers.RequestURIBasedDispatcher;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.http.HTTPTransportUtils;
import org.apache.axis2.util.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.MessageContextCreatorForAxis2;
import org.apache.synapse.inbound.InboundEndpoint;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.mediators.MediatorFaultHandler;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.apache.synapse.rest.RESTRequestHandler;
import org.apache.synapse.transport.nhttp.HttpCoreRequestResponseTransport;
import org.apache.synapse.transport.nhttp.NHttpConfiguration;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.nhttp.util.RESTUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.inbound.endpoint.osgi.service.ServiceReferenceHolder;
import org.wso2.carbon.inbound.endpoint.protocol.http.InboundHttpConstants;
import org.wso2.carbon.inbound.endpoint.protocol.http2.Http2Constants;
import org.wso2.carbon.inbound.endpoint.protocol.http2.Http2SourceRequest;
import org.wso2.carbon.inbound.endpoint.protocol.http2.InboundHttp2Configuration;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.xml.parsers.FactoryConfigurationError;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is responsible for dispatching request message to Axis2 Engine
 */

public class InboundMessageHandler {

	private static final Log log = LogFactory.getLog(InboundMessageHandler.class);
	public final Pattern dispatchPattern;
	private InboundResponseSender responseSender;
	private InboundHttp2Configuration config;
	private Matcher patternMatcher;

	public InboundMessageHandler(InboundResponseSender responseSender,
	                             InboundHttp2Configuration config) {
		this.config = config;
		if (config.getDispatchPattern() == null) {
			dispatchPattern = null;
		} else {
			dispatchPattern = Pattern.compile(config.getDispatchPattern());
		}
		this.responseSender = responseSender;
	}

	/**
	 * Create synapse message context and dispatch the sequence to Axis2 Engine
	 *
	 * @param request
	 * @throws AxisFault
	 */
	public void processRequest(Http2SourceRequest request) throws AxisFault {

		String tenantDomain = getTenantDomain(request);
		MessageContext synCtx = getSynapseMessageContext(tenantDomain);

		InboundEndpoint endpoint =
				synCtx.getConfiguration().getInboundEndpoint(this.config.getName());
		if (endpoint == null) {
			throw new AxisFault("Cannot find deployed inbound endpoint " + this.config.getName() +
			                    "for process request");
		}

		org.apache.axis2.context.MessageContext axis2MsgCtx =
				((Axis2MessageContext) synCtx).getAxis2MessageContext();
		updateMessageContext(axis2MsgCtx, request);
		synCtx.setProperty(Http2Constants.STREAM_ID, request.getStreamID());
		synCtx.setProperty(Http2Constants.STREAM_CHANNEL, request.getChannel());

		axis2MsgCtx.setProperty("OutTransportInfo", this);

		axis2MsgCtx.setProperty(Http2Constants.STREAM_ID, request.getStreamID());
		axis2MsgCtx.setProperty(Http2Constants.STREAM_CHANNEL, request.getChannel());
		if (request.getRequestType() != null) {
			axis2MsgCtx.setProperty(Http2Constants.HTTP2_REQUEST_TYPE, request.getRequestType());
		}

		axis2MsgCtx.setServerSide(true);
		axis2MsgCtx.setProperty("TransportInURL", request.getUri());
		String method = request.getMethod();
		axis2MsgCtx.setIncomingTransportName(request.getScheme());
		processHttpRequestUri(axis2MsgCtx, method, request);
		synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
		synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER,
		                   responseSender);
		axis2MsgCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER,
		                        responseSender);
		synCtx.setWSAAction(request.getHeader(InboundHttpConstants.SOAP_ACTION));
		axis2MsgCtx.setProperty(Http2Constants.HTTP2_PUSH_PROMISE_REQEUST_ENABLED,
		                        config.isEnableServerPush());
		if (config.isEnableServerPush()) {
			axis2MsgCtx.setProperty(Http2Constants.HTTP2_DISPATCH_SEQUENCE,
			                        config.getDispatchSequence());
			axis2MsgCtx.setProperty(Http2Constants.HTTP2_ERROR_SEQUENCE, config.getErrorSequence());
		}
		if (!isRESTRequest(axis2MsgCtx, method)) {
			if (request.getPipe() != null) {
				processEntityEnclosingRequest(axis2MsgCtx, false, request);
			} else {
				processNonEntityEnclosingRESTHandler(null, axis2MsgCtx, false, request);
			}
		} else {
			AxisOperation axisOperation =
					((Axis2MessageContext) synCtx).getAxis2MessageContext().getAxisOperation();
			((Axis2MessageContext) synCtx).getAxis2MessageContext().setAxisOperation(null);
			String contentTypeHeader = request.getHeader(HTTP.CONTENT_TYPE);
			SOAPEnvelope soapEnvelope = handleRESTUrlPost(contentTypeHeader, axis2MsgCtx, request);
			processNonEntityEnclosingRESTHandler(soapEnvelope, axis2MsgCtx, false, request);
			((Axis2MessageContext) synCtx).getAxis2MessageContext().setAxisOperation(axisOperation);

		}
		boolean continueDispatch = true;
		if (dispatchPattern != null) {
			patternMatcher = dispatchPattern.matcher(request.getUri());
			if (!patternMatcher.matches()) {
				if (log.isDebugEnabled()) {
					log.debug("Requested URI does not match given dispatch regular expression.");
				}
				continueDispatch = false;
			}
		}

		if (continueDispatch && dispatchPattern != null) {

			boolean processedByAPI = false;
			RESTRequestHandler restHandler = new RESTRequestHandler();
			processedByAPI = restHandler.process(synCtx);
			if (log.isDebugEnabled()) {
				log.debug("Dispatch to API state : enabled, Message is " +
				          (!processedByAPI ? "NOT" : "") + "processed by an API");
			}

			if (!processedByAPI) {
				//check the validity of message routing to axis2 path
				boolean isAxis2Path = isAllowedAxis2Path(synCtx, request, axis2MsgCtx);

				if (isAxis2Path) {
					//create axis2 message context again to avoid settings updated above
					if (!isRESTRequest(axis2MsgCtx, method)) {
						if (request.getPipe() != null) {
							processEntityEnclosingRequest(axis2MsgCtx, isAxis2Path, request);
						} else {
							processNonEntityEnclosingRESTHandler(null, axis2MsgCtx, isAxis2Path,
							                                     request);
						}
					} else {
						String contentTypeHeader = request.getHeader(HTTP.CONTENT_TYPE);
						SOAPEnvelope soapEnvelope =
								handleRESTUrlPost(contentTypeHeader, axis2MsgCtx, request);
						processNonEntityEnclosingRESTHandler(soapEnvelope, axis2MsgCtx, true,
						                                     request);
					}
				} else {
					//this case can only happen regex exists and it DOES match
					//BUT there is no api or proxy found message to be injected
					//should be routed to the main sequence instead inbound defined sequence
					injectToMainSequence(synCtx, endpoint);
				}
			}
		} else if (continueDispatch && dispatchPattern == null) {
			// else if for clarity compiler will optimize
			injectToSequence(synCtx, endpoint);
		} else {
			//this case can only happen regex exists and it DOES NOT match
			//should be routed to the main sequence instead inbound defined sequence
			injectToMainSequence(synCtx, endpoint);
		}
	}

	private static org.apache.axis2.context.MessageContext createAxis2MessageContext() {
		org.apache.axis2.context.MessageContext axis2MsgCtx =
				new org.apache.axis2.context.MessageContext();
		axis2MsgCtx.setMessageID(UIDGenerator.generateURNString());
		axis2MsgCtx.setConfigurationContext(
				ServiceReferenceHolder.getInstance().getConfigurationContextService()
				                      .getServerConfigContext());
		return axis2MsgCtx;
	}

	private void injectToSequence(MessageContext synCtx, InboundEndpoint endpoint) {

		SequenceMediator injectingSequence = null;
		if (endpoint.getInjectingSeq() != null) {
			injectingSequence = (SequenceMediator) synCtx.getSequence(endpoint.getInjectingSeq());
		}
		if (injectingSequence == null) {
			injectingSequence = (SequenceMediator) synCtx.getMainSequence();
		}
		SequenceMediator faultSequence = getFaultSequence(synCtx, endpoint);
		MediatorFaultHandler mediatorFaultHandler = new MediatorFaultHandler(faultSequence);
		synCtx.pushFaultHandler(mediatorFaultHandler);

		log.info("Injecting message to sequence : " + endpoint.getInjectingSeq());
		synCtx.setProperty("inbound.endpoint.name", endpoint.getName());
		synCtx.getEnvironment().injectMessage(synCtx, injectingSequence);
	}

	private SequenceMediator getFaultSequence(MessageContext synCtx, InboundEndpoint endpoint) {
		SequenceMediator faultSequence = null;
		if (endpoint.getOnErrorSeq() != null) {
			faultSequence = (SequenceMediator) synCtx.getSequence(endpoint.getOnErrorSeq());
		}
		if (faultSequence == null) {
			faultSequence = (SequenceMediator) synCtx.getFaultSequence();
		}
		return faultSequence;
	}

	/**
	 * creating synapse context according to the tenant domain
	 *
	 * @param tenantDomain
	 * @return
	 * @throws AxisFault
	 */
	public MessageContext getSynapseMessageContext(String tenantDomain) throws AxisFault {
		MessageContext synCtx = createSynapseMessageContext(tenantDomain);
		synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
		((Axis2MessageContext) synCtx).getAxis2MessageContext()
		                              .setProperty(SynapseConstants.IS_INBOUND, true);
		synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER,
		                   responseSender);
		((Axis2MessageContext) synCtx).getAxis2MessageContext().setProperty(
				InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
		return synCtx;
	}

	private MessageContext createSynapseMessageContext(String tenantDomain) throws AxisFault {
		org.apache.axis2.context.MessageContext axis2MsgCtx = createAxis2MessageContext();
		ServiceContext svcCtx = new ServiceContext();
		OperationContext opCtx = new OperationContext(new InOutAxisOperation(), svcCtx);
		axis2MsgCtx.setServiceContext(svcCtx);
		axis2MsgCtx.setOperationContext(opCtx);

		if (!tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
			ConfigurationContext tenantConfigCtx = TenantAxisUtils
					.getTenantConfigurationContext(tenantDomain,
					                               axis2MsgCtx.getConfigurationContext());
			axis2MsgCtx.setConfigurationContext(tenantConfigCtx);
			axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, tenantDomain);
		} else {
			axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN,
			                        MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
		}
		SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
		SOAPEnvelope envelope = fac.getDefaultEnvelope();
		axis2MsgCtx.setEnvelope(envelope);
		return MessageContextCreatorForAxis2.getSynapseMessageContext(axis2MsgCtx);
	}

	private void injectToMainSequence(MessageContext synCtx, InboundEndpoint endpoint) {

		SequenceMediator injectingSequence = (SequenceMediator) synCtx.getMainSequence();

		SequenceMediator faultSequence = getFaultSequence(synCtx, endpoint);

		MediatorFaultHandler mediatorFaultHandler = new MediatorFaultHandler(faultSequence);
		synCtx.pushFaultHandler(mediatorFaultHandler);

        /* handover synapse message context to synapse environment for inject it to given
        sequence in synchronous manner*/
		if (log.isDebugEnabled()) {
			log.debug("injecting message to sequence : " + endpoint.getInjectingSeq());
		}
		synCtx.getEnvironment().injectMessage(synCtx, injectingSequence);
	}

	private void processNonEntityEnclosingRESTHandler(SOAPEnvelope soapEnvelope,
	                                                  org.apache.axis2.context.MessageContext msgContext,
	                                                  boolean injectToAxis2Engine,
	                                                  Http2SourceRequest request) {
		String soapAction = request.getHeader(Http2Constants.Http2SoapHeader);
		if (soapAction != null && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
			soapAction = soapAction.substring(1, soapAction.length() - 1);
		}

		msgContext.setSoapAction(soapAction);
		msgContext.setTo(new EndpointReference(request.getUri()));
		msgContext.setServerSide(true);
		msgContext.setDoingREST(true);
		if (request.getPipe() == null) {
			msgContext.setProperty("NO_ENTITY_BODY", Boolean.TRUE);
		}

		try {
			if (soapEnvelope == null) {
				msgContext.setEnvelope((new SOAP11Factory()).getDefaultEnvelope());
			} else {
				msgContext.setEnvelope(soapEnvelope);
			}

			if (injectToAxis2Engine) {
				AxisEngine.receive(msgContext);
			}
		} catch (AxisFault var6) {
			log.error("AxisFault:" + var6);
		} catch (Exception var7) {
			log.error("Exception:" + var7);
		}

	}

	private boolean isAllowedAxis2Path(MessageContext synapseMsgContext, Http2SourceRequest request,
	                                   org.apache.axis2.context.MessageContext messageContext) {
		boolean isProxy = false;

		String reqUri = request.getUri();
		String tenant = MultitenantUtils.getTenantDomainFromUrl(request.getUri());
		String servicePath = messageContext.getConfigurationContext().getServicePath();

		//for tenants, service path will be appended by tenant name
		if (!reqUri.equalsIgnoreCase(tenant)) {
			servicePath = servicePath + "/t/" + tenant;
		}

		//Get the operation part from the request URL
		// e.g. '/services/TestProxy/' > TestProxy when service path is '/service/' > result 'TestProxy/'
		String serviceOpPart = Utils.getServiceAndOperationPart(reqUri, servicePath);
		//if proxy, then check whether it is deployed in the environment
		if (serviceOpPart != null) {
			isProxy = isProxyDeployed(synapseMsgContext, serviceOpPart);
		} else {
			if (log.isDebugEnabled()) {
				log.debug("Requested Proxy Service '" + serviceOpPart + "' is not deployed");
			}
		}
		return isProxy;
	}

	private boolean isProxyDeployed(MessageContext synapseContext, String serviceOpPart) {
		boolean isDeployed = false;

		//extract proxy name from serviceOperation, get the first portion split by '/'
		String proxyName = serviceOpPart.split("/")[0];

		//check whether the proxy is deployed in synapse environment
		if (synapseContext.getConfiguration().getProxyService(proxyName) != null) {
			isDeployed = true;
		}
		return isDeployed;
	}

	private void processEntityEnclosingRequest(org.apache.axis2.context.MessageContext msgContext,
	                                           boolean injectToAxis2Engine,
	                                           Http2SourceRequest request) {
		try {
			String e = request.getHeader("content-type");
			String charSetEncoding = null;
			String contentType = null;
			if (e != null) {
				charSetEncoding = BuilderUtil.getCharSetEncoding(e);
				contentType = TransportUtils.getContentType(e, msgContext);
			}

			if (charSetEncoding == null) {
				charSetEncoding = "UTF-8";
			}

			String method = request != null ? request.getMethod().toUpperCase() : "";
			msgContext.setTo(new EndpointReference(request.getUri()));
			msgContext.setProperty("HTTP_METHOD_OBJECT", method);
			msgContext.setProperty("CHARACTER_SET_ENCODING", charSetEncoding);
			msgContext.setServerSide(true);
			msgContext.setProperty("ContentType", e);
			msgContext.setProperty("messageType", contentType);
			if (e == null || HTTPTransportUtils.isRESTRequest(e) || this.isRest(e)) {
				msgContext.setProperty("synapse.internal.rest.contentType", contentType);
				msgContext.setDoingREST(true);
				SOAPEnvelope soapAction1 = this.handleRESTUrlPost(e, msgContext, request);
				msgContext.setProperty("pass-through.pipe", request.getPipe());
				this.processNonEntityEnclosingRESTHandler(soapAction1, msgContext,
				                                          injectToAxis2Engine, request);
				return;
			}

			String soapAction = (String) request.getHeader("soapaction");
			int soapVersion = HTTPTransportUtils
					.initializeMessageContext(msgContext, soapAction, request.getUri(), e);
			SOAPEnvelope envelope;
			SOAPFactory fac;
			if (soapVersion == 1) {
				fac = OMAbstractFactory.getSOAP11Factory();
				envelope = fac.getDefaultEnvelope();
			} else if (soapVersion == 2) {
				fac = OMAbstractFactory.getSOAP12Factory();
				envelope = fac.getDefaultEnvelope();
			} else {
				fac = OMAbstractFactory.getSOAP12Factory();
				envelope = fac.getDefaultEnvelope();
			}

			if (soapAction != null && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
				soapAction = soapAction.substring(1, soapAction.length() - 1);
				msgContext.setSoapAction(soapAction);
			}

			msgContext.setEnvelope(envelope);
			msgContext.setProperty("pass-through.pipe", request.getPipe());
			if (injectToAxis2Engine) {
				AxisEngine.receive(msgContext);
			}
		} catch (AxisFault var11) {
			log.error("Error processing " + request.getMethod() + " request for : " +
			          request.getUri(), var11);
		} catch (Exception var12) {
			log.error("Error processing " + request.getMethod() + " reguest for : " +
			          request.getUri() + ". Error detail: " + var12.getMessage() + ". ", var12);
		}

	}

	private boolean isRest(String contentType) {
		return contentType != null && contentType.indexOf("text/xml") == -1 &&
		       contentType.indexOf("application/soap+xml") == -1;
	}

	private boolean isRESTRequest(org.apache.axis2.context.MessageContext msgContext,
	                              String method) {
		if (msgContext.getProperty("rest_get_delete_invoke") != null &&
		    ((Boolean) msgContext.getProperty("rest_get_delete_invoke")).booleanValue()) {
			msgContext.setProperty("HTTP_METHOD_OBJECT", method);
			msgContext.setServerSide(true);
			msgContext.setDoingREST(true);
			return true;
		} else {
			return false;
		}
	}

	private void processHttpRequestUri(org.apache.axis2.context.MessageContext msgContext,
	                                   String method, Http2SourceRequest request) {
		String servicePrefixIndex = "://";
		ConfigurationContext cfgCtx = msgContext.getConfigurationContext();
		msgContext.setProperty("HTTP_METHOD", method);
		String oriUri = request.getUri();
		oriUri = "/" + oriUri;
		String restUrlPostfix = NhttpUtil.getRestUrlPostfix(oriUri, cfgCtx.getServicePath());
		String servicePrefix = oriUri.substring(0, oriUri.indexOf(restUrlPostfix));
		if (servicePrefix.indexOf(servicePrefixIndex) == -1) {
			String schema = request.getScheme();
			SocketAddress entity = request.getChannel().channel().localAddress();
			if (entity != null) {
				servicePrefix =
						schema + servicePrefixIndex.substring(0, servicePrefixIndex.length() - 1) +
						entity + servicePrefix;
			}
		}

		msgContext.setProperty("SERVICE_PREFIX", servicePrefix);
		msgContext.setTo(new EndpointReference(restUrlPostfix));
		msgContext.setProperty("REST_URL_POSTFIX", restUrlPostfix);
		if ("GET".equals(method) || "DELETE".equals(method) || "HEAD".equals(method) ||
		    "OPTIONS".equals(method)) {
			msgContext.setProperty(PassThroughConstants.REST_GET_DELETE_INVOKE, true);
		}

	}

	private SOAPEnvelope handleRESTUrlPost(String contentTypeHdr,
	                                       org.apache.axis2.context.MessageContext msgContext,
	                                       Http2SourceRequest request)
			throws FactoryConfigurationError {
		SOAPEnvelope soapEnvelope = null;
		String contentType =
				contentTypeHdr != null ? TransportUtils.getContentType(contentTypeHdr, msgContext) :
				null;
		if (contentType == null || "".equals(contentType) ||
		    "application/x-www-form-urlencoded".equals(contentType)) {
			contentType =
					contentTypeHdr != null ? contentTypeHdr : "application/x-www-form-urlencoded";
			msgContext.setTo(new EndpointReference(request.getUri()));
			msgContext.setProperty(InboundHttp2Constants.HTTP2_CONTENT_TYPE, contentType);
			String charSetEncoding = BuilderUtil.getCharSetEncoding(contentType);
			msgContext.setProperty("CHARACTER_SET_ENCODING", charSetEncoding);

			try {
				RESTUtil.dispatchAndVerify(msgContext);
			} catch (AxisFault var11) {
				log.error("Error while building message for REST_URL request", var11);
			}

			try {
				boolean e = NHttpConfiguration.getInstance().isReverseProxyMode();
				AxisService axisService = null;
				if (!e) {
					RequestURIBasedDispatcher isCustomRESTDispatcher =
							new RequestURIBasedDispatcher();
					axisService = isCustomRESTDispatcher.findService(msgContext);
				}

				boolean isCustomRESTDispatcher1 = false;
				String requestURI = request.getUri();
				if (requestURI.matches(NHttpConfiguration.getInstance().getRestUriApiRegex()) ||
				    requestURI.matches(NHttpConfiguration.getInstance().getRestUriProxyRegex())) {
					isCustomRESTDispatcher1 = true;
				}

				String multiTenantDispatchService;
				if (!isCustomRESTDispatcher1) {
					if (axisService == null) {
						multiTenantDispatchService =
								NHttpConfiguration.getInstance().getNhttpDefaultServiceName();
						axisService = msgContext.getConfigurationContext().getAxisConfiguration()
						                        .getService(multiTenantDispatchService);
						msgContext.setAxisService(axisService);
					}
				} else {
					multiTenantDispatchService =
							PassThroughConfiguration.getInstance().getRESTDispatchService();
					axisService = msgContext.getConfigurationContext().getAxisConfiguration()
					                        .getService(multiTenantDispatchService);
					msgContext.setAxisService(axisService);
				}
			} catch (AxisFault var12) {
				log.error("Error processing " + request.getMethod() + " request for : " +
				          request.getUri(), var12);
			}

			try {
				soapEnvelope = TransportUtils
						.createSOAPMessage(msgContext, (InputStream) null, contentType);
			} catch (Exception var10) {
				log.error("Error while building message for REST_URL request");
			}

			msgContext.setProperty("messageType", "application/xml");
		}

		return soapEnvelope;
	}

	private String getTenantDomain(Http2SourceRequest request) {
		String tenant = MultitenantUtils.getTenantDomainFromUrl(request.getUri());
		if (tenant.equals(request.getUri())) {
			return MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
		}
		return tenant;
	}

	private org.apache.axis2.context.MessageContext updateMessageContext(
			org.apache.axis2.context.MessageContext msgContext, Http2SourceRequest request) {
		Map excessHeaders = request.getExcessHeaders();
		if (msgContext == null) {
			msgContext = new org.apache.axis2.context.MessageContext();
		}

		if (request.getScheme() != null && (request.getScheme().equalsIgnoreCase("https")
		    ||request.getScheme().equalsIgnoreCase("https2"))) {
			msgContext.setTransportOut(msgContext.getConfigurationContext().getAxisConfiguration()
			                                     .getTransportOut("https"));
			msgContext.setTransportIn(msgContext.getConfigurationContext().getAxisConfiguration()
			                                    .getTransportIn("https"));
		} else {
			msgContext.setTransportOut(msgContext.getConfigurationContext().getAxisConfiguration()
			                                     .getTransportOut("http"));
			msgContext.setTransportIn(msgContext.getConfigurationContext().getAxisConfiguration()
			                                    .getTransportIn("http"));

		}

		msgContext.setProperty("OutTransportInfo", this);
		msgContext.setServerSide(true);
		msgContext.setProperty("TransportInURL", request.getUri());
		TreeMap<String, String> headers1 = new TreeMap<String, String>(new Comparator<String>() {
			public int compare(String o1, String o2) {
				return o1.compareToIgnoreCase(o2);
			}
		});
		Set entries = request.getHeaders().entrySet();
		Iterator netConn = entries.iterator();

		while (netConn.hasNext()) {
			Map.Entry remoteAddress = (Map.Entry) netConn.next();
			headers1.put(remoteAddress.getKey().toString(), remoteAddress.getValue().toString());
		}

		msgContext.setProperty("TRANSPORT_HEADERS", headers1);
		msgContext.setProperty("EXCESS_TRANSPORT_HEADERS", excessHeaders);
		msgContext.setProperty("RequestResponseTransportControl",
		                       new HttpCoreRequestResponseTransport(msgContext));
		return msgContext;
	}

	/**
	 * Extract content type form message context
	 *
	 * @param msgCtx
	 * @return
	 * @throws AxisFault
	 */
	public String getContentType(org.apache.axis2.context.MessageContext msgCtx) throws AxisFault {
		Boolean noEntityBody = (Boolean) msgCtx.getProperty("NO_ENTITY_BODY");

		boolean noEntityBodyResponse = false;
		if (noEntityBody != null && Boolean.TRUE == noEntityBody) {
			noEntityBodyResponse = true;
		}
		MessageFormatter formatter =
				MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgCtx);
		OMOutputFormat format = BaseUtils.getOMOutputFormat(msgCtx);
		if (!noEntityBodyResponse) {
			if ("true".equals(msgCtx.getProperty("enableMTOM"))) {
				msgCtx.setProperty("ContentType", "multipart/related");
				msgCtx.setProperty("messageType", "multipart/related");
			}
			Object contentTypeInMsgCtx = msgCtx.getProperty("ContentType");
			if (contentTypeInMsgCtx != null) {
				String fault = contentTypeInMsgCtx.toString();
				if (!fault.contains("multipart/related")) {

					return fault;
				}
			}
		}
		return formatter.getContentType(msgCtx, format, msgCtx.getSoapAction());
	}
}
