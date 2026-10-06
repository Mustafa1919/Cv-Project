package com.mstech.vitrin.gateway.edge;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommitHookResponse extends HttpServletResponseWrapper {
    private final Runnable callback;
    private final AtomicBoolean invoked = new AtomicBoolean();

    CommitHookResponse(HttpServletResponse response, Runnable callback) {
        super(response);
        this.callback = callback;
    }

    void runHook() {
        if (invoked.compareAndSet(false, true)) {
            callback.run();
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        runHook();
        return super.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        runHook();
        return super.getWriter();
    }

    @Override
    public void flushBuffer() throws IOException {
        runHook();
        super.flushBuffer();
    }

    @Override
    public void sendError(int status) throws IOException {
        runHook();
        super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        runHook();
        super.sendError(status, message);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        runHook();
        super.sendRedirect(location);
    }

    @Override
    public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        runHook();
        super.sendRedirect(location, clearBuffer);
    }

    @Override
    public void sendRedirect(String location, int status) throws IOException {
        runHook();
        super.sendRedirect(location, status);
    }

    @Override
    public void sendRedirect(String location, int status, boolean clearBuffer) throws IOException {
        runHook();
        super.sendRedirect(location, status, clearBuffer);
    }
}
