package com.embabel.publisher.servlet;

import com.embabel.publisher.realm.RealmEndpoint;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Answers everything at and below the publisher path, and passes every other request on untouched.
 * <p>
 * A plain servlet filter, not a controller, so the protocol is served the same way on every Spring
 * MVC line, and so that when publication is off every path below the publisher answers
 * {@code PUBLISHER_DISABLED}, including paths no controller would map. It authenticates the world
 * itself, so it needs no Spring Security.
 */
public class RealmServletFilter implements Filter {

    private final String path;
    private final Supplier<RealmEndpoint> endpoint;

    /**
     * @param path     the publisher path below the context path, such as {@code /embabel}
     * @param endpoint resolved on first use, so the realm can be built after the filter is registered
     */
    public RealmServletFilter(String path, Supplier<RealmEndpoint> endpoint) {
        this.path = path;
        this.endpoint = endpoint;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        if (!uri.equals(path) && !uri.startsWith(path + "/")) {
            chain.doFilter(req, res);
            return;
        }
        RealmEndpoint.Response answer = endpoint.get().handle(new RealmEndpoint.Request(
                request.getMethod(), uri.substring(path.length()), request::getHeader, request.getInputStream().readAllBytes()));
        response.setStatus(answer.status());
        answer.headers().forEach(response::setHeader);
        if (answer.contentType() != null) {
            response.setContentType(answer.contentType());
            response.setCharacterEncoding("UTF-8");
        }
        response.setContentLength(answer.body().length);
        response.getOutputStream().write(answer.body());
    }
}
