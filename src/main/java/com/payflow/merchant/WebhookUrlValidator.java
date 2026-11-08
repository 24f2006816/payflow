package com.payflow.merchant;

import com.payflow.common.ApiException;
import com.payflow.config.PayflowProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Guards against Server-Side Request Forgery (OWASP API7): a merchant must not be able to make our
 * servers POST to internal addresses such as 169.254.169.254 (cloud metadata) or 10.x databases.
 * Checked at registration; the HTTP client additionally refuses redirects.
 * (A determined attacker can still use DNS rebinding; production systems send webhooks through an
 * egress proxy that re-checks the resolved IP.)
 */
@Component
public class WebhookUrlValidator {

    private final boolean allowInsecureHttp;
    private final boolean allowPrivateHosts;

    public WebhookUrlValidator(PayflowProperties props) {
        this.allowInsecureHttp = props.webhooks().allowInsecureHttp();
        this.allowPrivateHosts = props.webhooks().allowPrivateHosts();
    }

    public void validate(String url) {
        if (url == null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_webhook_url", "webhook url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !(allowInsecureHttp && "http".equalsIgnoreCase(scheme))) {
            throw ApiException.badRequest("invalid_webhook_url", "webhook url must use https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw ApiException.badRequest("invalid_webhook_url", "webhook url must have a host and no credentials");
        }
        if (allowPrivateHosts) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    throw ApiException.badRequest("invalid_webhook_url", "webhook url resolves to a private address");
                }
            }
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("invalid_webhook_url", "webhook host does not resolve");
        }
    }
}
