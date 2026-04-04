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

    private HttpRequestHandler handler;
    private Configuration<String> configuration;
    private ChannelHandlerContext ctx;

    @BeforeEach
    public void setUp() {
        configuration = new Settings();
        handler = new HttpRequestHandler(configuration);
        ctx = mock(ChannelHandlerContext.class);
        when(ctx.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));
    }

    @Test
    public void testDefaultCORSAllowAny() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://example.com");
        request.headers().set(HttpHeaderNames.HOST, "localhost");

        handler.channelRead0(ctx, request);

        ArgumentCaptor<FullHttpResponse> responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        // ...
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
}
