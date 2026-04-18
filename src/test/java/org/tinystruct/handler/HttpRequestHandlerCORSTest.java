package org.tinystruct.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tinystruct.system.Configuration;
import org.tinystruct.system.Settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

public class HttpRequestHandlerCORSTest {

    private Configuration<String> configuration;
    private HttpRequestHandler handler;
    private ChannelHandlerContext ctx;

    @BeforeEach
    public void setUp() {
        configuration = new Settings();
        handler = new HttpRequestHandler(configuration);
        ctx = mock(ChannelHandlerContext.class);
        io.netty.channel.ChannelFuture mockFuture = mock(io.netty.channel.ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(mockFuture);
    }

    @Test
    public void testDefaultCORSAllowAny() {
        configuration.set("cors.allowed.origins", "");
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://example.com");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        handler.channelRead0(ctx, request);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        verify(ctx).writeAndFlush(responseCaptor.capture());

        FullHttpResponse response = responseCaptor.getValue();
        String actualOrigin = response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertEquals("http://example.com", actualOrigin, "CORS origin should be echoed when no allowed origins are configured");
        assertEquals("Origin", response.headers().get(HttpHeaderNames.VARY));
    }

    @Test
    public void testPreflightResponseHeaders() {
        configuration.set("cors.allowed.methods", "GET,POST");
        configuration.set("cors.allowed.headers", "X-Custom-Header");
        configuration.set("cors.preflight.maxage", "7200");

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://example.com");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "X-Custom-Header");

        handler.channelRead0(ctx, request);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        verify(ctx).writeAndFlush(responseCaptor.capture());

        FullHttpResponse response = responseCaptor.getValue();
        assertEquals("GET,POST", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("X-Custom-Header", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("7200", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));
    }

    @Test
    public void testMultipleOriginsMatch() {
        configuration.set("cors.allowed.origins", "http://domain1.com,http://domain2.com");
        
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://domain2.com");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        handler.channelRead0(ctx, request);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        verify(ctx).writeAndFlush(responseCaptor.capture());
        
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals("http://domain2.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testMultipleOriginsNoMatch() {
        configuration.set("cors.allowed.origins", "http://domain1.com,http://domain2.com");
        
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://domain3.com");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        handler.channelRead0(ctx, request);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        verify(ctx).writeAndFlush(responseCaptor.capture());
        
        FullHttpResponse response = responseCaptor.getValue();
        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testWildcardWithCredentials() {
        configuration.set("cors.allowed.origins", "*");
        configuration.set("cors.allow.credentials", "true");
        
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://domain1.com");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        handler.channelRead0(ctx, request);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        verify(ctx).writeAndFlush(responseCaptor.capture());
        
        FullHttpResponse response = responseCaptor.getValue();
        // Should echo origin when credentials are true and wildcard is used
        assertEquals("http://domain1.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testNoOriginHeader() {
        configuration.set("cors.allowed.origins", "");
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        // No Origin header set
        request.headers().set(HttpHeaderNames.HOST, "localhost");

        handler.channelRead0(ctx, request);

        // For non-OPTIONS requests, it calls service() which we haven't fully mocked, 
        // but for this test we only care about the CORS headers which are set in channelRead0 
        // before calling service() OR during service().
        
        // Wait, HttpRequestHandler.channelRead0 handles OPTIONS early.
        // For GET, it calls service().
        // In service(), it also sets CORS headers.
        
        // Actually, if it's GET, it might fail in ApplicationManager.call if not initialized.
        // Let's use OPTIONS to avoid ApplicationManager issues in this unit test.
        
        FullHttpRequest optionsRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
        optionsRequest.headers().set(HttpHeaderNames.HOST, "localhost");
        optionsRequest.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        handler.channelRead0(ctx, optionsRequest);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        verify(ctx, atLeastOnce()).writeAndFlush(responseCaptor.capture());
        
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals("*", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(response.headers().get(HttpHeaderNames.VARY));
    }
}
