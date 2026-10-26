/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.tinystruct.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.SystemPropertyUtil;
import jakarta.activation.MimetypesFileTypeMap;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_0;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * A simple handler that serves incoming HTTP requests to send their respective
 * HTTP responses.  It also implements {@code 'If-Modified-Since'} header to
 * take advantage of browser cache, as described in
 * <a href="https://tools.ietf.org/html/rfc2616#section-14.25">RFC 2616</a>.
 *
 * <p>
 * Web browser caching works with HTTP headers as illustrated by the following
 * sample:
 * <ol>
 * <li>Request #1 returns the content of {@code /file1.txt}.</li>
 * <li>Contents of {@code /file1.txt} is cached by the browser.</li>
 * <li>Request #2 for {@code /file1.txt} does not return the contents of the
 *     file again. Rather, a 304 Not Modified is returned. This tells the
 *     browser to use the contents stored in its cache.</li>
 * <li>The server knows the file has not been modified because the
 *     {@code If-Modified-Since} date is the same as the file's last
 *     modified date.</li>
 * </ol>
 *
 * <pre>
 * Request #1 Headers
 * ===================
 * GET /file1.txt HTTP/1.1
 *
 * Response #1 Headers
 * ===================
 * HTTP/1.1 200 OK
 * Date:               Tue, 01 Mar 2011 22:44:26 GMT
 * Last-Modified:      Wed, 30 Jun 2010 21:36:48 GMT
 * Expires:            Tue, 01 Mar 2012 22:44:26 GMT
 * Cache-Control:      private, max-age=31536000
 *
 * Request #2 Headers
 * ===================
 * GET /file1.txt HTTP/1.1
 * If-Modified-Since:  Wed, 30 Jun 2010 21:36:48 GMT
 *
 * Response #2 Headers
 * ===================
 * HTTP/1.1 304 Not Modified
 * Date:               Tue, 01 Mar 2011 22:44:28 GMT
 *
 * </pre>
 */
