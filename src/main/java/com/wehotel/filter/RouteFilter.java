/*
 *  Copyright (C) 2020 the original author or authors.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wehotel.filter;

import com.wehotel.flume.clients.log4j2appender.LogService;
import com.wehotel.legacy.RespEntity;
import com.wehotel.proxy.FizzWebClient;
import com.wehotel.util.ThreadContext;
import com.wehotel.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * @author lancer
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RouteFilter extends ProxyAggrFilter {

    private static final Logger log = LoggerFactory.getLogger(RouteFilter.class);

    @Resource
    private FizzWebClient fizzWebClient;

    @Override
    public Mono<Void> doFilter(ServerWebExchange exchange, WebFilterChain chain) {
        FilterResult pfr = WebUtils.getPrevFilterResult(exchange);
        if (pfr.success) {
            return doFilter0(exchange, chain);
        } else {
            Mono<Void> resp = WebUtils.getDirectResponse(exchange);
            if (resp == null) { // should not reach here
                ServerHttpRequest clientReq = exchange.getRequest();
                String rid = clientReq.getId();
                String msg = pfr.id + " fail";
                if (pfr.cause == null) {
                    log.error(msg, LogService.BIZ_ID, rid);
                } else {
                    log.error(msg, LogService.BIZ_ID, rid, pfr.cause);
                }
                return WebUtils.buildJsonDirectResponseAndBindContext(exchange, HttpStatus.OK, null, RespEntity.toJson(HttpStatus.INTERNAL_SERVER_ERROR.value(), msg, rid));
            } else {
                return resp;
            }
        }
    }

    private Mono<Void> doFilter0(ServerWebExchange exchange, WebFilterChain chain) {

        ServerHttpRequest clientReq = exchange.getRequest();
        String rid = clientReq.getId();
        HttpHeaders hdrs = new HttpHeaders();
        clientReq.getHeaders().forEach(
                (h, vs) -> {
                    hdrs.addAll(h, vs);
                }
        );
        Map<String, String> appendHeaders = WebUtils.getAppendHeaders(exchange);
        if (appendHeaders != null) {
            appendHeaders.forEach(
                    (h, v) -> {
                        List<String> vs = hdrs.get(h);
                        if (vs != null && !vs.isEmpty()) {
                            vs.clear();
                            vs.add(v);
                        } else {
                            hdrs.add(h, v);
                        }
                    }
            );
        }

        return fizzWebClient.proxySend2service(rid, clientReq.getMethod(), WebUtils.getServiceId(exchange), WebUtils.getRelativeUri(exchange), hdrs, exchange.getRequest().getBody()).flatMap(
                remoteResp -> {
                    ServerHttpResponse clientResp = exchange.getResponse();
                    clientResp.setStatusCode(remoteResp.statusCode());
                    HttpHeaders clientRespHeaders = clientResp.getHeaders();
                    HttpHeaders remoteRespHeaders = remoteResp.headers().asHttpHeaders();
                    remoteRespHeaders.entrySet().forEach(
                            h -> {
                                String k = h.getKey();
                                if (clientRespHeaders.containsKey(k)) {
                                    if (k.equals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) || k.equals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
                                            || k.equals(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS) || k.equals(HttpHeaders.ACCESS_CONTROL_MAX_AGE)) {
                                    } else {
                                        clientRespHeaders.put(k, h.getValue());
                                    }
                                } else {
                                    clientRespHeaders.put(k, h.getValue());
                                }
                            }
                    );
                    if (log.isDebugEnabled()) {
                        StringBuilder b = ThreadContext.getStringBuilder();
                        WebUtils.response2stringBuilder(rid, remoteResp, b);
                        log.debug(b.toString(), LogService.BIZ_ID, rid);
                    }
                    return clientResp.writeWith(remoteResp.body(BodyExtractors.toDataBuffers()))
                            .doOnError(throwable -> cleanup(remoteResp)).doOnCancel(() -> cleanup(remoteResp));
                }
        );
    }
    
    private void cleanup(ClientResponse clientResponse) {
		if (clientResponse != null) {
			clientResponse.bodyToMono(Void.class).subscribe();
		}
	}
}
