package oauth2.grantflow.authorizationcode

import oauth2.client.OAuth2ClientAuthenticationManager
import oauth2.client.OAuth2ClientRegistrationManager
import oauth2.code.AuthorizationCode
import oauth2.code.OAuth2AuthorizationCodeManager
import oauth2.context.OAuth2ContextManager
import oauth2.exception.OAuth2ClientAuthenticationFailedException
import oauth2.exception.OAuth2ClientNotRegisteredException
import oauth2.exception.OAuth2ContextNotSetupException
import oauth2.grantflow.OAuth2Grant
import oauth2.request.*
import oauth2.response.*
import oauth2.response.Code
import oauth2.response.Scope
import oauth2.response.State
import oauth2.token.OAuth2TokenManager
import org.koin.core.KoinComponent

typealias AuthorizationRequest = OAuth2AuthorizationCodeGrantRequest.AuthorizationRequest
typealias TokenRequest = OAuth2AuthorizationCodeGrantRequest.TokenRequest
typealias AuthorizationResponse = OAuth2AuthorizationCodeGrantResponse.AuthorizationResponse
typealias TokenResponse = OAuth2AuthorizationCodeGrantResponse.TokenResponse

class OAuth2AuthorizationCodeGrant(
    private val clientRegistrationManager: OAuth2ClientRegistrationManager,
    private val clientAuthenticationManager: OAuth2ClientAuthenticationManager,
    private val codeManager: OAuth2AuthorizationCodeManager,
    private val tokenManager: OAuth2TokenManager,
    private val contextManager: OAuth2ContextManager
): OAuth2Grant<OAuth2AuthorizationCodeGrantRequest, OAuth2AuthorizationCodeGrantResponse>, KoinComponent {

    override fun flow(
        request: OAuth2AuthorizationCodeGrantRequest
    ): OAuth2AuthorizationCodeGrantResponse {
        return when (request) {
            is AuthorizationRequest -> this.handleAuthorizationRequest(request)
            is TokenRequest -> this.handleTokenRequest(request)
        }
    }

    private fun handleAuthorizationRequest(
        request: AuthorizationRequest
    ): AuthorizationResponse {

        // validate request parameters
        request.validate()

        // TODO: check if resource owner is authenticated

        // TODO: check if resource owner's approval is obtained

        // retrieve client
        val client = clientRegistrationManager.retrieveClient(clientId = request.clientId.value)

        // client not registered
        client ?: throw OAuth2ClientNotRegisteredException(clientId = request.clientId.value)

        // validate response type
        client.validateResponseType(request.responseType)

        // validate and resolve redirect uri
        val redirectUri = client.validateAndResolveRedirectUri(redirectUri = request.redirectUri)

        // generate authorization code
        val code = codeManager.issueAuthorizationCode(request)

        // save context
        val context = contextManager.saveContext(request, property = Pair(AuthorizationCode.NAME, code))

        // create new Authorization Response and return it
        return AuthorizationResponse(
            context = context,
            redirectUri = redirectUri,
            code = Code(code),
            state = State(request.state)
        )
    }

    private fun handleTokenRequest(request: TokenRequest): TokenResponse {

        // validate request parameters
        request.validate()

        // retrieve context
        val context = contextManager.retrieveContext(request)
        context ?: throw OAuth2ContextNotSetupException()

        // extract client information from request
        val clientId = request.clientId.value
        val clientSecret = request.clientCredential.clientSecret
        val redirectUri = request.redirectUri.value

        // retrieve client
        val client = clientRegistrationManager.retrieveClient(clientId = clientId)
        client ?: throw OAuth2ClientNotRegisteredException(clientId = clientId)

        // authenticate client
        val authenticated = clientAuthenticationManager.authenticate(
            client = client,
            clientSecret = clientSecret,
            redirectUri = redirectUri
        )
        if(!authenticated) {
            throw OAuth2ClientAuthenticationFailedException(clientId = clientId)
        }

        // consume authorization code
        codeManager.consumeAuthorizationCode(value = request.code.value!!) // not null after validation

        // generate tokens
        val accessToken = tokenManager.generateAccessToken()
        val refreshToken = tokenManager.generateRefreshToken()

        // create new Token Response and return it
        return TokenResponse(
            context = context,
            accessToken = AccessToken(accessToken.token),
            tokenType = TokenType(OAuth2TokenType.BEARER.type),
            expiresIn = ExpiresIn(time = accessToken.expiresIn),
            refreshToken = RefreshToken(refreshToken.token),
            scope = Scope(context.scope)
        )
    }

}

