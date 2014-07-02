package com.odobo.grails.plugin.springsecurity.rest

import grails.plugin.springsecurity.authentication.GrailsAnonymousAuthenticationToken
import groovy.util.logging.Slf4j
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.filter.GenericFilterBean

import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * This filter starts the token validation flow. It extracts the token from the configured header name, and pass it to
 * the {@link RestAuthenticationProvider}.
 *
 * This filter, when applied, is incompatible with traditional browser-based Spring Security Core redirects. Users have
 * to make sure it's applied only to REST endpoint URL's.
 *
 * If the authentication is successful, the result is stored in the security context and the response is generated by the
 * {@link AuthenticationSuccessHandler}. Otherwise, an {@link AuthenticationFailureHandler} is called.
 */
@Slf4j
class RestTokenValidationFilter extends GenericFilterBean {

    String headerName

    RestAuthenticationProvider restAuthenticationProvider

    AuthenticationSuccessHandler authenticationSuccessHandler
    AuthenticationFailureHandler authenticationFailureHandler

    TokenReader tokenReader
    String validationEndpointUrl
    Boolean active

    Boolean enableAnonymousAccess

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        def httpRequest = request as HttpServletRequest
        def httpResponse = response as HttpServletResponse


        try {
            String tokenValue = tokenReader.findToken(httpRequest, httpResponse)
            if (tokenValue) {
                log.debug "Token found: ${tokenValue}"

                log.debug "Trying to authenticate the token"
                RestAuthenticationToken authenticationRequest = new RestAuthenticationToken(tokenValue)
                RestAuthenticationToken authenticationResult = restAuthenticationProvider.authenticate(authenticationRequest) as RestAuthenticationToken

                if (authenticationResult.authenticated) {
                    log.debug "Token authenticated. Storing the authentication result in the security context"
                    log.debug "Authentication result: ${authenticationResult}"
                    SecurityContextHolder.context.setAuthentication(authenticationResult)

                    processFilterChain(request, response, chain, tokenValue, authenticationResult)

                }

            } else {
                log.debug "Token not found"
                processFilterChain(request, response, chain, tokenValue, null)
            }
        } catch (AuthenticationException ae) {
            log.debug "Authentication failed: ${ae.message}"
            authenticationFailureHandler.onAuthenticationFailure( httpRequest, httpResponse, ae)
        }

    }

    private processFilterChain(ServletRequest request, ServletResponse response, FilterChain chain, String tokenValue, RestAuthenticationToken authenticationResult) {
        def httpRequest = request as HttpServletRequest
        def httpResponse = response as HttpServletResponse

        def actualUri = httpRequest.requestURI - httpRequest.contextPath

        if( !active ) {
            log.debug "Token validation is disabled. Continuing the filter chain"
            chain.doFilter(request, response)
            return
        }

        if( tokenValue ) {
            if (actualUri == validationEndpointUrl) {
                log.debug "Validation endpoint called. Generating response."
                authenticationSuccessHandler.onAuthenticationSuccess(httpRequest, httpResponse, authenticationResult)
            } else {
                log.debug "Continuing the filter chain"
                chain.doFilter(request, response)
            }
            return

        } else if( enableAnonymousAccess ) {

            log.debug "Anonymous access is enabled"
            Authentication authentication = SecurityContextHolder.context.authentication
            if (authentication && authentication instanceof GrailsAnonymousAuthenticationToken) {
                log.debug "Request is already authenticated as anonymous request. Continuing the filter chain"
                chain.doFilter(request, response)
                return

            } else {
                log.debug "However, request is not authenticated as anonymous"
                throw new AuthenticationCredentialsNotFoundException("Token is missing")
            }
        }

        throw new AuthenticationCredentialsNotFoundException("Token is missing")
    }
}
