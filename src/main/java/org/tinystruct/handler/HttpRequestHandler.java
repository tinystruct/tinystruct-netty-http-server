package org.tinystruct.handler;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.util.CharsetUtil;
import org.tinystruct.ApplicationContext;
import org.tinystruct.ApplicationException;
import org.tinystruct.application.Context;
import org.tinystruct.data.component.Builder;
import org.tinystruct.http.*;
import org.tinystruct.http.Cookie;
import org.tinystruct.http.security.JWTManager;
import org.tinystruct.mcp.MCPPushManager;
import org.tinystruct.mcp.MCPSpecification;
import org.tinystruct.system.ApplicationManager;
import org.tinystruct.system.Configuration;
import org.tinystruct.system.Language;
import org.tinystruct.system.annotation.Action;
import org.tinystruct.system.annotation.Action.Mode;
import org.tinystruct.system.util.StringUtilities;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.tinystruct.Application.LANGUAGE;
import static org.tinystruct.http.Constants.*;
import static org.tinystruct.http.Header.SET_COOKIE;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static {
        DiskFileUpload.deleteOnExitTemporaryFile = true; // should delete file
        // on exit (in normal
        // exit)
        DiskFileUpload.baseDirectory = null; // system temp directory
        DiskAttribute.deleteOnExitTemporaryFile = true; // should delete file on
        // exit (in normal exit)
        DiskAttribute.baseDirectory = null; // system temp directory
    }

    private static final Logger logger = Logger.getLogger(HttpRequestHandler.class.getName());
    private final Configuration<String> configuration;

    public HttpRequestHandler(Configuration<String> configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest original) {
        String origin = original.headers().get(HttpHeaderNames.ORIGIN);
        // Allow origins: prefer explicit setting, otherwise echo Origin or wildcard
        String allowOrigin = getAllowOrigin(origin);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        if (allowOrigin != null) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowOrigin);
        }
        // Make responses vary by Origin when echoing it
        if (origin != null) {
            response.headers().set(HttpHeaderNames.VARY, "Origin");
        }

        // Allow credentials if explicitly enabled in settings
        if ("true".equalsIgnoreCase(configuration.get("cors.allow.credentials"))) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }

        // Expose specific headers for clients to read (e.g. MCP session ID)
        String exposeHeaders = configuration.getOrDefault("cors.exposed.headers", MCPSpecification.Http.SESSION_ID + "," + MCPSpecification.Http.CONVERSATION_ID);
        response.headers().set("Access-Control-Expose-Headers", exposeHeaders);

        // Handle CORS preflight (OPTIONS) requests up-front: these have no body.
        if (original.method() == HttpMethod.OPTIONS) {
            // CORS preflight handling with configurability
            String acrMethod = original.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
            String acrHeaders = original.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);

            // Allow methods: prefer configured list, otherwise echo requested or use
            // sensible defaults
            String allowMethods = configuration.getOrDefault("cors.allowed.methods",
                    acrMethod != null ? acrMethod : "GET,POST,PUT,DELETE,OPTIONS,PATCH");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, allowMethods);

            // Allow headers: prefer configured list, otherwise echo requested or common headers
            String allowHeaders = configuration.getOrDefault("cors.allowed.headers", acrHeaders != null ? acrHeaders : "Content-Type,Authorization");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, allowHeaders);

            // Cache the preflight response for a configurable duration (seconds)
            String maxAge = configuration.getOrDefault("cors.preflight.maxage", "3600");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, maxAge);

            response.setStatus(HttpResponseStatus.NO_CONTENT);
            response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Enforce server name restriction if configured
        String configuredServerName = configuration.get("server.name");
        if (configuredServerName != null && !configuredServerName.trim().isEmpty()) {
            String hostHeader = original.headers().get(HttpHeaderNames.HOST);
            boolean hostAllowed = false;
            if (hostHeader != null) {
                for (String allowed : configuredServerName.split(",")) {
                    if (hostHeader.equalsIgnoreCase(allowed.trim())) {
                        hostAllowed = true;
                        break;
                    }
                }
            }
            if (!hostAllowed) {
                logger.warning("Rejected request: Host header '" + hostHeader + "' does not match any configured server.name '" + configuredServerName.trim() + "'");
                sendErrorResponse(ctx, response, "Bad Request: Invalid server name.", allowOrigin);
                return;
            }
        }

        // Decide whether to close the connection or not.
        boolean keepAlive = HttpUtil.isKeepAlive(original);
        boolean ssl = Boolean.parseBoolean(configuration.getOrDefault("ssl.enabled", "false"));

        Request<FullHttpRequest, Object> request = new RequestBuilder(original, ssl);
        Context context = new ApplicationContext();
        context.setId(request.getSession().getId());

        // Compute CORS headers FIRST — they must be present on every response,
        // including error responses returned before any further processing.
        if (!authenticateRequest(request, context)) {
            response.setStatus(HttpResponseStatus.UNAUTHORIZED);
            sendErrorResponse(ctx, response, "Invalid or expired token.", allowOrigin);
            return;
        }
        this.service(ctx, context, request, new ResponseBuilder(response, ctx), keepAlive);
    }

    private String getAllowOrigin(String origin) {
        // Get the configured allowed origins.
        String allowedOrigins = configuration.get("cors.allowed.origins");

        if (allowedOrigins == null || allowedOrigins.trim().isEmpty()) {
            return origin != null ? origin : "*";
        }

        if ("*".equals(allowedOrigins)) {
            // If credentials are allowed, we MUST echo the origin instead of returning "*"
            if ("true".equalsIgnoreCase(configuration.get("cors.allow.credentials"))) {
                return origin != null ? origin : "*";
            }
            return "*";
        }

        if (origin != null) {
            String[] origins = allowedOrigins.split(",");
            for (String allowed : origins) {
                if (origin.equalsIgnoreCase(allowed.trim())) {
                    return origin;
                }
            }
        }

        return null;
    }

    private void service(final ChannelHandlerContext ctx, final Context context, final Request<FullHttpRequest, Object> request,
                         ResponseBuilder response, boolean keepAlive) {
        String[] parameterNames = request.parameterNames();
        for (String parameter : parameterNames) {
            if (parameter.startsWith("--")) {
                context.setAttribute(parameter, request.getParameter(parameter));
            }
        }

        String host = request.headers().get(Header.HOST).toString();
        Object message;
        try {
            String lang = request.getParameter("lang");
            if (lang != null && !lang.trim().isEmpty()) {
                String name = lang.replace('-', '_');

                if (Language.support(name) && !lang.equalsIgnoreCase(this.configuration.get("language"))) {
                    context.setAttribute(LANGUAGE, name);
                }
            }

            String url_prefix = "/";
            if (this.configuration.get("default.url_rewrite") != null && !"enabled".equalsIgnoreCase(this.configuration.get("default.url_rewrite"))) {
                url_prefix = "/?q=";
            }

            String hostName;
            if ((hostName = this.configuration.get("default.hostname")) != null) {
                if (hostName.length() <= 3) {
                    hostName = host;
                }
            } else {
                hostName = host;
            }

            String http_protocol = "http://";
            if (request.isSecure()) {
                http_protocol = "https://";
            }

            context.setAttribute(HTTP_HOST, http_protocol + hostName + url_prefix);
            context.setAttribute(HTTP_REQUEST, request);
            context.setAttribute(HTTP_RESPONSE, response);

            // Check if this is an SSE request
            if (isSSE(request)) {
                handleSSE(ctx, request, response, context);
                return;
            }

            String query = request.query();
            if (query != null && query.length() > 1) {
                Mode mode = Mode.fromName(request.method().name());
                query = StringUtilities.htmlSpecialChars(query);
                if (null == (message = ApplicationManager.call(query, context, mode))) {
                    message = "No response retrieved!";
                } else if (message instanceof Response) {
                    // Write the response.
                    ChannelFuture future = ctx.writeAndFlush(((Response) message).get());
                    // Close the connection after the write operation is done if necessary.
                    if (!keepAlive) {
                        future.addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                }
            } else {
                message = ApplicationManager.call(this.configuration.getOrDefault("default.home.page", "say/Praise the Lord."), context, Action.Mode.HTTP_GET);
            }
        } catch (ApplicationException e) {
            StackTraceElement[] trace = e.getStackTrace();
            message = e.getMessage();
            if (trace.length > 0 && null != e.getCause()) {
                message = e.getCause().toString();
            }

            response.setStatus(Objects.requireNonNull(ResponseStatus.valueOf(e.getStatus())));
        }

        ByteBuf resp;
        try {
            if (message instanceof byte[]) {
                resp = copiedBuffer((byte[]) message);
            } else {
                resp = copiedBuffer(message.toString(), CharsetUtil.UTF_8);
            }
        } catch (Exception e) {
            resp = copiedBuffer(e.getMessage(), CharsetUtil.UTF_8);
        }

        FullHttpResponse replacement = response.get().replace(resp);
        response = new ResponseBuilder(replacement, ctx);
        boolean sessionCookieExists = false;
        for (Cookie cookie : request.cookies()) {
            if (cookie.name().equalsIgnoreCase(JSESSIONID)) {
                sessionCookieExists = true;
                break;
            }
        }

        if (!sessionCookieExists) {
            Cookie cookie = new CookieImpl(JSESSIONID);
            if (host.contains(":"))
                cookie.setDomain(host.substring(0, host.indexOf(":")));
            cookie.setValue(context.getId());
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(-1);

            response.addHeader(SET_COOKIE.name(), cookie);
        }

        if (!response.headers().contains(Header.CONTENT_TYPE))
            response.setContentType("text/html; charset=UTF-8");

        switch (response.status()) {
            case TEMPORARY_REDIRECT:
            case MOVED_PERMANENTLY:
            case PERMANENT_REDIRECT:
                keepAlive = false;
                break;
            default:
                response.addHeader(Header.CONTENT_LENGTH.name(), resp.readableBytes());
                break;
        }

        // Write the response.
        ChannelFuture future = ctx.writeAndFlush(response.get());
        // Close the connection after the write operation is done if necessary.
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private boolean authenticateRequest(Request<FullHttpRequest, Object> request, Context context) {
        Object authorization;
        if ((authorization = request.headers().get(Header.AUTHORIZATION)) != null) {
            String authHeader = authorization.toString();
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);

                String secret = configuration.get("jwt.secret");
                if (secret == null || secret.trim().isEmpty()) {
                    // jwt.secret is not configured — cannot validate Bearer token.
                    // Log a warning and reject the request to avoid using a weak/empty key.
                    logger.log(Level.WARNING, "jwt.secret is not configured. " +
                            "Bearer token authentication is disabled. " +
                            "Please set jwt.secret (>= 256-bit) in application.properties.");
                    return false;
                }

                JWTManager jwtManager = new JWTManager();
                jwtManager.withBase64Secret(secret);

                String timezone = configuration.get("jwt.timezone");
                if (timezone != null && !timezone.trim().isEmpty()) {
                    try {
                        jwtManager.withTimezone(timezone);
                    } catch (NumberFormatException e) {
                        logger.log(Level.WARNING, "Invalid jwt.timezone value: " + timezone);
                    }
                }

                try {
                    Jws<Claims> claims = jwtManager.parseToken(token);
                    context.setAttribute("CLAIMS", claims);
                    return true;
                } catch (JwtException e) {
                    // Log authentication failure
                    logger.log(Level.WARNING, "JWT validation failed: " + e.getMessage());
                    return false;
                }
            }
        }
        return true; // Allow requests without a token
    }

    private boolean isSSE(Request<FullHttpRequest, Object> request) {
        Object acceptHeader = request.headers().get(Header.ACCEPT);
        return acceptHeader != null && acceptHeader.toString().contains("text/event-stream");
    }

    /**
     * Helper to select the appropriate push manager based on isMCP flag.
     */
    private SSEPushManager getAppropriatePushManager(boolean isMCP) {
        return isMCP ? MCPPushManager.getInstance() : SSEPushManager.getInstance();
    }

    private void handleSSE(final ChannelHandlerContext ctx, final Request<FullHttpRequest, Object> request,
                           Response<FullHttpResponse, FullHttpResponse> response, final Context context) {
        String query = request.query();

        // Guard against null/empty query before any processing
        if (query == null || query.trim().isEmpty()) {
            ByteBuf respBuf = copiedBuffer("Bad Request: missing query.", CharsetUtil.UTF_8);
            FullHttpResponse errResponse = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST, respBuf);
            errResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            errResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, respBuf.readableBytes());
            ctx.writeAndFlush(errResponse).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Sanitize query before passing to ApplicationManager (mirrors service() path)
        final String sanitizedQuery = StringUtilities.htmlSpecialChars(query);

        try {
            // 1. Execute the action first — mirrors HttpServer.handleSSE ordering
            Mode mode = Mode.fromName(request.method().name());
            Object call = ApplicationManager.call(sanitizedQuery, context, mode);

            // Use parsed 'q' parameter for isMCP detection instead of raw query string
            boolean isMCP = MCPSpecification.Endpoints.SSE.equals(query)
                    || MCPSpecification.Endpoints.SSE.equals(sanitizedQuery);

            String sessionId = context.getId();
            SSEPushManager pushManager = getAppropriatePushManager(isMCP);

            // 2. Attempt to register this channel as the persistent SSE stream for this session.
            //    register() returns non-null only on the FIRST call for a given sessionId.
            Object registration = pushManager.register(sessionId, response);

            final boolean isNew = (registration != null);
            if (isNew) {
                // First connection for this session: set SSE headers and keep the channel open.
                response.addHeader(Header.CONTENT_TYPE.name(), "text/event-stream; charset=utf-8");
                response.addHeader(Header.CACHE_CONTROL.name(), "no-cache");
                response.addHeader(Header.CONNECTION.name(), "keep-alive");
                response.addHeader(Header.TRANSFER_ENCODING.name(), "chunked");
                response.addHeader("X-Accel-Buffering", "no");

                // Write headers-only response to establish the chunked stream, then push
                // the first event only after the headers have been flushed to the client.
                // This prevents the DefaultHttpContent chunk from racing ahead of the headers.
                HttpResponse initialResponse = new DefaultHttpResponse(HTTP_1_1, OK);
                initialResponse.headers().setAll(response.get().headers());

                ctx.writeAndFlush(initialResponse).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        pushToManager(pushManager, sessionId, call);
                    } else {
                        logger.log(Level.WARNING, "SSE header flush failed for session: " + sessionId, future.cause());
                        pushManager.remove(sessionId);
                    }
                });
            } else {
                // registration == null: a persistent stream already exists for this session.
                // Push the result through the registered persistent channel immediately —
                // in Netty mode SSEPushManager.push() writes a DefaultHttpContent directly
                // to the persistent channel's ctx. Nothing is written on this connection.
                pushToManager(pushManager, sessionId, call);

                // 5. Return the result as a normal JSON response for this specific request and close it
                ByteBuf respBuf = copiedBuffer(String.valueOf(call), CharsetUtil.UTF_8);
                FullHttpResponse fullResponse = response.get().replace(respBuf);
                fullResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
                fullResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, respBuf.readableBytes());

                // Ensure this specific request's connection is closed after flushing
                ctx.writeAndFlush(fullResponse).addListener(ChannelFutureListener.CLOSE);
            }
        } catch (ApplicationException e) {
            ByteBuf respBuf = copiedBuffer(e.getMessage(), CharsetUtil.UTF_8);
            FullHttpResponse fullResponse = new DefaultFullHttpResponse(HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR, respBuf);
            fullResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            fullResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, respBuf.readableBytes());
            ctx.writeAndFlush(fullResponse).addListener(ChannelFutureListener.CLOSE);
            logger.log(Level.WARNING, "SSE Application Exception: " + e.getMessage(), e);
        }
    }

    private void pushToManager(SSEPushManager pushManager, String sessionId, Object call) {
        if (call instanceof Builder) {
            pushManager.push(sessionId, (Builder) call);
        } else if (call instanceof String) {
            Builder builder = new Builder();
            try {
                builder.parse((String) call);
                pushManager.push(sessionId, builder);
            } catch (ApplicationException ignore) {
                // If not a JSON builder, push as raw text if supported or ignore
            }
        } else if (call != null) {
            logger.log(Level.WARNING, "pushToManager: unhandled call result type ''{0}'' for session ''{1}'' — result discarded.",
                    new Object[]{call.getClass().getName(), sessionId});
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, FullHttpResponse response, String message,
                                   String allowOrigin) {
        ByteBuf content = copiedBuffer(message, CharsetUtil.UTF_8);
        FullHttpResponse fullResponse = response.replace(content);
        fullResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        fullResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        // CORS header must be present even on error responses so the browser
        // can read the status code and body instead of reporting a CORS failure.
        if (allowOrigin != null && !allowOrigin.isEmpty()) {
            fullResponse.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowOrigin);
        }
        ctx.writeAndFlush(fullResponse).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

}