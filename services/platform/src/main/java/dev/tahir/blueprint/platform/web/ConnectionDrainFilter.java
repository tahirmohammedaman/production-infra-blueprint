package dev.tahir.blueprint.platform.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds {@code Connection: close} to every response once the instance is draining. Tomcat honours
 * the header: it sends the response and then closes the connection, so the client learns the
 * connection is finished from the response itself instead of from a reset on its next request.
 *
 * <p>Set before the chain runs, because a header added after the body starts streaming is lost.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConnectionDrainFilter extends OncePerRequestFilter {

    private final ConnectionDrain drain;

    public ConnectionDrainFilter(ConnectionDrain drain) {
        this.drain = drain;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (drain.isDraining()) {
            response.setHeader(HttpHeaders.CONNECTION, "close");
        }
        chain.doFilter(request, response);
    }
}