public class HttpStaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
    public static final int HTTP_CACHE_SECONDS = 60;
    public static final String DEFAULT_STATIC_DIR = "static";
    private FullHttpRequest request;
    private static final Logger logger = Logger.getLogger(HttpStaticFileHandler.class.getName());

    /**
     * The directories whose content may be served. Empty disables static serving.
     */
    private final List<Path> baseDirs;

    public HttpStaticFileHandler() {
        this(DEFAULT_STATIC_DIR);
    }

    /**
     * @param staticDirs directory or comma/semicolon-separated directories to serve files from.
     *                   They must never be the working directory itself, otherwise the project sources,
     *                   configuration and credentials would become downloadable.
     */
    public HttpStaticFileHandler(String staticDirs) {
        this.baseDirs = resolveBaseDirs(staticDirs);
    }

    private static List<Path> resolveBaseDirs(String staticDirs) {
        Path workDir = Paths.get(SystemPropertyUtil.get("user.dir")).toAbsolutePath().normalize();
        String dirStr = (staticDirs == null || staticDirs.trim().isEmpty()) ? DEFAULT_STATIC_DIR : staticDirs.trim();
        String[] dirs = dirStr.split("[,;]");
        List<Path> list = new ArrayList<>(dirs.length);
        for (String dir : dirs) {
            dir = dir.trim();
            if (dir.isEmpty()) continue;
            Path base = workDir.resolve(dir).toAbsolutePath().normalize();
            if (workDir.startsWith(base)) {
                if (!staticDirWarned) {
                    staticDirWarned = true;
                    logger.warning("server.static.dir must be a sub-directory of the working directory; static file serving is disabled for: " + dir);
                }
                continue;
            }
            if (Files.isDirectory(base)) {
                list.add(base);
            }
        }
        return Collections.unmodifiableList(list);
    }

    private static volatile boolean staticDirWarned = false;

    /**
     * Resolves a decoded request path to a regular file inside one of {@link #baseDirs}, or returns {@code null}.
     */
    private Path sanitizeUri(String decoded) {
        if (baseDirs == null || baseDirs.isEmpty() || decoded == null || decoded.isEmpty() || decoded.charAt(0) != '/' || decoded.length() > 2048) {
            return null;
        }

        String relative = decoded.substring(1).replace('\\', '/');
        // ':' covers drive letters and NTFS alternate data streams (file::$DATA)
        if (relative.isEmpty() || relative.indexOf('\0') >= 0 || relative.indexOf(':') >= 0) {
            return null;
        }

        for (Path baseDir : baseDirs) {
            for (Path root : new Path[]{baseDir, Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()}) {
                Path requested;
                try {
                    requested = root.resolve(relative).normalize();
                } catch (InvalidPathException e) {
                    continue;
                }
                if (!requested.startsWith(baseDir) || requested.equals(baseDir)) {
                    continue;
                }

                boolean invalid = false;
                for (Path part : baseDir.relativize(requested)) {
                    String name = part.toString();
                    // Never serve hidden files or folders (.git, .env, ...), and reject Windows
                    // trailing dot/space aliases ("secret.txt." resolves to "secret.txt").
                    if (name.startsWith(".") || name.endsWith(".") || name.endsWith(" ")) {
                        invalid = true;
                        break;
                    }
                }
                if (invalid) {
                    continue;
                }

                // Resolve symlinks so a link inside the directory cannot point outside of it.
                try {
                    if (Files.isRegularFile(requested) && requested.toRealPath().startsWith(baseDir.toRealPath())) {
                        return requested;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isForbiddenPath(String decoded) {
        if (decoded == null || decoded.isEmpty() || decoded.length() > 2048) {
            return true;
        }
        if (decoded.indexOf('\0') >= 0) {
            return true;
        }
        String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        relative = relative.replace('\\', '/');
        for (String segment : relative.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProjectRootFile(String decoded) {
        if (decoded == null || decoded.isEmpty()) return false;
        String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        relative = relative.replace('\\', '/');
        if (relative.isEmpty() || relative.indexOf(':') >= 0 || relative.indexOf('\0') >= 0) return false;
        Path workDir = Paths.get(SystemPropertyUtil.get("user.dir")).toAbsolutePath().normalize();
        try {
            Path candidate = workDir.resolve(relative).normalize();
            if (candidate.startsWith(workDir) && !candidate.equals(workDir)) {
                return Files.isRegularFile(candidate);
            }
        } catch (InvalidPathException ignored) {
        }
        return false;
    }

    /**
     * Sets the Date header for the HTTP response
     *
     * @param response HTTP response
     */
    private static void setDateHeader(FullHttpResponse response) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        Calendar time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));
    }

    /**
     * Sets the Date and Cache headers for the HTTP Response
     *
     * @param response    HTTP response
     * @param fileToCache file to extract content type
     */
    private static void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        Calendar time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));

        // Add cache headers
        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime()));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(
                HttpHeaderNames.LAST_MODIFIED, dateFormatter.format(new Date(fileToCache.lastModified())));
    }

    /**
     * Sets the content type header for the HTTP Response
     *
     * @param response HTTP response
     * @param file     file to extract content type
     */
    private static void setContentTypeHeader(HttpResponse response, File file) {
        String contentType = null;
        try {
            MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap(HttpStaticFileHandler.class.getResourceAsStream("/META-INF/mime.types"));
            contentType = mimeTypesMap.getContentType(file);
        } catch (Exception e) {
            // Log or handle the exception appropriately
        }

        if (contentType == null || contentType.equalsIgnoreCase("application/octet-stream")) {
            try {
                // Fix the issue as if the name include two dots, the content type will be retrieved incorrectly.
                String name = file.getName().substring(0, file.getName().indexOf(".")) + file.getName().substring(file.getName().lastIndexOf("."));
                contentType = Files.probeContentType(Path.of(name));
            } catch (IOException e) {
                // Log or handle the exception appropriately
            }
        }

        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        this.request = request;
        if (!request.decoderResult().isSuccess()) {
            request.retain();
            ctx.fireChannelRead(request);
            return;
        }

        if (!GET.equals(request.method())) {
            request.retain();
            ctx.fireChannelRead(request);
            return;
        }

        // QueryStringDecoder strips the query string and percent-decodes the path ('+' is kept as-is).
        final String uriPath;
        try {
            uriPath = new QueryStringDecoder(request.uri()).path();
        } catch (IllegalArgumentException e) {
            request.retain();
            ctx.fireChannelRead(request);
            return;
        }

        if (uriPath == null || uriPath.isEmpty() || uriPath.equals("/")) {
            request.retain();
            ctx.fireChannelRead(request);
            return;
        }

        if ("/favicon.ico".equals(uriPath)) {
            final Path faviconPath = sanitizeUri(uriPath);
            if (faviconPath != null && Files.isRegularFile(faviconPath)) {
                // fall through to serve static favicon
            } else {
                try (InputStream stream = getClass().getResourceAsStream("/favicon.ico")) {
                    if (stream != null) {
                        ReadableByteChannel channel = Channels.newChannel(stream);
                        ByteBuffer buffer = ByteBuffer.allocate(stream.available());
                        channel.read(buffer);
                        buffer.rewind();

                        ByteBuf buf = Unpooled.wrappedBuffer(buffer);
                        FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/x-icon");
                        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());

                        sendAndCleanupConnection(ctx, response);
                        return;
                    }
                } catch (Exception e) {
                    sendError(ctx, INTERNAL_SERVER_ERROR);
                    return;
                }
            }
        }

        // Reject path traversal, illegal characters, and hidden files with 403 Forbidden
        if (isForbiddenPath(uriPath)) {
            logger.warning("Rejected forbidden file access attempt: " + uriPath);
            sendError(ctx, HttpResponseStatus.FORBIDDEN);
            return;
        }

        final Path path = sanitizeUri(uriPath);
        if (path == null || !Files.isRegularFile(path)) {
            // Forbid any direct download of files that exist in the project root directory
            if (isProjectRootFile(uriPath)) {
                logger.warning("Rejected attempt to download project root file: " + uriPath);
                sendError(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }

            request.retain();
            ctx.fireChannelRead(request);
            return;
        }

        File file = path.toFile();

        // Cache Validation
        String ifModifiedSince = request.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
            Date ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);

            // Only compare up to the second because the datetime format we send to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
            long fileLastModifiedSeconds = file.lastModified() / 1000;
            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                this.sendNotModified(ctx);
                return;
            }
        }

        // Decide whether to close the connection or not.
        final boolean keepAlive = HttpUtil.isKeepAlive(request);

        // Write the content.
        ChannelFuture sendFileFuture;
        ChannelFuture lastContentFuture;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();

            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            HttpUtil.setContentLength(response, fileLength);
            setContentTypeHeader(response, file);
            setDateAndCacheHeaders(response, file);

            if (!keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            } else if (request.protocolVersion().equals(HTTP_1_0)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            // Write the initial line and the header.
            ctx.write(response);

            if (ctx.pipeline().get(SslHandler.class) == null) {
                sendFileFuture =
                        ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise());
                // Write the end marker.
                lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                sendFileFuture =
                        ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                                ctx.newProgressivePromise());
                // HttpChunkedInput will write the end marker (LastHttpContent) for us.
                lastContentFuture = sendFileFuture;
            }
        } catch (FileNotFoundException ignore) {
            this.sendError(ctx, NOT_FOUND);
            return;
        }

        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
                if (total < 0) { // total unknown
                    System.err.println(future.channel() + " Transfer progress: " + progress);
                } else {
                    System.err.println(future.channel() + " Transfer progress: " + progress + " / " + total);
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future) {
                System.err.println(future.channel() + " Transfer complete.");
            }
        });

        // Decide whether to close the connection or not.
        if (!keepAlive) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.log(Level.SEVERE, "Exception caught!", cause);
        if (ctx.channel().isActive()) {
            sendError(ctx, INTERNAL_SERVER_ERROR);
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        this.sendAndCleanupConnection(ctx, response);
    }

    /**
     * When file timestamp is the same as what the browser is sending up, send a "304 Not Modified"
     *
     * @param ctx Context
     */
    private void sendNotModified(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED, Unpooled.EMPTY_BUFFER);
        setDateHeader(response);

        this.sendAndCleanupConnection(ctx, response);
    }

    /**
     * If Keep-Alive is disabled, attaches "Connection: close" header to the response
     * and closes the connection after the response being sent.
     */
    private void sendAndCleanupConnection(ChannelHandlerContext ctx, FullHttpResponse response) {
        final FullHttpRequest request = this.request;
        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        if (!keepAlive) {
            // We're going to close the connection as soon as the response is sent,
            // so we should also make it clear for the client.
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        } else if (request.protocolVersion().equals(HTTP_1_0)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        ChannelFuture flushPromise = ctx.writeAndFlush(response);

        if (!keepAlive) {
            // Close the connection as soon as the response is sent.
            flushPromise.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
